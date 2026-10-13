package architect.edit;

import haven.*;
import nurgling.NHitBox;
import nurgling.pf.NHitBoxD;

import java.util.*;

/**
 * Ctrl while dragging planned objects: the selection sticks flush to nearby objects, the way nurgling's
 * PlacementSnap makes a placement ghost stick (same capture distance, same per-axis rules), with the whole
 * selection treated as one box. Objects being moved are not neighbours of themselves.
 */
public class GroupSnap {
    public static final double CAPTURE = nurgling.tools.PlacementSnap.CAPTURE;
    private static final double SEARCH = 150;

    /** An object's footprint box at a position and angle, as nurgling measures it, or null if it has none. */
    static Coord2d[] box(PlanObject o, Coord2d rc) {
        Gob g = o.gob();
        NHitBox hb = (g != null && g.ngob != null) ? g.ngob.hitBox : null;
        if(hb == null)
            hb = NHitBox.findCustom(o.res);
        if(hb == null)
            return(null);
        NHitBoxD d = new NHitBoxD(hb.begin, hb.end, rc, o.a);
        return(new Coord2d[] {d.getCircumscribedUL(), d.getCircumscribedBR()});
    }

    /**
     * How far to shift the moved objects (each at its proposed position) so their combined box lies flush
     * against a neighbour, per axis, when within CAPTURE; zero on an axis with nothing in range.
     */
    public static Coord2d shift(Plan plan, Map<PlanObject, Coord2d> moved) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for(Map.Entry<PlanObject, Coord2d> e : moved.entrySet()) {
            Coord2d[] b = box(e.getKey(), e.getValue());
            if(b == null)
                continue;
            x0 = Math.min(x0, b[0].x); y0 = Math.min(y0, b[0].y);
            x1 = Math.max(x1, b[1].x); y1 = Math.max(y1, b[1].y);
        }
        if(x0 > x1)
            return(Coord2d.z);
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, hw = (x1 - x0) / 2, hh = (y1 - y0) / 2;
        Coord2d c = Coord2d.of(cx, cy);
        double bestx = CAPTURE, besty = CAPTURE;
        Double sx = null, sy = null;
        for(PlanObject o : plan.objects()) {
            if(moved.containsKey(o) || !plan.layers.shown(o.layer) || o.rc.dist(c) > SEARCH + Math.max(hw, hh))
                continue;
            Coord2d[] n = box(o, o.rc);
            if(n == null)
                continue;
            Coord2d nul = n[0], nbr = n[1];
            /* side by side on x only when the spans overlap on y (within capture), and the other way round */
            if((cy + hh > nul.y - CAPTURE) && (cy - hh < nbr.y + CAPTURE)) {
                for(double t : new double[] {nul.x - hw, nbr.x + hw, nul.x + hw, nbr.x - hw}) {
                    double d = Math.abs(t - cx);
                    if(d < bestx) {bestx = d; sx = t;}
                }
            }
            if((cx + hw > nul.x - CAPTURE) && (cx - hw < nbr.x + CAPTURE)) {
                for(double t : new double[] {nul.y - hh, nbr.y + hh, nul.y + hh, nbr.y - hh}) {
                    double d = Math.abs(t - cy);
                    if(d < besty) {besty = d; sy = t;}
                }
            }
        }
        return(Coord2d.of((sx == null) ? 0 : sx - cx, (sy == null) ? 0 : sy - cy));
    }
}
