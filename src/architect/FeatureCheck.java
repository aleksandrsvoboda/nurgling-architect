package architect;

import architect.edit.*;
import haven.*;

import java.util.*;

/**
 * Dev (-Darchitect.featurecheck=true): exercises Ctrl placement snap, mirroring and the square paint brush
 * through the planner's own code and logs the results; -Darchitect.showbb / gridwalls switch those views on
 * for screenshots. Leaves the real mouse alone.
 */
class FeatureCheck {
    private final PlannerView mv;
    private double t0 = -1;
    private int step = 0;
    private PlanObject a, b;

    FeatureCheck(PlannerView mv) {
        this.mv = mv;
    }

    void tick() {
        double now = Utils.rtime();
        if(t0 < 0)
            t0 = now + 9;
        if(now < t0)
            return;
        Coord2d c = mv.cc.floor(MCache.tilesz).mul(MCache.tilesz).add(MCache.tilesz.div(2));
        switch(step) {
        case 0:
            /* two cupboards, one turned, for mirroring and as a snap target */
            a = new PlanObject("gfx/terobjs/cupboard", null, c, 0);
            b = new PlanObject("gfx/terobjs/cupboard", null, c.add(33, 11), Math.PI / 2);
            mv.plan.add(a);
            mv.plan.add(b);
            mv.place("gfx/terobjs/cupboard");
            break;
        case 30: {
            MapView.Plob p = null;
            try {
                java.lang.reflect.Field f = MapView.class.getDeclaredField("placing");
                f.setAccessible(true);
                @SuppressWarnings("unchecked") Loader.Future<MapView.Plob> fut = (Loader.Future<MapView.Plob>)f.get(mv);
                p = (fut != null && fut.done()) ? fut.get() : null;
            } catch(ReflectiveOperationException e) {
                System.out.println("[featurecheck] no plob: " + e);
            }
            if(p != null) {
                Coord2d near = a.rc.add(0, -12);
                Coord2d s = nurgling.tools.PlacementSnap.snap(p, near);
                System.out.println("[featurecheck] ctrl snap: cursor " + near + " -> " + s + " (cupboard at " + a.rc + ")");
            } else {
                System.out.println("[featurecheck] ctrl snap: placing ghost not ready");
            }
            mv.cancelplace();
            break;
        }
        case 32: {
            Coord2d ra = a.rc, rb = b.rc;
            double aa = a.a, ab = b.a;
            mv.sel.set(Arrays.asList(a, b));
            mv.edit.mirror(false);
            System.out.println(String.format("[featurecheck] mirror E-W: a %s %.2f -> %s %.2f, b %s %.2f -> %s %.2f", ra, aa, a.rc, a.a, rb, ab, b.rc, b.a));
            mv.edit.mirror(true);
            System.out.println(String.format("[featurecheck] mirror N-S: a %s %.2f, b %s %.2f", a.rc, a.a, b.rc, b.a));
            mv.plan.undo();
            mv.plan.undo();
            System.out.println("[featurecheck] after 2 undos: a " + a.rc + " b " + b.rc);
            break;
        }
        case 34: {
            TerrainTool t = mv.terrain;
            t.paint = "gfx/tiles/paving/graybrick";
            t.paintside = 1;
            int one = t.paintat(c.add(-110, -110));
            t.paintside = 3;
            int nine = t.paintat(c.add(-220, -110));
            System.out.println("[featurecheck] paint: 1x1 changed " + one + " tile(s), 3x3 changed " + nine);
            break;
        }
        }
        step++;
    }
}
