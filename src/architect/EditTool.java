package architect;

import architect.edit.*;
import haven.*;
import nurgling.NStyle;

import java.awt.event.KeyEvent;
import java.util.*;

/**
 * Selecting and changing planned objects in the view (whenever nothing is being placed).
 *
 * Click selects (Shift toggles), dragging a selected object moves the selection with placing's snapping
 * (tile centre, Shift fine grid, Ctrl free), dragging on empty ground box-selects. Keys: R / Shift+R turn the
 * selection 90 degrees, Delete removes, arrows nudge, Ctrl+A/C/V/D select all/copy/paste/duplicate,
 * Ctrl+Z / Ctrl+Y undo/redo, Esc clears the selection.
 *
 * Hit tests run on the GPU and answer a frame later, so every callback checks it still applies.
 */
public class EditTool {
    private final PlannerView mv;
    private final Plan plan;
    private final Selection sel;

    private enum Mode {NONE, PENDING, MOVE, BOX}
    private Mode mode = Mode.NONE;
    private boolean released;
    private Coord pressc, boxc;
    private int pressmods;
    /* Moving: the grabbed object, where the grab hit the ground, and where everything started. */
    private PlanObject anchor;
    private Coord2d grabmc;
    private final Map<PlanObject, Coord2d> starts = new HashMap<>();

    /** Copied objects, positioned relative to the first one. */
    private final List<Plan.State> clipboard = new ArrayList<>();

    public EditTool(PlannerView mv) {
        this.mv = mv;
        this.plan = mv.plan;
        this.sel = mv.sel;
    }

    public boolean dragging() {
        return(mode != Mode.NONE);
    }

    private static Gob gobof(ClickData inf) {
        if(inf == null)
            return(null);
        if(inf.ci instanceof Gob.GobClick)
            return(((Gob.GobClick)inf.ci).gob);
        if(inf.ci instanceof Composited.CompositeClick)
            return(((Composited.CompositeClick)inf.ci).gi.gob);
        return(null);
    }

    /* ---------- mouse ---------- */

    public void press(Coord c, int mods) {
        mode = Mode.PENDING;
        released = false;
        pressc = boxc = c;
        pressmods = mods;
        mv.new Hittest(c) {
            protected void hit(Coord pc, Coord2d mc, ClickData inf) {
                Gob g = gobof(inf);
                pressed((g == null) ? null : plan.of(g), mc);
            }

            protected void nohit(Coord pc) {
                pressed(null, null);
            }
        }.run();
    }

    private void pressed(PlanObject o, Coord2d mc) {
        if(mode != Mode.PENDING)
            return;
        if(o != null && !plan.editable(o))
            o = null;   /* locked layer: clicks go through to the ground */
        boolean shift = (pressmods & UI.MOD_SHIFT) != 0;
        if(o != null) {
            if(shift) {
                sel.toggle(o);
                mode = Mode.NONE;
                return;
            }
            if(!sel.contains(o))
                sel.set(Collections.singletonList(o));
            if(released || mc == null) {
                mode = Mode.NONE;
                return;
            }
            anchor = o;
            grabmc = mc;
            starts.clear();
            for(PlanObject s : sel.objects())
                starts.put(s, s.rc);
            plan.begin("Move");
            mode = Mode.MOVE;
        } else {
            if(!shift)
                sel.clear();
            mode = released ? Mode.NONE : Mode.BOX;
            if(released)
                boxselect(pressc, boxc, shift);
        }
    }

    public void drag(Coord c, int mods) {
        if(mode == Mode.BOX || mode == Mode.PENDING) {
            boxc = c;
        } else if(mode == Mode.MOVE) {
            mv.new Maptest(c) {
                protected void hit(Coord pc, Coord2d mc) {
                    if(mode != Mode.MOVE)
                        return;
                    Coord2d from = starts.get(anchor);
                    if(from == null)
                        return;
                    Coord2d to = PlannerView.snap(from.add(mc.sub(grabmc)), mods);
                    Coord2d d = to.sub(from);
                    for(Map.Entry<PlanObject, Coord2d> e : starts.entrySet())
                        plan.move(e.getKey(), e.getValue().add(d), e.getKey().a);
                }
            }.run();
        }
    }

    public void release(Coord c, int mods) {
        switch(mode) {
        case PENDING:
            released = true;
            boxc = c;
            break;
        case MOVE:
            plan.commit();
            mode = Mode.NONE;
            break;
        case BOX:
            boxselect(pressc, c, (pressmods & UI.MOD_SHIFT) != 0);
            mode = Mode.NONE;
            break;
        default:
            mode = Mode.NONE;
        }
    }

    private void boxselect(Coord a, Coord b, boolean add) {
        if(a.dist(b) < 4)
            return;
        Coord ul = Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y)), br = Coord.of(Math.max(a.x, b.x), Math.max(a.y, b.y));
        List<PlanObject> hit = new ArrayList<>(add ? sel.objects() : Collections.emptyList());
        for(PlanObject o : plan.objects()) {
            if(!plan.editable(o))
                continue;
            Coord3f s;
            try {
                Gob g = o.gob();
                s = mv.screenxf((g != null) ? g.getc() : new Coord3f((float)o.rc.x, (float)o.rc.y, 0));
            } catch(Loading l) {
                continue;
            }
            if(s.x >= ul.x && s.x <= br.x && s.y >= ul.y && s.y <= br.y && !hit.contains(o))
                hit.add(o);
        }
        sel.set(hit);
    }

    public void draw(GOut g) {
        if(mode == Mode.BOX && pressc.dist(boxc) >= 4) {
            Coord ul = Coord.of(Math.min(pressc.x, boxc.x), Math.min(pressc.y, boxc.y));
            Coord sz = Coord.of(Math.abs(boxc.x - pressc.x), Math.abs(boxc.y - pressc.y));
            g.chcolor(233, 156, 84, 40);
            g.frect(ul, sz);
            g.chcolor(NStyle.border);
            g.rect(ul, sz);
            g.chcolor();
        }
    }

    /* ---------- keys ---------- */

    public boolean key(Widget.KeyDownEvent ev) {
        boolean ctrl = (ev.mods & KeyMatch.C) != 0, shift = (ev.mods & KeyMatch.S) != 0;
        if(ctrl) {
            switch(ev.code) {
            case KeyEvent.VK_Z:
                if(shift) plan.redo(); else plan.undo();
                return(true);
            case KeyEvent.VK_Y:
                plan.redo();
                return(true);
            case KeyEvent.VK_A:
                sel.set(plan.objects().stream().filter(plan::editable).collect(java.util.stream.Collectors.toList()));
                return(true);
            case KeyEvent.VK_C:
                copy();
                return(true);
            case KeyEvent.VK_V:
                pasteAtCursor();
                return(true);
            case KeyEvent.VK_D:
                if(copy())
                    paste(sel.objects().get(0).rc.add(MCache.tilesz));
                return(true);
            }
            return(false);
        }
        switch(ev.code) {
        case KeyEvent.VK_ESCAPE:
            if(sel.isEmpty())
                return(false);
            sel.clear();
            return(true);
        case KeyEvent.VK_DELETE: case KeyEvent.VK_BACK_SPACE:
            delete();
            return(true);
        case KeyEvent.VK_R:
            turn(shift ? -Math.PI / 2 : Math.PI / 2);
            return(true);
        case KeyEvent.VK_F:
            mirror(shift);
            return(true);
        case KeyEvent.VK_LEFT:  return(nudge(-1, 0, shift));
        case KeyEvent.VK_RIGHT: return(nudge(1, 0, shift));
        case KeyEvent.VK_UP:    return(nudge(0, -1, shift));
        case KeyEvent.VK_DOWN:  return(nudge(0, 1, shift));
        }
        return(false);
    }

    /**
     * Mirrors the selection east-west (or north-south with ns) across the middle of its objects: positions
     * flip and facings turn to match, as the game's preset mirror does; there are no mirrored models, so an
     * object that isn't symmetric ends up turned rather than mirrored.
     */
    public void mirror(boolean ns) {
        List<PlanObject> objs = sel.objects();
        if(objs.isEmpty())
            return;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for(PlanObject o : objs) {
            x0 = Math.min(x0, o.rc.x); x1 = Math.max(x1, o.rc.x);
            y0 = Math.min(y0, o.rc.y); y1 = Math.max(y1, o.rc.y);
        }
        double sx = x0 + x1, sy = y0 + y1;
        plan.begin(ns ? "Mirror north-south" : "Mirror east-west");
        try {
            for(PlanObject o : objs) {
                if(ns)
                    plan.move(o, Coord2d.of(o.rc.x, sy - o.rc.y), Utils.cangle(-o.a));
                else
                    plan.move(o, Coord2d.of(sx - o.rc.x, o.rc.y), Utils.cangle(Math.PI - o.a));
            }
        } finally {
            plan.commit();
        }
    }

    /** Turns the selection a quarter turn, clockwise or back. */
    public void turnsel(boolean back) {
        turn(back ? -Math.PI / 2 : Math.PI / 2);
    }

    public void deletesel() {
        delete();
    }

    public void duplicate() {
        if(copy())
            paste(sel.objects().get(0).rc.add(MCache.tilesz));
    }

    private void delete() {
        List<PlanObject> objs = sel.objects();
        if(objs.isEmpty())
            return;
        plan.begin("Delete");
        try {
            for(PlanObject o : objs)
                plan.remove(o);
        } finally {
            plan.commit();
        }
        sel.clear();
    }

    /** Turns the selection by d radians around the first selected object, keeping angles on the turn's grid. */
    private void turn(double d) {
        List<PlanObject> objs = sel.objects();
        if(objs.isEmpty())
            return;
        Coord2d pivot = objs.get(0).rc;
        double cos = Math.cos(d), sin = Math.sin(d);
        plan.begin("Turn");
        try {
            for(PlanObject o : objs) {
                Coord2d r = o.rc.sub(pivot);
                Coord2d nr = Coord2d.of(r.x * cos - r.y * sin, r.x * sin + r.y * cos);
                plan.move(o, pivot.add(nr), Utils.cangle(o.a + d));
            }
        } finally {
            plan.commit();
        }
    }

    private boolean nudge(int dx, int dy, boolean fine) {
        List<PlanObject> objs = sel.objects();
        if(objs.isEmpty())
            return(false);
        double step = fine ? MCache.tilesz.x / MapView.plobpgran : MCache.tilesz.x;
        plan.begin("Nudge");
        try {
            for(PlanObject o : objs)
                plan.move(o, o.rc.add(dx * step, dy * step), o.a);
        } finally {
            plan.commit();
        }
        return(true);
    }

    private boolean copy() {
        List<PlanObject> objs = sel.objects();
        if(objs.isEmpty())
            return(false);
        clipboard.clear();
        Coord2d base = objs.get(0).rc;
        for(PlanObject o : objs)
            clipboard.add(new Plan.State(o.res, o.sdt, o.rc.sub(base), o.a, null));
        return(true);
    }

    private void pasteAtCursor() {
        if(clipboard.isEmpty())
            return;
        Coord c = mv.ui.mc.sub(mv.rootpos());
        int mods = mv.ui.modflags();
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                paste(PlannerView.snap(mc, mods));
            }
        }.run();
    }

    /** Adds a copy of the clipboard with its first object at base, and selects the copies. */
    private void paste(Coord2d base) {
        List<PlanObject> made = new ArrayList<>();
        plan.begin("Paste");
        try {
            for(Plan.State s : clipboard) {
                PlanObject o = new PlanObject(s.res, s.sdt, base.add(s.rc), s.a);
                plan.add(o);
                made.add(o);
            }
        } finally {
            plan.commit();
        }
        sel.set(made);
    }
}
