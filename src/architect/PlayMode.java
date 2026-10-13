package architect;

import architect.edit.*;
import haven.*;

import java.awt.event.KeyEvent;
import java.util.*;

/**
 * Play mode: a character you walk around your plan with, seen through the game's own camera.
 *
 * The character is a real engine Composite assembled from the bundled character resources (body, hair,
 * clothes) and animated with the game's own poses: idle, walking, running, wading in shallow water and
 * swimming in deep water. Click to walk to a spot (as in the game), or steer with WASD; Shift walks instead
 * of running. Planned objects block the way by their real footprints. Esc leaves play mode.
 */
public class PlayMode {
    /**
     * The game's four speeds (1 crawl, 2 walk, 3 run, 4 sprint), in world units per second (a tile is 11).
     * Walk and run are the paces the walking and running animations were made for (their nspeed), so the
     * legs match the ground; crawl and sprint play those same animations slower or faster.
     */
    public static final double[] SPEEDS = {11.5, 22.92, 33.33, 44.0};
    public static final double SWIM = 12;
    /** Index into SPEEDS; the game's default is run. */
    public int speed = 2;
    /** Half-size of the character's square footprint, in world units. */
    static final double BODY = 2.5;
    private final PlannerView mv;
    private final Plan plan;
    public boolean on = false;
    private Gob gob = null;
    private Composite comp = null;
    private Stride stride = null;
    private double lastdebug = 0;

    /**
     * Tells the engine how fast the character moves. Walking and running poses advance by the owner's speed
     * (Skeleton: dt *= getv() / pose speed), so without this the walk cycle stands still. Positions the gob
     * the same way the game's own LinMove does.
     */
    static class Stride extends Moving {
        volatile double v = 0;

        Stride(Gob gob) {
            super(gob);
        }

        public Coord3f getc() {
            return(gob.placer().getc(gob.rc, gob.a));
        }

        public Coord3f gett() {
            return(getc());
        }

        public double getv() {
            return(v);
        }
    }
    private Coord2d pos, target = null;
    private double angle = 0;
    private String pose = null;
    private MapView.Camera prevcam = null;
    private Coord2d prevcc = null;
    private final Set<Integer> held = new HashSet<>();
    private boolean walk = false;
    public boolean female = false;

    public PlayMode(PlannerView mv) {
        this.mv = mv;
        this.plan = mv.plan;
    }

    /* ---------- the character ---------- */

    private static Indir<Resource> r(String name) {
        return(Resource.remote().load(name));
    }

    /**
     * The character's look, as the game describes it to the client: mesh resources, each with the textures laid
     * on it. Dev override for trying outfits: -Darchitect.outfit=mesh:tex+tex,mesh:tex (gfx/borka/ is implied).
     */
    private List<Composited.MD> outfit() {
        /* Hair colours (e.g. ravenblackhair) need the game's own recolouring and break the shader on their own,
         * so hair keeps its default colour. */
        String spec = System.getProperty("architect.outfit", female ? "female:female+linenpants+hempshirt,hair:hair"
                                                                    : "male:male+linenpants+hempshirt,hair:hair,beard:beard");
        List<Composited.MD> mods = new ArrayList<>();
        for(String part : spec.split(",")) {
            String[] mt = part.split(":");
            List<Indir<Resource>> tex = new ArrayList<>();
            if(mt.length > 1)
                for(String t : mt[1].split("\\+"))
                    tex.add(r("gfx/borka/" + t));
            mods.add(new Composited.MD(r("gfx/borka/" + mt[0]), ResData.wrap(tex)));
        }
        return(mods);
    }

    private void setpose(String name) {
        if(name.equals(pose) || comp == null)
            return;
        pose = name;
        /* A pose resource holds several layers; the bitmask in its data picks which apply (Skeleton.ModFactory.def).
         * The server always sends one; with none, no layer applies and the bones never move. */
        int mask = Integer.getInteger("architect.posemask", 0xffff);
        byte[] sdt = {(byte)(mask & 0xff), (byte)((mask >> 8) & 0xff)};
        comp.chposes(Collections.singletonList(new ResData(r(name), new MessageBuf(sdt))), true);
    }

    /* ---------- entering and leaving ---------- */

    /** Starts play mode with the character standing at at. */
    public void start(Coord2d at) {
        if(on)
            return;
        on = true;
        pos = at;
        target = null;
        pose = null;
        prevcam = mv.camera;
        prevcc = mv.cc;
        /* nurgling's ortho camera: the game view, but free rotation (SOrthoCam snaps to 90 degrees on release). */
        mv.camera = mv.new NOrthoCam();
        mv.cc = at;
        Glob glob = mv.glob;
        glob.loader.defer(() -> {
            Gob g = glob.oc.new Virtual(pos, angle);
            Composite c = new Composite(g, r("gfx/borka/body"));
            c.chmod(outfit());
            g.setattr(c);
            Stride st = new Stride(g);
            g.setattr(st);
            g.move(pos, angle);
            synchronized(PlayMode.this) {
                if(!on)
                    return;
                gob = g;
                comp = c;
                stride = st;
            }
            glob.oc.add(g);
            setpose("gfx/borka/idle");
        }, null);
    }

    public void stop() {
        if(!on)
            return;
        on = false;
        Gob g;
        synchronized(this) {
            g = gob;
            gob = null;
            comp = null;
            stride = null;
        }
        if(g != null) {
            /* Drop the movement attribute first: Gob.removed() iterates the attributes and nurgling's hook
             * for a removed Moving changes that same map (ConcurrentModificationException, dead UI thread).
             * Server characters always stop moving before they leave, so the game never hits this. */
            g.delattr(Moving.class);
            mv.glob.oc.remove(g);
        }
        if(prevcam != null)
            mv.camera = prevcam;
        if(prevcc != null)
            mv.cc = pos;
        held.clear();
    }

    /* ---------- moving ---------- */

    private String tileat(Coord2d p) {
        try {
            MCache map = mv.glob.map;
            return(map.tilesetname(map.gettile(p.floor(MCache.tilesz))));
        } catch(Loading l) {
            return(null);
        }
    }

    /** Water under the character: 0 dry, 1 shallow (wading), 2 deep (swimming). */
    private int water(Coord2d p) {
        String t = tileat(p);
        if(t == null)
            return(0);
        if(t.endsWith("/deep") || t.contains("/odeep") || t.endsWith("/seabed"))
            return(2);
        if(t.endsWith("/water") || t.contains("swampwater") || t.contains("owater"))
            return(1);
        return(0);
    }

    /** Whether the character could stand at p: inside the world and clear of every planned footprint. */
    private boolean free(Coord2d p) {
        if(!mv.inbounds(p))
            return(false);
        Coord2d[][] me = {{p.add(-BODY, -BODY), p.add(BODY, -BODY), p.add(BODY, BODY), p.add(-BODY, BODY)}};
        for(PlanObject o : plan.objects()) {
            if(o.rc.dist(p) > 80 || !plan.layers.shown(o.layer))
                continue;
            Footprint fp = Footprint.of(o.res);
            if(fp != null && fp.exact && Collisions.overlapTest(me, fp.world(o.rc, o.a)))
                return(false);
        }
        return(true);
    }

    public void walkto(Coord2d p) {
        target = p;
    }

    public void tick(double dt) {
        if(!on)
            return;
        dt = Math.min(dt, 0.1);
        Coord2d dir = keydir();
        if(dir != null)
            target = null;
        else if(target != null) {
            Coord2d d = target.sub(pos);
            double len = Math.hypot(d.x, d.y);
            if(len < 1)
                target = null;
            else
                dir = d.div(len);
        }
        int wet = water(pos);
        double moved = 0;
        if(dir != null) {
            int lvl = walk ? Math.min(speed, 1) : speed;
            double pace = (wet == 2) ? SWIM : SPEEDS[lvl];
            double step = pace * dt;
            if(target != null)
                step = Math.min(step, target.dist(pos));
            Coord2d np = pos.add(dir.mul(step));
            /* Blocked: slide along whichever axis is still free, like brushing past a wall. */
            if(!free(np)) {
                Coord2d nx = pos.add(dir.x * step, 0), ny = pos.add(0, dir.y * step);
                np = free(nx) ? nx : (free(ny) ? ny : null);
            }
            if(np == null) {
                target = null;
                setpose(wet == 2 ? "gfx/borka/swimman" : "gfx/borka/idle");
            } else {
                angle = Math.atan2(dir.y, dir.x);
                moved = np.dist(pos) / dt;
                pos = np;
                setpose((wet == 2) ? "gfx/borka/swimman" : (wet == 1) ? "gfx/borka/wading" : (lvl >= 2) ? "gfx/borka/running" : "gfx/borka/walking");
            }
        } else {
            setpose((wet == 2) ? "gfx/borka/swimman" : "gfx/borka/idle");
        }
        Stride st = stride;
        if(st != null)
            st.v = moved;
        if(Boolean.getBoolean("architect.debugplay") && (Utils.rtime() - lastdebug) > 0.5) {
            lastdebug = Utils.rtime();
            System.out.printf("[play] pos %s v %.1f pose %s speed %d fps %d%n", pos, moved, pose, speed, nurgling.NUtils.getFps());
        }
        Gob g = gob;
        if(g != null)
            g.move(pos, angle);
        mv.cc = pos;
    }

    /* ---------- keys ---------- */

    private Coord2d keydir() {
        double f = 0, r = 0;
        if(held.contains(KeyEvent.VK_W)) f += 1;
        if(held.contains(KeyEvent.VK_S)) f -= 1;
        if(held.contains(KeyEvent.VK_D)) r += 1;
        if(held.contains(KeyEvent.VK_A)) r -= 1;
        if(f == 0 && r == 0)
            return(null);
        /* Both cameras look along (-cos a, sin a) on the ground (Camera.makepointed, y inverted). */
        double a = mv.camera.angle();
        Coord2d fw = Coord2d.of(-Math.cos(a), Math.sin(a));
        Coord2d rt = Coord2d.of(-fw.y, fw.x);
        Coord2d d = fw.mul(f).add(rt.mul(r));
        return(d.div(Math.hypot(d.x, d.y)));
    }

    public boolean keydown(Widget.KeyDownEvent ev) {
        walk = (ev.mods & KeyMatch.S) != 0;
        switch(ev.code) {
        case KeyEvent.VK_W: case KeyEvent.VK_A: case KeyEvent.VK_S: case KeyEvent.VK_D:
            held.add(ev.code);
            return(true);
        case KeyEvent.VK_1: case KeyEvent.VK_2: case KeyEvent.VK_3: case KeyEvent.VK_4:
            speed = ev.code - KeyEvent.VK_1;
            return(true);
        case KeyEvent.VK_ESCAPE:
            stop();
            return(true);
        }
        return(false);
    }

    public boolean keyup(Widget.KeyUpEvent ev) {
        walk = (ev.mods & KeyMatch.S) != 0;
        return(held.remove(ev.code));
    }

    public void lostfocus() {
        held.clear();
    }
}
