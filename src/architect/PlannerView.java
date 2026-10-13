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
    public final BlueprintTool blueprints;
    /** Short messages for the status bar (set by the runner). */
    public java.util.function.Consumer<String> onmsg = s -> {};
    public static final String PREF_INVERTX = "camera-invert-x", PREF_INVERTY = "camera-invert-y";
    /** Options: reverse the planning cameras' drag (not play mode's). */
    public static boolean invertx = Utils.getprefb(PREF_INVERTX, false), inverty = Utils.getprefb(PREF_INVERTY, false);
    public static final int MINVIEW = 3, MAXVIEW = 16, DEFVIEW = 4;
    /** Grass and flowers (flavour objects) only this many cuts around the camera; bare ground reaches further. */
    public static final int FLAVVIEW = 3;
    /** Resource being placed, or null. */
    private String placingres = null;
    private byte[] placingsdt = new byte[0];
    private boolean placeready = false;
    /* Line/array placing: where the drag began, and the ghosts previewing the row. */
    private Coord linepress = null;
    private Coord2d linestart = null;
    private boolean linemode = false;
    private List<Coord2d> linepos = Collections.emptyList();
    private final List<Gob> preview = new ArrayList<>();
    private final List<String> previewres = new ArrayList<>();
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
        this.blueprints = new BlueprintTool(this);
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
        /** Tilt the camera is heading to (dev checks). */
        public float tilt() {return(telev);}
        public float heading() {return(tangl);}
        public float dist() {return(dist);}

        public void turn(float d) {tangl += d;}

        public boolean click(Coord c) {
            elevorig = telev;
            anglorig = tangl;
            dragorig = c;
            return(true);
        }

        public void drag(Coord c) {
            /* Mouse up tilts toward the horizon, mouse down looks down at the ground. */
            telev = Utils.clip(elevorig + (c.y - dragorig.y) / 150f, 0.08f, (float)Math.PI / 2 - 0.001f);
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
            Walls.Kit kit = Walls.kit(placingres);
            if(kit == null) {
                super.adjust(plob, pc, mc, modflags);
                placeready = true;
                return;
            }
            placeready = true;
            if(linepress != null) {
                /* mid-drag the ghost stays where the drag put it (the gate, or the first post) */
                if(pin != null)
                    plob.move(pin.rc, pin.a);
                return;
            }
            Walls.Piece piece = kit.piece(placingres);
            if(piece.gate()) {
                /* Over a wall, a gate snaps into it: the ghost takes the gate's place and the posts it needs show too. */
                gatehover = Walls.gateinto(plan, kit, piece, mc);
                if(gatehover != null) {
                    PlanObject g = Walls.gateof(kit, gatehover);
                    plob.move(g.rc, g.a);
                    showpreview(without(gatehover.add, g));
                    return;
                }
                clearpreview();
            }
            /* Wall pieces stand on tile centres. */
            plob.move(Walls.centre(Walls.tile(mc)));
        }
    }

    /* Walls: the wall or gate a drag would lay, the gate a click would set into a wall, and where the ghost is held. */
    private Walls.Layout walllayout = null, gatehover = null;
    private PlanObject pin = null;

    private static List<PlanObject> without(List<PlanObject> l, PlanObject x) {
        List<PlanObject> ret = new ArrayList<>(l);
        ret.remove(x);
        return(ret);
    }

    private double warmat = -1;

    /*
     * The engine builds its click buffer on the first hit test and answers that one from an empty buffer, so
     * the first click on a planned object would select nothing. One throwaway test shortly after opening fills it.
     */
    private void warmclicks() {
        if(warmat == 0)
            return;
        if(warmat < 0)
            warmat = Utils.rtime() + 2;
        if(Utils.rtime() < warmat)
            return;
        warmat = 0;
        new Hittest(sz.div(2)) {
            protected void hit(Coord pc, Coord2d mc, ClickData inf) {}
            protected void nohit(Coord pc) {}
        }.run();
    }

    public void toast(String s) {
        onmsg.accept(s);
    }

    /** Starts stamping a blueprint preset; other tools step aside. */
    public void stampblueprint(architect.io.Blueprints.Preset p) {
        cancelplace();
        sel.clear();
        areas.on = false;
        if(terrain != null)
            terrain.on = false;
        play.stop();
        blueprints.stamp(p);
        blueprints.hover(ui.mc.sub(rootpos()));
    }

    /** Ghosts for the blueprint stamp (the same ghosts placing uses). */
    void blueprintpreview(List<PlanObject> objs) {
        if(objs.isEmpty())
            clearpreview();
        else
            showpreview(objs);
    }

    int previewcount() {
        return(preview.size());
    }

    public String placing() {
        return(placingres);
    }

    /** Starts placing res; the ghost follows the cursor until cancelled. Keeps the previous angle. */
    public void place(String res) {
        place(res, null);
    }

    /** As place(res), drawn and planned with state data sdt (which variant of a shared model). */
    public void place(String res, byte[] sdt) {
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
        placingsdt = (sdt == null) ? new byte[0] : sdt;
        placeready = false;
        this.placing = glob.loader.defer(() -> {
            Resource rr = r.get();
            if(Looks.broken(res))
                return(null);
            Plob p;
            try {
                p = createPlob(r, Looks.of(glob, cc, rr, placingsdt));
            } catch(Loading l) {
                throw(l);
            } catch(RuntimeException e) {
                /* its sprite needs server data; see Looks.drawable */
                System.out.println("[architect] cannot place " + res + ": " + e);
                return(null);
            }
            p.adjust = new PlannerPlace();
            p.a = angle;
            p.place();
            return(p);
        });
    }

    public void cancelplace() {
        clearpreview();
        walllayout = gatehover = null;
        pin = null;
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
        plan.add(new PlanObject(placingres, placingsdt, p.rc, p.a));
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
        List<PlanObject> objs = new ArrayList<>();
        for(Coord2d p : pos)
            objs.add(new PlanObject(placingres, placingsdt, p, ang));
        showpreview(objs);
    }

    /** Ghosts for objects about to be placed. */
    private void showpreview(List<PlanObject> objs) {
        Map<String, Resource> res = new HashMap<>();
        try {
            for(PlanObject o : objs)
                if(!res.containsKey(o.res))
                    res.put(o.res, Resource.remote().load(o.res).get());
        } catch(Loading l) {
            return;
        }
        /* a ghost showing a different resource than wanted at its index is replaced */
        for(int i = 0; i < preview.size(); i++) {
            if(i >= objs.size() || !previewres.get(i).equals(objs.get(i).res)) {
                while(preview.size() > i) {
                    glob.oc.remove(preview.remove(preview.size() - 1));
                    previewres.remove(previewres.size() - 1);
                }
                break;
            }
        }
        try {
            while(preview.size() < objs.size()) {
                PlanObject o = objs.get(preview.size());
                /* Unique (virtual) ids: OCache keys objects by id, so a shared id hides all but one. */
                Gob g = glob.oc.new Virtual(o.rc, o.a);
                g.setattr(new nurgling.GhostAlpha(g));
                ResDrawable d = Looks.drawable(g, res.get(o.res), o.sdt);
                if(d == null)
                    break;
                g.setattr(d);
                glob.oc.add(g);
                preview.add(g);
                previewres.add(o.res);
            }
        } catch(Loading l) {
            /* sprite parts still loading; the next update fills the rest in */
        }
        for(int i = 0; i < preview.size(); i++)
            preview.get(i).move(objs.get(i).rc, objs.get(i).a);
    }

    private void clearpreview() {
        for(Gob g : preview)
            glob.oc.remove(g);
        preview.clear();
        previewres.clear();
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
                Walls.Kit kit = Walls.kit(placingres);
                if(kit != null) {
                    walldrag(kit, mc);
                    return;
                }
                linepos = arraypos(linestart, snap(mc, mods), ang, (mods & UI.MOD_META) != 0);
                showpreview(linepos, ang);
            }
        }.run();
    }

    /** A drag while placing a wall piece: a wall run from the first post, or a gate leading off it. */
    private void walldrag(Walls.Kit kit, Coord2d mc) {
        Walls.Piece piece = kit.piece(placingres);
        Coord a = Walls.tile(linestart);
        walllayout = piece.gate() ? Walls.gatefrom(plan, kit, piece, a, mc) : Walls.run(plan, kit, a, mc);
        if(walllayout == null) {
            pin = null;
            clearpreview();
            return;
        }
        PlanObject g = piece.gate() ? Walls.gateof(kit, walllayout) : null;
        pin = (g != null) ? g : new PlanObject(placingres, null, Walls.centre(a), 0);
        Plob p = plob();
        if(p != null)
            p.move(pin.rc, pin.a);
        List<PlanObject> rest = new ArrayList<>();
        for(PlanObject o : walllayout.add)
            if(o != g && !(o.res.equals(placingres) && o.rc.equals(pin.rc)))
                rest.add(o);
        showpreview(rest);
    }

    private void wallend(Walls.Kit kit) {
        Walls.Piece piece = kit.piece(placingres);
        Coord a = Walls.tile(linestart);
        Plob p = plob();
        if(linemode)
            Walls.apply(plan, walllayout, piece.gate() ? "Gate" : "Wall");
        else if(piece.gate())
            Walls.apply(plan, (gatehover != null) ? gatehover : Walls.gatealong(plan, kit, piece, a, (p != null) ? p.a : 0), "Gate");
        else
            Walls.apply(plan, Walls.run(plan, kit, a, Walls.centre(a)), "Wall");
        walllayout = gatehover = null;
        pin = null;
    }

    private void lineend() {
        Plob p = plob();
        Walls.Kit kit = Walls.kit(placingres);
        if(kit != null && p != null && placeready) {
            wallend(kit);
            clearpreview();
            linemode = false;
            linepress = null;
            linestart = null;
            return;
        }
        if(linemode && p != null && placingres != null && !linepos.isEmpty()) {
            plan.begin(linepos.size() > 1 ? "Place " + linepos.size() : "Place");
            try {
                for(Coord2d pos : linepos)
                    plan.add(new PlanObject(placingres, placingsdt, pos, p.a));
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
        blueprints.tick();
        warmclicks();
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
        /* While a camera drag holds the mouse, presses elsewhere (toolbar, panels) still arrive here; outside
         * the view there is nothing to hit, and the engine's hit test dies on an empty area. */
        if(!ev.c.isect(Coord.z, sz))
            return(true);
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
        if(ev.b == 1 && blueprints.stamping()) {
            blueprints.place();
            return(true);
        }
        if(ev.b == 1 && blueprints.regionarmed()) {
            blueprints.regionpress(ev.c);
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

    public boolean mousewheel(MouseWheelEvent ev) {
        if(blueprints.stamping()) {
            blueprints.turn((ev.a > 0) ? 1 : -1);
            return(true);
        }
        return(super.mousewheel(ev));
    }

    public void mousemove(MouseMoveEvent ev) {
        if(drag != null) {
            if(camdrag) {
                Coord c = ev.c;
                if(!play.on) {
                    /* the cameras read the drag relative to where it began; flip that offset per option */
                    Coord d = c.sub(dragstart);
                    c = dragstart.add(invertx ? -d.x : d.x, inverty ? -d.y : d.y);
                }
                camera.drag(c);
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
        if(blueprints.stamping())
            blueprints.hover(ev.c);
        else if(blueprints.regiondragging())
            blueprints.regiondrag(ev.c);
        super.mousemove(ev);
    }

    public boolean mouseup(MouseUpEvent ev) {
        if(ev.b == 1 && linepress != null) {
            lineend();
            return(true);
        }
        if(ev.b == 1 && blueprints.regiondragging()) {
            blueprints.regionrelease();
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
                if(ev.c.dist(dragstart) < 4) {
                    if(placingres != null)
                        cancelplace();
                    else if(blueprints.stamping())
                        blueprints.cancel();
                    else if(blueprints.regionarmed())
                        blueprints.cancelregion();
                }
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
        if(blueprints.key(ev))
            return(true);
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
