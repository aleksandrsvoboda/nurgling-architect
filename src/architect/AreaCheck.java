package architect;

import architect.edit.*;
import haven.*;

/**
 * Dev (-Darchitect.areacheck=true): draws an area, moves it and resizes it through the area tool's own
 * press/drag/release at the tiles' screen positions, and logs each result. Checks the map side of Areas mode
 * without taking over the real mouse.
 */
class AreaCheck {
    private final PlannerView mv;
    private double t0 = -1;
    private int step = 0;
    private Coord base;
    private PlanArea made;

    AreaCheck(PlannerView mv) {
        this.mv = mv;
    }

    /** Screen point of a world point a little inside tile t (fx, fy = 0..1 across the tile). */
    private Coord at(Coord t, double fx, double fy) {
        double s = MCache.tilesz.x;
        Coord3f p = mv.screenxf(Coord2d.of((t.x + fx) * s, (t.y + fy) * s));
        return(Coord.of(Math.round(p.x), Math.round(p.y)));
    }

    void tick() {
        double now = Utils.rtime();
        if(t0 < 0)
            t0 = now + 9;
        if(now < t0)
            return;
        switch(step) {
        case 0:
            mv.areas.on = true;
            base = mv.cc.floor(MCache.tilesz).add(-3, -3);
            mv.areas.press(at(base, 0.5, 0.5), 0);
            break;
        case 2: mv.areas.drag(at(base.add(1, 1), 0.5, 0.5)); break;
        case 4: mv.areas.drag(at(base.add(3, 2), 0.5, 0.5)); break;
        case 6: mv.areas.release(); break;
        case 8:
            made = mv.areas.selarea();
            System.out.println("[areacheck] drawn: " + ((made == null) ? "nothing" : made.name + " " + made.tiles + " size " + made.tiles.sz()));
            /* move: grab the middle, drag 2 tiles right */
            mv.areas.press(at(base.add(1, 1), 0.5, 0.5), 0);
            break;
        case 10: mv.areas.drag(at(base.add(2, 1), 0.5, 0.5)); break;
        case 12: mv.areas.drag(at(base.add(3, 1), 0.5, 0.5)); break;
        case 14: mv.areas.release(); break;
        case 16: {
            PlanArea a = mv.plan.area(made.id);
            System.out.println("[areacheck] moved: " + a.tiles + " (was " + made.tiles + ")");
            made = a;
            /* resize: grab the right edge (last tile, near its right side), drag 2 tiles further right */
            Coord last = Coord.of(a.tiles.br.x - 1, a.tiles.ul.y + 1);
            mv.areas.press(at(last, 0.95, 0.5), 0);
            break;
        }
        case 18: mv.areas.drag(at(Coord.of(made.tiles.br.x, made.tiles.ul.y + 1), 0.5, 0.5)); break;
        case 20: mv.areas.drag(at(Coord.of(made.tiles.br.x + 1, made.tiles.ul.y + 1), 0.5, 0.5)); break;
        case 22: mv.areas.release(); break;
        case 24: {
            PlanArea a = mv.plan.area(made.id);
            System.out.println("[areacheck] resized: " + a.tiles + " size " + a.tiles.sz() + " (was " + made.tiles.sz() + ")");
            mv.plan.undo();
            System.out.println("[areacheck] after undo: " + mv.plan.area(made.id).tiles.sz());
            break;
        }
        }
        step++;
    }
}
