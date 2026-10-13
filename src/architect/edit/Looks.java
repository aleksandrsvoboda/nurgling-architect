package architect.edit;

import haven.*;

import java.util.*;

/**
 * How an object looks when the plan has no state for it. In the game the server sends every object a little
 * state data (a stockpile's fill, a kiln's parts, a crop's growth stage). Without it:
 *  - the model sprite draws a part numbered 0-15 only when that data switches it on, so objects made only of
 *    numbered parts (tar kiln, stockpiles, fireplace) draw nothing; for those every part is switched on;
 *  - crops cannot be built at all; they get their last growth stage (parts are numbered stage * 10 + variant).
 * Which applies is found once per object kind by building it with no data and looking at the sprite.
 */
public class Looks {
    private static final Map<String, byte[]> cache = new HashMap<>();
    private static final Set<String> broken = Collections.synchronizedSet(new HashSet<>());
    private static final byte[] NONE = new byte[0];

    /** State data to draw res, standing at rc, with when none is given. Throws Loading while res loads. */
    public static byte[] sdt(Glob glob, Coord2d rc, Resource res) {
        synchronized(cache) {
            byte[] b = cache.get(res.name);
            if(b != null)
                return(b);
        }
        byte[] ret = NONE;
        Collection<FastMesh.MeshRes> ms = res.layers(FastMesh.MeshRes.class);
        if(res.name.startsWith("gfx/terobjs/plants/") && !ms.isEmpty()) {
            int top = 0;
            for(FastMesh.MeshRes m : ms)
                top = Math.max(top, m.id / 10);
            ret = new byte[] {(byte)top};
        } else {
            int mask = 0;
            for(FastMesh.MeshRes m : ms)
                if(m.id >= 0 && m.id < 16 && m.mat != null)
                    mask |= 1 << m.id;
            if(mask != 0 && dark(glob, rc, res, mask))
                ret = mask(mask | 0xffff0000);
        }
        synchronized(cache) {
            cache.put(res.name, ret);
        }
        return(ret);
    }

    /**
     * Whether res, built with no data, is a model sprite with none of its numbered parts switched on. Built where
     * the object stands: some sprites read the ground under them and wait for its map data.
     */
    private static boolean dark(Glob glob, Coord2d rc, Resource res, int mask) {
        try {
            ResDrawable d = new ResDrawable(new Gob(glob, rc), res.indir(), MessageBuf.nil);
            if(!(d.spr instanceof ModSprite))
                return(false);
            if((((ModSprite)d.spr).flags & mask) != 0)
                return(false);
            for(FastMesh.MeshRes m : res.layers(FastMesh.MeshRes.class))
                if(m.id < 0 && m.mat != null)
                    return(false);
            return(true);
        } catch(Loading l) {
            throw(l);
        } catch(RuntimeException e) {
            /* needs data to build at all; the drawable will report it */
            return(false);
        }
    }

    static byte[] mask(int m) {
        return(new byte[] {(byte)m, (byte)(m >>> 8), (byte)(m >>> 16), (byte)(m >>> 24)});
    }

    /** The data to draw a planned object with: its own, else the default look. */
    public static Message of(Glob glob, Coord2d rc, Resource res, byte[] sdt) {
        return(new MessageBuf(((sdt != null) && (sdt.length > 0)) ? sdt : sdt(glob, rc, res)));
    }

    /**
     * Builds the drawable for an object, or null if its sprite cannot be built without the server's data
     * (internal pieces like wall extensions). A failure is reported once and must not escape: it would kill the
     * loader thread and stall every object after it.
     */
    public static ResDrawable drawable(Gob g, Resource res, byte[] sdt) {
        if(broken.contains(res.name))
            return(null);
        try {
            return(new ResDrawable(g, res.indir(), of(g.glob, g.rc, res, sdt)));
        } catch(Loading l) {
            throw(l);
        } catch(RuntimeException e) {
            /* Sprite code ships with the resource; anything it throws on missing data lands here. */
            broken.add(res.name);
            System.out.println("[architect] cannot draw " + res.name + ": " + e);
            return(null);
        }
    }

    /** The default state already worked out for res (it has been drawn), or null if not yet known. */
    public static byte[] known(String res) {
        synchronized(cache) {
            return(cache.get(res));
        }
    }

    public static boolean broken(String res) {
        return(broken.contains(res));
    }
}
