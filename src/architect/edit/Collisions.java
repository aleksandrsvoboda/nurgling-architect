package architect.edit;

import haven.*;
import haven.render.*;

import java.awt.Color;
import java.util.*;

/**
 * Finds planned objects whose footprints overlap (they could not both be built) and tints them red.
 * Recomputed only when the plan changes; a spatial hash keeps it near-linear.
 */
public class Collisions {
    private final Plan plan;
    private int seq = -1;
    private Set<String> hit = Collections.emptySet();
    public boolean enabled = true;

    public Collisions(Plan plan) {
        this.plan = plan;
    }

    public static class Tint extends GAttrib implements Gob.SetupMod {
        static final Pipe.Op state = new BaseColor(new Color(255, 90, 80, 255));

        public Tint(Gob gob) {
            super(gob);
        }

        public Pipe.Op gobstate() {
            return(state);
        }
    }

    public int count() {
        return(hit.size());
    }

    public boolean colliding(PlanObject o) {
        return(hit.contains(o.id));
    }

    private static final double CELL = 33;

    private static boolean tree(PlanObject o) {
        return(o.res.startsWith("gfx/terobjs/trees/"));
    }

    /** Returns false when some footprints are still loading (try again next tick). */
    private boolean compute() {
        boolean complete = true;
        Map<Coord, List<Object[]>> cells = new HashMap<>();
        Set<String> found = new HashSet<>();
        for(PlanObject o : plan.objects()) {
            if(!plan.layers.shown(o.layer))
                continue;
            Footprint fp = Footprint.of(o.res);
            if(fp == null) {
                complete = false;
                continue;
            }
            Coord2d[][] w = fp.world(o.rc, o.a);
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for(Coord2d[] poly : w)
                for(Coord2d p : poly) {
                    x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y);
                    x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y);
                }
            Object[] ent = {o, w};
            Set<PlanObject> checked = new HashSet<>();
            for(int cy = (int)Math.floor(y0 / CELL); cy <= (int)Math.floor(y1 / CELL); cy++) {
                for(int cx = (int)Math.floor(x0 / CELL); cx <= (int)Math.floor(x1 / CELL); cx++) {
                    List<Object[]> cell = cells.computeIfAbsent(Coord.of(cx, cy), k -> new ArrayList<>());
                    for(Object[] other : cell) {
                        PlanObject po = (PlanObject)other[0];
                        /* Forests are scenery: trees brushing each other are not a planning problem. */
                        if(tree(o) && tree(po))
                            continue;
                        if(checked.add(po) && overlap(w, (Coord2d[][])other[1])) {
                            found.add(o.id);
                            found.add(po.id);
                        }
                    }
                    cell.add(ent);
                }
            }
        }
        hit = found;
        return(complete);
    }

    /** For tests. */
    public static boolean overlapTest(Coord2d[][] a, Coord2d[][] b) {
        return(overlap(a, b));
    }

    static boolean overlap(Coord2d[][] a, Coord2d[][] b) {
        for(Coord2d[] pa : a)
            for(Coord2d[] pb : b)
                if(sat(pa, pb))
                    return(true);
        return(false);
    }

    /* Separating-axis test for two convex polygons; touching edges do not count (flush placement is fine). */
    private static boolean sat(Coord2d[] a, Coord2d[] b) {
        return(!separated(a, b) && !separated(b, a));
    }

    private static boolean separated(Coord2d[] a, Coord2d[] b) {
        for(int i = 0; i < a.length; i++) {
            Coord2d p = a[i], q = a[(i + 1) % a.length];
            double nx = -(q.y - p.y), ny = q.x - p.x;
            double amin = Double.MAX_VALUE, amax = -Double.MAX_VALUE, bmin = Double.MAX_VALUE, bmax = -Double.MAX_VALUE;
            for(Coord2d v : a) {double d = v.x * nx + v.y * ny; amin = Math.min(amin, d); amax = Math.max(amax, d);}
            for(Coord2d v : b) {double d = v.x * nx + v.y * ny; bmin = Math.min(bmin, d); bmax = Math.max(bmax, d);}
            double eps = 1e-3 * Math.hypot(nx, ny);
            if(amax <= bmin + eps || bmax <= amin + eps)
                return(true);
        }
        return(false);
    }

    /** Recomputes when the plan changed and keeps the red tints in step. Call every tick. */
    public void tick() {
        int s = plan.seq + 31 * plan.layers.seq;
        if(s != seq) {
            seq = s;
            if(enabled) {
                if(!compute())
                    seq = -1;
            } else
                hit = Collections.emptySet();
        }
        for(PlanObject o : plan.objects()) {
            Gob g = o.gob();
            if(g == null)
                continue;
            boolean want = hit.contains(o.id), has = g.getattr(Tint.class) != null;
            if(want && !has)
                g.setattr(new Tint(g));
            else if(!want && has)
                g.delattr(Tint.class);
        }
    }

    public void toggle() {
        enabled = !enabled;
        seq = -1;
    }
}
