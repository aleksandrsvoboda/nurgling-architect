package architect.edit;

import haven.*;

import java.util.*;

/**
 * An object's ground footprint: the polygons of its resource's "obst" layer (what the game uses for
 * collision), else nurgling's hand-made hitbox, else one tile. Coordinates are world units around the
 * object's centre at angle 0.
 */
public class Footprint {
    public final Coord2d[][] polys;
    /** True when the shape came from the resource itself rather than a guess. */
    public final boolean exact;
    private static final Map<String, Footprint> cache = new HashMap<>();

    private Footprint(Coord2d[][] polys, boolean exact) {
        this.polys = polys;
        this.exact = exact;
    }

    private static Footprint box(Coord2d a, Coord2d b, boolean exact) {
        return(new Footprint(new Coord2d[][] {{a, Coord2d.of(b.x, a.y), b, Coord2d.of(a.x, b.y)}}, exact));
    }

    /** The footprint of res, or null while it is still loading. */
    public static Footprint of(String res) {
        synchronized(cache) {
            Footprint f = cache.get(res);
            if(f != null)
                return(f);
        }
        Resource r;
        try {
            r = Resource.remote().load(res).get();
        } catch(Loading l) {
            return(null);
        } catch(Resource.LoadException | Resource.BadResourceException e) {
            r = null;
        }
        Footprint f = null;
        if(r != null) {
            List<Coord2d[]> ps = new ArrayList<>();
            for(Resource.Obstacle o : r.layers(Resource.Obstacle.class))
                ps.addAll(Arrays.asList(o.p));
            if(!ps.isEmpty())
                f = new Footprint(ps.toArray(new Coord2d[0][]), true);
        }
        if(f == null) {
            nurgling.NHitBox hb = nurgling.NHitBox.findCustom(res);
            f = (hb != null) ? box(hb.begin, hb.end, false)
                : box(MCache.tilesz.div(-2), MCache.tilesz.div(2), false);
        }
        synchronized(cache) {
            cache.put(res, f);
        }
        return(f);
    }

    /** Length of the footprint along world direction (dx,dy) for an object turned by a. */
    public double extent(double a, Coord2d dir) {
        double len = Math.hypot(dir.x, dir.y);
        if(len == 0)
            return(0);
        double ux = dir.x / len, uy = dir.y / len;
        double cos = Math.cos(a), sin = Math.sin(a);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for(Coord2d[] poly : polys) {
            for(Coord2d p : poly) {
                double wx = p.x * cos - p.y * sin, wy = p.x * sin + p.y * cos;
                double d = wx * ux + wy * uy;
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
        }
        return(max - min);
    }

    /** The polygons placed in the world at rc, turned by a. */
    public Coord2d[][] world(Coord2d rc, double a) {
        double cos = Math.cos(a), sin = Math.sin(a);
        Coord2d[][] ret = new Coord2d[polys.length][];
        for(int i = 0; i < polys.length; i++) {
            ret[i] = new Coord2d[polys[i].length];
            for(int k = 0; k < polys[i].length; k++) {
                Coord2d p = polys[i][k];
                ret[i][k] = Coord2d.of(rc.x + p.x * cos - p.y * sin, rc.y + p.x * sin + p.y * cos);
            }
        }
        return(ret);
    }
}
