package architect;

import architect.edit.*;
import haven.*;

/**
 * Dev (-Darchitect.editcheck=true): clicks a planned object where it shows on screen, then drags it, through
 * the edit tool's own press/drag/release, and logs whether it got selected and moved. Checks selecting and
 * moving without taking over the real mouse.
 */
class EditCheck {
    private final PlannerView mv;
    private double t0 = -1;
    private int step = 0;
    private PlanObject o;
    private Coord sc;
    private Coord2d was, was2;
    private PlanObject o2;
    private Coord sc2;

    EditCheck(PlannerView mv) {
        this.mv = mv;
    }

    /* Camera check (-Darchitect.camcheck=true): a right-drag right and down with each inversion setting. */
    private int cstep = 0;
    private float[][] turned = new float[4][];

    private void camdrag(int dx, int dy) {
        Coord c = mv.sz.div(2);
        mv.mousedown(new Widget.MouseDownEvent(c, 3));
        mv.mousemove(new Widget.MouseMoveEvent(c.add(dx, dy)));
        mv.mouseup(new Widget.MouseUpEvent(c.add(dx, dy), 3));
    }

    boolean camtick() {
        if(!Boolean.getBoolean("architect.camcheck"))
            return(false);
        if(!(mv.camera instanceof PlannerView.OrbitCam) || cstep >= 4) {
            if(cstep == 4) {
                System.out.println(String.format("[camcheck] normal: turn %+.2f tilt %+.2f | invert X: turn %+.2f tilt %+.2f | invert Y: turn %+.2f tilt %+.2f | both: turn %+.2f tilt %+.2f",
                                                 turned[0][0], turned[0][1], turned[1][0], turned[1][1], turned[2][0], turned[2][1], turned[3][0], turned[3][1]));
                PlannerView.invertx = PlannerView.inverty = false;
                cstep++;
            }
            return(true);
        }
        PlannerView.OrbitCam cam = (PlannerView.OrbitCam)mv.camera;
        PlannerView.invertx = (cstep & 1) != 0;
        PlannerView.inverty = (cstep & 2) != 0;
        float a = cam.heading(), e = cam.tilt();
        camdrag(30, 30);
        turned[cstep] = new float[] {cam.heading() - a, cam.tilt() - e};
        camdrag(-30, -30);
        cstep++;
        return(true);
    }

    void tick() {
        double now = Utils.rtime();
        if(t0 < 0)
            t0 = now + 10;
        if(now > t0 && camtick())
            return;
        if(t0 < 0)
            t0 = now + 10;
        if(now < t0)
            return;
        switch(step) {
        case 0:
            for(PlanObject p : mv.plan.objects())
                if(p.gob() != null && mv.plan.editable(p) && !p.res.contains("/trees/")) {
                    Coord3f s = mv.screenxf(p.gob().getc());
                    Coord c = Coord.of((int)s.x, (int)s.y);
                    if(c.isect(Coord.of(300, 100), mv.sz.sub(600, 200))) {
                        o = p;
                        sc = c;
                        break;
                    }
                }
            if(o == null) {
                System.out.println("[editcheck] no object on screen");
                step = 99;
                return;
            }
            was = o.rc;
            System.out.println("[editcheck] clicking " + o.res + " at screen " + sc + " world " + o.rc);
            mv.edit.press(sc, 0);
            break;
        case 2:
            mv.edit.release(sc, 0);
            break;
        case 6:
            System.out.println("[editcheck] after click: selected=" + mv.sel.size() + " contains=" + mv.sel.contains(o) + " dragging=" + mv.edit.dragging());
            mv.edit.press(sc, 0);
            break;
        case 8: case 9: case 10:
            mv.edit.drag(sc.add((step - 7) * 30, 0), 0);
            break;
        case 13:
            mv.edit.release(sc.add(90, 0), 0);
            break;
        case 16:
            System.out.println("[editcheck] after drag: moved " + o.rc.dist(was) + " from " + was + " to " + o.rc + " selected=" + mv.sel.size());
            /* Shift+click a second object, then drag the first: both should move */
            for(PlanObject p : mv.plan.objects())
                if(p != o && p.gob() != null && mv.plan.editable(p) && !p.res.contains("/trees/")) {
                    Coord3f s = mv.screenxf(p.gob().getc());
                    Coord c = Coord.of((int)s.x, (int)s.y);
                    if(c.isect(Coord.of(300, 100), mv.sz.sub(600, 200)) && c.dist(sc) > 40) {
                        o2 = p;
                        sc2 = c;
                        break;
                    }
                }
            if(o2 == null) {
                System.out.println("[editcheck] no second object on screen");
                step = 99;
                return;
            }
            mv.edit.press(sc2, UI.MOD_SHIFT);
            break;
        case 18:
            mv.edit.release(sc2, UI.MOD_SHIFT);
            break;
        case 21:
            System.out.println("[editcheck] after shift-click: selected=" + mv.sel.size());
            was = o.rc;
            was2 = o2.rc;
            Coord3f s = mv.screenxf(o.gob().getc());
            sc = Coord.of((int)s.x, (int)s.y);
            mv.edit.press(sc, 0);
            break;
        case 23: case 24: case 25:
            mv.edit.drag(sc.add(0, (step - 22) * 30), 0);
            break;
        case 28:
            mv.edit.release(sc.add(0, 90), 0);
            break;
        case 31:
            System.out.println("[editcheck] group drag: first moved " + o.rc.sub(was) + ", second moved " + o2.rc.sub(was2) + " selected=" + mv.sel.size());
            break;
        }
        step++;
    }
}
