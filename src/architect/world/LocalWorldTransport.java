package architect.world;

import haven.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * A Transport with no socket: an in-process "map server".
 *
 * The engine keeps its own streaming logic. MCache.sendreqs() sends MSG_MAPREQ(gc) for grids the view
 * needs, we encode that grid from the WorldSource and hand it to MCache.mapdata2 as if the server had
 * replied. Everything else the client "sends" (session key, widget messages) is dropped.
 *
 * Like the real server, it only answers grids near the viewer and tells MCache to drop grids it has
 * left behind. Without that bound, every arriving grid meshes, samples its neighbours' heights,
 * requests them, and the world floods outward until the heap is gone (seen in the first spike).
 *
 * Replies go out on our own thread: sendreqs() calls us while holding MCache.req, and mapdata2 takes
 * MCache.grids then req, so answering inline could deadlock against the render thread.
 */
public class LocalWorldTransport implements Transport {
    /** Grid id of filler grids where the world has no data. Never exported. */
    public static final long VOID_ID = 0;
    /** Tile drawn where the world has no data (unexplored), so gaps read as gaps rather than black holes. */
    public static final String VOID_TILE = "gfx/tiles/nil";

    private final Collection<Callback> cbs = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Coord> pending = new LinkedBlockingQueue<>();
    private final Set<Coord> queued = ConcurrentHashMap.newKeySet();
    private volatile WorldSource world;
    private final GridEncoder enc;
    private volatile MCache map;
    private final Thread worker;
    private volatile boolean closed = false;
    /** Grids answered around the focus grid (Chebyshev radius); one more ring is kept before trimming. */
    private volatile java.util.function.IntSupplier range = () -> 2;
    private volatile java.util.function.Supplier<Coord2d> focus = null;
    private Coord trimmedAt = null;
    private int trimmedRange = -1;

    public LocalWorldTransport(WorldSource world, GridEncoder enc) {
        this.world = world;
        this.enc = enc;
        this.worker = new HackThread(this::serve, "Architect map server");
        this.worker.setDaemon(true);
    }

    /** Starts answering; call once the Session (and so the Glob/MCache) exists. */
    public void start(Session sess) {
        this.map = sess.glob.map;
        worker.start();
    }

    /** The world position the viewer looks at; grids are served and kept around it. */
    public void focus(java.util.function.Supplier<Coord2d> focus) {
        this.focus = focus;
    }

    /** How many grids around the viewer to serve; follows the view distance. */
    public void range(java.util.function.IntSupplier range) {
        this.range = range;
    }

    private Coord focusgrid() {
        java.util.function.Supplier<Coord2d> f = focus;
        if(f == null)
            return(null);
        return(f.get().floor(MCache.tilesz).div(MCache.cmaps));
    }

    private static boolean near(Coord gc, Coord fg, int r) {
        return(Math.abs(gc.x - fg.x) <= r && Math.abs(gc.y - fg.y) <= r);
    }

    /** What the server's invalblob(1, ul, lr) does: forget grids outside the window around the viewer. */
    private void trim(Coord fg) {
        int rg = range.getAsInt();
        if(fg == null || (fg.equals(trimmedAt) && rg == trimmedRange))
            return;
        trimmedAt = fg;
        trimmedRange = rg;
        Coord r = Coord.of(rg + 1, rg + 1);
        synchronized(map) {
            map.trim(fg.sub(r), fg.add(r));
        }
    }

    /** Sends grids again after their terrain was edited, if the view has them loaded. */
    public void refresh(Collection<Coord> gcs) {
        MCache m = map;
        if(m == null)
            return;
        for(Coord gc : gcs) {
            boolean loaded;
            synchronized(m.grids) {
                loaded = m.grids.containsKey(gc);
            }
            if(loaded) {
                m.request(gc);
                if(queued.add(gc))
                    pending.add(gc);
            }
        }
    }

    public WorldSource world() {
        return(world);
    }

    public void queuemsg(PMessage msg) {
        if(msg.type == Session.MSG_MAPREQ) {
            Coord gc = new MessageBuf(msg.fin()).coord();
            if(queued.add(gc)) {
                pending.add(gc);
                if(Boolean.getBoolean("architect.tracegrids"))
                    new Throwable("[architect] mapreq " + gc).printStackTrace(System.out);
            }
        }
    }

    public void send(PMessage msg) {
        queuemsg(msg);
    }

    public Transport add(Callback cb) {
        cbs.add(cb);
        return(this);
    }

    public void close() {
        if(closed)
            return;
        closed = true;
        worker.interrupt();
        for(Callback cb : cbs)
            cb.closed();
    }

    private void serve() {
        try {
            while(!closed) {
                Coord gc = pending.poll(250, TimeUnit.MILLISECONDS);
                Coord fg = focusgrid();
                trim(fg);
                if(gc == null)
                    continue;
                queued.remove(gc);
                if(fg == null || !near(gc, fg, range.getAsInt()))
                    continue;   /* out of range: dropped, as the server does; asked again once the viewer is near */
                try {
                    GridData g = world.grid(gc);
                    if(g == null)
                        g = GridData.flat(VOID_ID, VOID_TILE, 0);
                    map.mapdata2(enc.encode(gc, g));
                } catch(RuntimeException e) {
                    /* One bad grid must not stop the server; it is requested again later. */
                    new Warning(e, "could not serve grid " + gc).issue();
                }
            }
        } catch(InterruptedException e) {
            /* closing */
        }
    }
}
