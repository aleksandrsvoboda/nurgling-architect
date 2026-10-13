package architect;

import architect.edit.*;
import haven.*;
import haven.render.Projection;

import java.awt.event.KeyEvent;
import java.util.*;

/**
 * The 3D viewport: a plain haven.MapView (not NMapView, which assumes a GameUI everywhere) with no player.
 * The camera looks at {@link #cc}.
 *
 * Camera: right-drag orbits/tilts, middle-drag pans, wheel zooms, WASD pans (Shift = faster),
 * Q/E turn, Tab switches between the orbit camera and the game's top-down ortho camera.
 *
 * Placing uses the game's own placement ghost (Plob) and snapping: tile centre, Shift = 1/8-tile grid,
 * Ctrl = flush against neighbouring objects; Ctrl/Shift+wheel turn 45/15 degrees, R turns 90. A left click
 * adds the object to the plan and keeps placing; Esc or a right click (without dragging) stops.
 * Dragging while placing lays a row spaced by the object's footprint; Alt+drag fills a rectangle.
 *
 * Editing (when not placing) is handled by {@link EditTool}.
 */
public class PlannerView extends MapView {
    public static final double PAN_SPEED = 0.9;   /* fraction of the camera distance per second, x3 with Shift */
    private final Set<Integer> held = new HashSet<>();
    private boolean fast = false;
    private UI.Grab drag = null;
    private int dragb;
    /** Whether the current drag turns the camera (right drag; in play mode middle drag too) rather than panning. */
    private boolean camdrag;
    private Coord dragc, dragstart;
    public final Plan plan;
    public final Selection sel;
    public final EditTool edit;
    public final Collisions collisions;
    public final AreaTool areas;
    /** Set by the runner once the terrain store exists. */
    public TerrainTool terrain = null;
    public final PlayMode play;
    public static final int MINVIEW = 3, MAXVIEW = 16, DEFVIEW = 5;
    /** Grass and flowers (flavour objects) only this many cuts around the camera; bare ground reaches further. */
    public static final int FLAVVIEW = 3;
    /** Resource being placed, or null. */
    private String placingres = null;
    private boolean placeready = false;
    /* Line/array placing: where the drag began, and the ghosts previewing the row. */
    private Coord linepress = null;
    private Coord2d linestart = null;
    private boolean linemode = false;
    private List<Coord2d> linepos = Collections.emptyList();
    private final List<Gob> preview = new ArrayList<>();
    public static final int MAXARRAY = 2000;

    /** World-unit rectangle the camera focus may move in; null = unbounded. */
    private final Coord2d boundul, boundbr;

    public PlannerView(Coord sz, Glob glob, Coord2d cc, Area gridbounds, Plan plan) {
        super(sz, glob, cc, -1);
        this.plan = plan;
        this.sel = new Selection(plan);
        this.edit = new EditTool(this);
        this.collisions = new Collisions(plan);
        this.areas = new AreaTool(this);
        this.play = new PlayMode(this);
        if(gridbounds != null) {
            Coord2d gsz = MCache.tilesz.mul(Coord2d.of(MCache.cmaps));
            boundul = gsz.mul(Coord2d.of(gridbounds.ul));
            boundbr = gsz.mul(Coord2d.of(gridbounds.br)).sub(1, 1);
        } else {
            boundul = boundbr = null;
        }
        camera = new OrbitCam();
        setview(Integer.getInteger("architect.view", DEFVIEW));
        super.terrain.flavview = Integer.getInteger("architect.flavview", FLAVVIEW);
    }

    /** Grids the map server must keep around the viewer for the current view distance. */
    public int gridrange() {
        return((int)Math.ceil((view + 1) * MCache.cutsz.x / (double)MCache.cmaps.x) + 1);
    }

    /* ---------- camera ---------- */

    /** Orbit camera for planning: free yaw, tilt from near-horizontal to straight down, wide zoom range. */
    public class OrbitCam extends Camera {
        public static final float MINDIST = 20, MAXDIST = 6000;
        private float dist = Float.parseFloat(System.getProperty("architect.camdist", "450")), tdist = dist;
        private float elev = 0.95f, telev = elev;
        private float angl = (float)(Math.PI * 0.75), tangl = angl;
        private Coord dragorig = null;
        private float elevorig, anglorig;
        private Coord3f fc = null;

        public void tick(double dt) {
            float cf = 1f - (float)Math.pow(500, -dt * 3);
            angl += (tangl - angl) * cf;
            elev += (telev - elev) * cf;
            dist += (tdist - dist) * cf;
            Coord3f mc = getcc().invy();
            fc = (fc == null || Math.hypot(mc.x - fc.x, mc.y - fc.y) > 2000) ? mc : fc.add(mc.sub(fc).mul(cf));
            view = haven.render.Camera.pointed(fc.add(0, 0, 5), dist, elev, angl);
        }

        public void resized() {
            /* Far plane well past a whole 10x10-grid region seen from MAXDIST. */
            float field = 0.5f, aspect = ((float)sz.y) / ((float)sz.x);
            proj = Projection.frustum(-field, field, -aspect * field, aspect * field, 1, 60000);
        }

        public float angle() {return(angl);}
        public float dist() {return(dist);}

        public void turn(float d) {tangl += d;}

        public boolean click(Coord c) {
            elevorig = telev;
            anglorig = tangl;
            dragorig = c;
            return(true);
        }

        public void drag(Coord c) {
            telev = Utils.clip(elevorig - (c.y - dragorig.y) / 150f, 0.08f, (float)Math.PI / 2 - 0.001f);
            tangl = anglorig + (c.x - dragorig.x) / 150f;
        }

        public boolean wheel(MouseWheelEvent ev) {
            tdist = Utils.clip(tdist * (float)Math.pow(1.15, ev.s), MINDIST, MAXDIST);
            return(true);
        }
    }

    /** World units per screen pixel at the focus, roughly; drives pan speed. */
    private double scale() {
        double d = (camera instanceof OrbitCam) ? ((OrbitCam)camera).dist() : 500;
        return(d / Math.max(sz.x, 1));
    }

    /* Ground-plane forward/right for the camera's yaw. The orbit eye sits at angle a around the focus
     * (y inverted in camera space), so it looks along (-cos a, sin a) in world coordinates. */
    private Coord2d forward() {
        double a = camera.angle();
        return(Coord2d.of(-Math.cos(a), Math.sin(a)));
    }

    /** Whether a world position is inside the plannable world. */
    public boolean inbounds(Coord2d p) {
        return(boundul == null || (p.x >= boundul.x && p.y >= boundul.y && p.x <= boundbr.x && p.y <= boundbr.y));
    }

    private void move(double fwd, double right) {
        Coord2d f = forward(), r = Coord2d.of(-f.y, f.x);
        Coord2d nc = cc.add(f.mul(fwd)).add(r.mul(right));
        if(boundul != null)
            nc = Coord2d.of(Utils.clip(nc.x, boundul.x, boundbr.x), Utils.clip(nc.y, boundul.y, boundbr.y));
        cc = nc;
    }

    /* ---------- placing ---------- */

    /** Game snapping, minus the player-facing auto-rotation (there is no player). */
    private class PlannerPlace extends nurgling.NStdPlace {
        public void adjust(Plob plob, Coord pc, Coord2d mc, int modflags) {
            super.adjust(plob, pc, mc, modflags);
            placeready = true;
        }
    }

    public String placing() {
        return(placingres);
    }

    /** Starts placing res; the ghost follows the cursor until cancelled. Keeps the previous angle. */
    public void place(String res) {
        double a = 0;
        Plob cur = plob();
        if(cur != null)
            a = cur.a;
        cancelplace();
        sel.clear();
        areas.on = false;
        if(terrain != null)
            terrain.on = false;
        play.stop();
        double angle = a;
        Indir<Resource> r = Resource.remote().load(res);
        placingres = res;
        placeready = false;
        this.placing = glob.loader.defer(() -> {
            Plob p = createPlob(r, Message.nil);
            p.adjust = new PlannerPlace();
            p.a = angle;
            p.place();
            return(p);
        });
    }

    public void cancelplace() {
        clearpreview();
        linepress = null;
        linemode = false;
        if(this.placing != null)
            uimsg("unplace");
        placingres = null;
    }

    private Plob plob() {
        Loader.Future<Plob> cur = this.placing;
        return((cur != null && cur.done()) ? cur.get() : null);
    }

    private void commitplace() {
        Plob p = plob();
        if(p == null || !placeready || placingres == null)
            return;
        plan.add(new PlanObject(placingres, null, p.rc, p.a));
    }

    /** Positions for a row from a to b (or a filled rectangle), spaced so footprints touch. */
    private List<Coord2d> arraypos(Coord2d a, Coord2d b, double ang, boolean rect) {
        Footprint fp = Footprint.of(placingres);
        List<Coord2d> ret = new ArrayList<>();
        Coord2d d = b.sub(a);
        if(fp == null || Math.hypot(d.x, d.y) < 1) {
            ret.add(a);
            return(ret);
        }
        if(rect) {
            double sx = Math.max(fp.extent(ang, Coord2d.of(1, 0)), 1), sy = Math.max(fp.extent(ang, Coord2d.of(0, 1)), 1);
            int nx = (int)Math.floor(Math.abs(d.x) / sx) + 1, ny = (int)Math.floor(Math.abs(d.y) / sy) + 1;
            double fx = Math.signum(d.x) * sx, fy = Math.signum(d.y) * sy;
            for(int y = 0; y < ny && ret.size() < MAXARRAY; y++)
                for(int x = 0; x < nx && ret.size() < MAXARRAY; x++)
                    ret.add(a.add(fx * x, fy * y));
        } else {
            double len = Math.hypot(d.x, d.y), step = Math.max(fp.extent(ang, d), 1);
            int n = Math.min((int)Math.floor(len / step) + 1, MAXARRAY);
            Coord2d u = d.div(len);
            for(int i = 0; i < n; i++)
                ret.add(a.add(u.mul(step * i)));
        }
        return(ret);
    }

    private void showpreview(List<Coord2d> pos, double ang) {
        Resource res;
        try {
            res = Resource.remote().load(placingres).get();
        } catch(Loading l) {
            return;
        }
        while(preview.size() > pos.size())
            glob.oc.remove(preview.remove(preview.size() - 1));
        try {
            while(preview.size() < pos.size()) {
                /* Unique (virtual) ids: OCache keys objects by id, so a shared id hides all but one. */
                Gob g = glob.oc.new Virtual(pos.get(preview.size()), 0);
                g.setattr(new nurgling.GhostAlpha(g));
                g.setattr(new ResDrawable(g, res));
                glob.oc.add(g);
                preview.add(g);
            }
        } catch(Loading l) {
            /* sprite parts still loading; the next drag update fills the row in */
        }
        for(int i = 0; i < preview.size(); i++)
            preview.get(i).move(pos.get(i), ang);
    }

    private void clearpreview() {
        for(Gob g : preview)
            glob.oc.remove(g);
        preview.clear();
    }

    private void linedrag(Coord c, int mods) {
        Plob p = plob();
        if(p == null || linestart == null)
            return;
        if(!linemode && c.dist(linepress) < 6)
            return;
        linemode = true;
        double ang = p.a;
        new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(!linemode || placingres == null)
                    return;
                linepos = arraypos(linestart, snap(mc, mods), ang, (mods & UI.MOD_META) != 0);
                showpreview(linepos, ang);
            }
        }.run();
    }

    private void lineend() {
        Plob p = plob();
        if(linemode && p != null && placingres != null && !linepos.isEmpty()) {
            plan.begin(linepos.size() > 1 ? "Place " + linepos.size() : "Place");
            try {
                for(Coord2d pos : linepos)
                    plan.add(new PlanObject(placingres, null, pos, p.a));
            } finally {
                plan.commit();
            }
        } else if(!linemode) {
            commitplace();
        }
        clearpreview();
        linemode = false;
        linepress = null;
        linestart = null;
        linepos = Collections.emptyList();
    }

    private void turnplob(double d) {
        Plob p = plob();
        if(p != null)
            p.move(Utils.cangle(Math.round((p.a + d) / (Math.PI / 2)) * (Math.PI / 2)));
    }

    /* ---------- snapping shared with the edit tool ---------- */

    /** Same grid as placing: tile centre, Shift = 1/plobpgran tile, Ctrl = free. */
    public static Coord2d snap(Coord2d mc, int modflags) {
        if((modflags & UI.MOD_CTRL) != 0)
            return(mc);
        if((modflags & UI.MOD_SHIFT) == 0)
            return(mc.floor(MCache.tilesz).mul(MCache.tilesz).add(MCache.tilesz.div(2)));
        if(plobpgran > 0)
            return(mc.div(MCache.tilesz).mul(plobpgran).roundf().div(plobpgran).mul(MCache.tilesz));
        return(mc);
    }

    /* ---------- MapView hooks ---------- */

    /* The server would be told the map finished loading; there is no server. */
    protected void initload(double time) {}

    public void tick(double dt) {
        if(play.on)
            play.tick(dt);
        else
            keypan(dt);
        plan.tick();
        if(terrain != null)
            terrain.tick();
        collisions.tick();
        sel.tick();
        super.tick(dt);
    }

    public void draw(GOut g) {
        super.draw(g);
        areas.draw(g);
        if(terrain != null)
            terrain.draw(g);
        edit.draw(g);
    }

    private void keypan(double dt) {
        double f = 0, r = 0;
        if(held.contains(KeyEvent.VK_W)) f += 1;
        if(held.contains(KeyEvent.VK_S)) f -= 1;
        if(held.contains(KeyEvent.VK_D)) r += 1;
        if(held.contains(KeyEvent.VK_A)) r -= 1;
        if(camera instanceof OrbitCam) {
            if(held.contains(KeyEvent.VK_Q)) ((OrbitCam)camera).turn((float)(-1.8 * dt));
            if(held.contains(KeyEvent.VK_E)) ((OrbitCam)camera).turn((float)(1.8 * dt));
        }
        if(f == 0 && r == 0)
            return;
        double sp = PAN_SPEED * scale() * sz.x * (fast ? 3 : 1) * dt / Math.hypot(f, r);
        move(f * sp, r * sp);
    }

    public boolean mousedown(MouseDownEvent ev) {
        parent.setfocus(this);
        if(play.on) {
            if(ev.b == 1) {
                new Maptest(ev.c) {
                    protected void hit(Coord pc, Coord2d mc) {
                        play.walkto(mc);
                    }
                }.run();
                return(true);
            }
            /* Right or middle drag turns the game camera, as in the game. */
            if(drag == null) {
                dragb = ev.b;
                camdrag = true;
                dragc = dragstart = ev.c;
                camera.click(ev.c);
                drag = ui.grabmouse(this);
            }
            return(true);
        }
        if(ev.b == 1) {
            if(plob() != null) {
                if(placeready) {
                    linepress = ev.c;
                    linestart = plob().rc;
                    linemode = false;
                }
            } else if(terrain != null && terrain.on)
                terrain.press(ev.c, ui.modflags());
            else if(areas.on)
                areas.press(ev.c);
            else
                edit.press(ev.c, ui.modflags());
            return(true);
        }
        if((ev.b == 2 || ev.b == 3) && drag == null) {
            dragb = ev.b;
            camdrag = (ev.b == 3);
            dragc = dragstart = ev.c;
            if(ev.b == 3)
                camera.click(ev.c);
            drag = ui.grabmouse(this);
            return(true);
        }
        return(true);
    }

    public void mousemove(MouseMoveEvent ev) {
        if(drag != null) {
            if(camdrag) {
                camera.drag(ev.c);
            } else {
                Coord d = ev.c.sub(dragc);
                dragc = ev.c;
                /* Grab-and-drag: the ground follows the cursor. Vertical motion is stretched by the tilt. */
                move(d.y * scale() * 1.4, -d.x * scale());
            }
            return;
        }
        if(edit.dragging())
            edit.drag(ev.c, ui.modflags());
        if(terrain != null && terrain.on)
            terrain.move(ev.c, ui.modflags());
        if(areas.dragging())
            areas.drag(ev.c);
        else if(areas.stamping())
            areas.hover(ev.c);
        if(linepress != null)
            linedrag(ev.c, ui.modflags());
        super.mousemove(ev);
    }

    public boolean mouseup(MouseUpEvent ev) {
        if(ev.b == 1 && linepress != null) {
            lineend();
            return(true);
        }
        if(ev.b == 1 && terrain != null && terrain.dragging()) {
            terrain.release();
            return(true);
        }
        if(ev.b == 1 && areas.dragging()) {
            areas.release();
            return(true);
        }
        if(ev.b == 1 && edit.dragging()) {
            edit.release(ev.c, ui.modflags());
            return(true);
        }
        if(drag != null && ev.b == dragb) {
            if(camdrag) {
                camera.release();
                /* A right click that did not orbit stops placing. */
                if(placingres != null && ev.c.dist(dragstart) < 4)
                    cancelplace();
            }
            drag.remove();
            drag = null;
            return(true);
        }
        return(true);
    }

    public boolean keydown(KeyDownEvent ev) {
        if(play.on) {
            if(play.keydown(ev))
                return(true);
            return(super.keydown(ev));
        }
        fast = (ev.mods & KeyMatch.S) != 0;
        boolean ctrl = (ev.mods & KeyMatch.C) != 0;
        if(!ctrl) {
            switch(ev.code) {
            case KeyEvent.VK_W: case KeyEvent.VK_A: case KeyEvent.VK_S: case KeyEvent.VK_D:
            case KeyEvent.VK_Q: case KeyEvent.VK_E:
                held.add(ev.code);
                return(true);
            case KeyEvent.VK_PAGE_UP:
                setview(Math.min(MAXVIEW, view + 1));
                return(true);
            case KeyEvent.VK_PAGE_DOWN:
                setview(Math.max(MINVIEW, view - 1));
                return(true);
            case KeyEvent.VK_TAB:
                camera = (camera instanceof OrbitCam) ? new SOrthoCam() : new OrbitCam();
                return(true);
            }
        }
        if(placingres != null) {
            if(ev.code == KeyEvent.VK_ESCAPE) {
                cancelplace();
                return(true);
            }
            if(ev.code == KeyEvent.VK_R && !ctrl) {
                turnplob(((ev.mods & KeyMatch.S) != 0) ? -Math.PI / 2 : Math.PI / 2);
                return(true);
            }
        } else if(terrain != null && terrain.on && terrain.key(ev)) {
            return(true);
        } else if(areas.on && areas.key(ev)) {
            return(true);
        } else if(edit.key(ev)) {
            return(true);
        }
        return(super.keydown(ev));
    }

    public boolean keyup(KeyUpEvent ev) {
        if(play.on && play.keyup(ev))
            return(true);
        fast = (ev.mods & KeyMatch.S) != 0;
        if(held.remove(ev.code))
            return(true);
        return(super.keyup(ev));
    }

    /* Keys held when focus leaves would otherwise keep panning forever. */
    public void lostfocus() {
        held.clear();
        play.lostfocus();
        super.lostfocus();
    }
}
