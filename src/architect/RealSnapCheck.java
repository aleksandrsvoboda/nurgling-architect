package architect;

import architect.edit.*;
import haven.*;

/**
 * Dev (-Darchitect.realsnap=true): places a cupboard ghost, then with java.awt.Robot holds the real Ctrl key
 * and moves the real cursor beside a drying frame, logging where the ghost goes. The full input path, OS
 * included; takes the mouse for a few seconds.
 */
class RealSnapCheck {
    private final PlannerView mv;
    private double t0 = -1;
    private int step = 0;
    private java.awt.Robot robot;
    private java.awt.Point origin;
    private PlanObject f, cup;

    RealSnapCheck(PlannerView mv) {
        this.mv = mv;
    }

    private MapView.Plob plob() {
        try {
            java.lang.reflect.Field fl = MapView.class.getDeclaredField("placing");
            fl.setAccessible(true);
            @SuppressWarnings("unchecked") Loader.Future<MapView.Plob> fut = (Loader.Future<MapView.Plob>)fl.get(mv);
            return((fut != null && fut.done()) ? fut.get() : null);
        } catch(ReflectiveOperationException e) {
            return(null);
        }
    }

    private void moveto(Coord2d mc) {
        Coord3f sp = mv.screenxf(mc);
        Coord vp = Coord.of(Math.round(sp.x), Math.round(sp.y)).add(mv.rootpos());
        robot.mouseMove(origin.x + vp.x, origin.y + vp.y);
    }

    void tick() {
        double now = Utils.rtime();
        if(t0 < 0)
            t0 = now + 10;
        if(now < t0)
            return;
        try {
            switch(step) {
            case 0:
                robot = new java.awt.Robot();
                for(java.awt.Frame fr : java.awt.Frame.getFrames())
                    if(fr.isShowing())
                        for(java.awt.Component c : fr.getComponents())
                            origin = findcanvas(c);
                for(PlanObject o : mv.plan.objects())
                    if(o.res.endsWith("/dframe")) { f = o; break; }
                System.out.println("[realsnap] canvas at " + origin + ", frame " + ((f == null) ? null : f.rc));
                mv.cc = f.rc;
                mv.place("gfx/terobjs/cupboard");
                break;
            case 20:
                moveto(f.rc.add(30, -30));
                break;
            case 25:
                robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                break;
            case 28: moveto(f.rc.add(10, -20)); break;
            case 32: moveto(f.rc.add(1.3, -14.6)); break;
            case 40:
                System.out.println("[realsnap] Ctrl held, cursor 14.6 above the frame at " + f.rc + ": ghost " + plob().rc + "  ui.modctrl " + mv.ui.modctrl);
                moveto(f.rc.add(2.6, -15.2));
                break;
            case 48:
                System.out.println("[realsnap] Ctrl held, moved a little: ghost " + plob().rc);
                robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                moveto(f.rc.add(1.3, -14.6));
                break;
            case 56:
                System.out.println("[realsnap] Ctrl released: ghost " + plob().rc);
                mv.cancelplace();
                /* a cupboard 3 tiles above the frame, to drag down against it */
                cup = new PlanObject("gfx/terobjs/cupboard", null, f.rc.add(0, -40), 0);
                mv.plan.add(cup);
                break;
            case 66:
                moveto(cup.rc);
                break;
            case 68:
                robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                break;
            case 70: moveto(cup.rc.add(0.4, 6)); break;
            case 72: moveto(f.rc.add(0.7, -40 + 14)); break;
            case 74: moveto(f.rc.add(1.1, -40 + 22.6)); break;
            case 80:
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                break;
            case 84:
                System.out.println("[realsnap] Ctrl-drag cupboard toward the frame at " + f.rc + " (top edge " + (f.rc.y - 11) + "): cupboard now " + cup.rc + ", selected " + mv.sel.size());
                break;
            }
        } catch(java.awt.AWTException | RuntimeException e) {
            System.out.println("[realsnap] " + e);
            if(robot != null)
                robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
            step = 1000;
        }
        step++;
    }

    private java.awt.Point findcanvas(java.awt.Component c) {
        if(c instanceof java.awt.Container)
            for(java.awt.Component k : ((java.awt.Container)c).getComponents()) {
                java.awt.Point p = findcanvas(k);
                if(p != null)
                    return(p);
            }
        if(c.isShowing() && c.getWidth() > 400 && !(c instanceof java.awt.Container && ((java.awt.Container)c).getComponentCount() > 0))
            return(c.getLocationOnScreen());
        return(null);
    }
}
