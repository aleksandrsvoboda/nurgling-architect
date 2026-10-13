package architect;

import architect.edit.Plan;
import architect.io.AreaPresets;
import architect.io.*;
import architect.res.*;
import architect.ui.*;
import architect.world.*;
import haven.*;
import nurgling.NStyle;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.io.IOException;
import java.util.*;
import java.nio.file.*;

/**
 * One open plan: owns the offline session (in place of RemoteUI), the plan and its file, and builds the
 * planner's widgets. Saves automatically; Close returns to the {@link StartScreen}.
 */
public class PlannerRunner implements UI.Runner {
    public static final double AUTOSAVE = 15;
    public final WorldSource world;
    /** The plan's own editable copy of the ground; what the map server actually serves. */
    public final TerrainStore terrain;
    public final LocalWorldTransport transport;
    public final Session sess;
    public final Plan plan;
    public final Path file;
    public final String name;
    private final PlanFile.Data loaded;
    private volatile String progress = "Indexing objects...";
    private volatile Catalogue catalogue = null;
    private volatile UI.Runner next = null;
    private volatile String toast = null;
    private volatile double toastuntil = 0;
    private int savedseq = -1, savedlseq = -1, savedaseq = -1, savedtseq = -1;
    private PlannerView view;
    private Screen screen;

    public PlannerRunner(WorldSource world, Path file, String name, PlanFile.Data loaded) {
        this(world, file, name, loaded, false);
    }

    /** trees: for a new generated world, plant its forests as objects on their own layer. */
    public PlannerRunner(WorldSource world, Path file, String name, PlanFile.Data loaded, boolean trees) {
        this.world = world;
        this.file = file;
        this.name = name;
        this.loaded = loaded;
        this.terrain = new TerrainStore(world);
        Path tf = terrainfile(file);
        boolean have = false;
        if(Files.exists(tf)) {
            try {
                terrain.read(tf);
                have = true;
            } catch(IOException e) {
                new Warning(e, "could not read " + tf + "; using the map as it is").issue();
            }
        }
        if(!have)
            terrain.snapshot();
        this.transport = new LocalWorldTransport(terrain, new GridEncoder(new GridEncoder.TileRegistry(Resource.remote())));
        this.sess = new Session(transport, new Session.User("Architect"));
        this.plan = new Plan(sess.glob);
        if(loaded != null)
            PlanFile.apply(loaded, plan);
        if(loaded == null && trees && world instanceof architect.gen.WorldGen) {
            architect.edit.Layers.Node l = plan.layers.addLayer("Trees (generated)", null);
            java.util.List<architect.edit.PlanObject> ts = new java.util.ArrayList<>();
            for(Object[] t : ((architect.gen.WorldGen)world).trees())
                ts.add(new architect.edit.PlanObject(java.util.UUID.randomUUID().toString(), (String)t[0], null,
                                                     Coord2d.of((Double)t[1], (Double)t[2]), (Double)t[3], l.id));
            plan.load(ts);
            plan.layers.activate(plan.layers.all().get(0).id);
        }
        transport.start(sess);
        daylight(sess.glob);
    }

    /** Fixed midday light; the server normally sends this in the glob blob. */
    static void daylight(Glob glob) {
        synchronized(glob) {
            glob.lightamb = new Color(150, 150, 160);
            glob.lightdif = new Color(225, 220, 200);
            glob.lightspc = new Color(255, 255, 255);
            glob.lightang = Math.PI * 0.75;
            glob.lightelev = Math.PI * 0.3;
            glob.brighten();
        }
    }

    public void init(UI ui) {
        ui.sess = sess;
    }

    public String title() {
        return("Nurgling Architect – " + name);
    }

    public void msg(String text) {
        toast = text;
        toastuntil = Utils.rtime() + 6;
        System.out.println("[architect] " + text);
    }

    /* ---------- saving ---------- */

    public static Path terrainfile(Path plan) {
        String n = plan.getFileName().toString();
        if(n.endsWith(PlanFile.EXT))
            n = n.substring(0, n.length() - PlanFile.EXT.length());
        return(plan.resolveSibling(n + TerrainStore.EXT));
    }

    private boolean dirty() {
        return(plan.seq != savedseq || plan.layers.seq != savedlseq || plan.areaseq != savedaseq || terrain.seq != savedtseq);
    }

    synchronized boolean save() {
        int s = plan.seq, ls = plan.layers.seq, as = plan.areaseq, ts = terrain.seq;
        try {
            PlanFile.save(file, name, world, plan, (view != null) ? view.cc : world.spawn());
            if(ts != savedtseq)
                terrain.write(terrainfile(file));
            savedtseq = ts;
            savedseq = s;
            savedlseq = ls;
            savedaseq = as;
            return(true);
        } catch(IOException e) {
            msg("Could not save: " + e.getMessage());
            return(false);
        }
    }

    /* ---------- game interchange ---------- */

    void exportareas() {
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser(AreaPresets.userdir().toFile());
            fc.setDialogTitle("Export areas as an area preset");
            fc.setFileFilter(new FileNameExtensionFilter("Area preset (JSON)", "json"));
            fc.setSelectedFile(AreaPresets.userdir().resolve(name + ".json").toFile());
            if(fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            String p = fc.getSelectedFile().getAbsolutePath();
            if(!p.endsWith(".json"))
                p += ".json";
            try {
                int n = AreaPresets.export(Paths.get(p), name, plan);
                msg((n == 0) ? "No visible areas to export" : "Exported " + n + " areas. In game: Areas › Presets › " + name + " › Place.");
            } catch(IOException e) {
                msg("Export failed: " + e.getMessage());
            }
        });
    }

    void close() {
        save();
        next = new StartScreen();
        synchronized(this) {
            notifyAll();
        }
    }

    /* ---------- widgets ---------- */

    /* ---------- modes ---------- */

    void togglePlay() {
        if(view.play.on) {
            view.play.stop();
        } else {
            view.cancelplace();
            view.areas.on = false;
            view.areas.cancelstamp();
            view.terrain.on = false;
            view.sel.clear();
            view.play.start(view.cc);
        }
    }

    void toggleBlueprints() {
        Screen scr = screen;
        if(scr == null)
            return;
        if(scr.blueprints.visible) {
            scr.blueprints.hide();
            view.blueprints.cancel();
            view.blueprints.cancelregion();
        } else {
            scr.blueprints.show();
            scr.blueprints.raise();
        }
    }

    void toggleAreas() {
        if(view.play.on)
            view.play.stop();
        view.areas.on = !view.areas.on;
        if(view.areas.on) {
            view.cancelplace();
            view.terrain.on = false;
        } else {
            view.areas.cancelstamp();
        }
    }

    void toggleTerrain() {
        if(view.play.on)
            view.play.stop();
        view.terrain.on = !view.terrain.on;
        if(view.terrain.on) {
            view.cancelplace();
            view.areas.on = false;
            view.areas.cancelstamp();
        }
    }

    /**
     * Top strip: the plan's name, then its commands in groups - file (save, undo, redo), modes (areas,
     * terrain, play; lit while active), the collision check switch (lit while on) - and Close on the right.
     * Its height follows the buttons, so nothing hangs over the view.
     */
    class Toolbar extends Widget {
        static final int PAD = 5, GAP = 4, GROUP = 18;
        final Text.Foundry fnd = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 14).aa(true);
        final Tex title;
        final List<Integer> seps = new ArrayList<>();
        final ToolButton close;

        Toolbar(int w) {
            super(Coord.of(w, 10));
            title = fnd.render(name, NStyle.border).tex();
            int x = UI.scale(12) + title.sz().x + UI.scale(GROUP), y = UI.scale(PAD);
            Object[][] groups = {
                {new ToolButton("Save", () -> {if(save()) msg("Saved");}),
                 new ToolButton("Undo", () -> plan.undo()),
                 new ToolButton("Redo", () -> plan.redo())},
                {new ToolButton("Areas", () -> view.areas.on, PlannerRunner.this::toggleAreas),
                 new ToolButton("Terrain", () -> view.terrain.on, PlannerRunner.this::toggleTerrain),
                 new ToolButton("Play", () -> view.play.on, PlannerRunner.this::togglePlay),
                 new ToolButton("Blueprints", () -> (screen != null) && screen.blueprints.visible, PlannerRunner.this::toggleBlueprints)},
                {new ToolButton("Collision", () -> view.collisions.enabled, () -> view.collisions.toggle())},
            };
            int h = 0;
            for(int gi = 0; gi < groups.length; gi++) {
                if(gi > 0) {
                    seps.add(x - UI.scale(GROUP) / 2 - UI.scale(GAP) / 2);
                }
                for(Object o : groups[gi]) {
                    ToolButton btn = add((ToolButton)o, x, y);
                    x += btn.sz.x + UI.scale(GAP);
                    h = Math.max(h, btn.sz.y);
                }
                x += UI.scale(GROUP) - UI.scale(GAP);
            }
            close = add(new ToolButton("Close plan", PlannerRunner.this::close), 0, y);
            resize(Coord.of(w, h + UI.scale(PAD) * 2));
        }

        public void resize(Coord sz) {
            super.resize(sz);
            if(close != null)
                close.c = Coord.of(sz.x - close.sz.x - UI.scale(10), close.c.y);
        }

        public void draw(GOut g) {
            g.chcolor(NStyle.titleBg);
            g.frect(Coord.z, sz);
            g.chcolor(NStyle.border);
            g.line(Coord.of(0, sz.y - 1), Coord.of(sz.x, sz.y - 1), 1);
            g.chcolor(70, 84, 86, 255);
            for(int x : seps)
                g.line(Coord.of(x, UI.scale(PAD + 3)), Coord.of(x, sz.y - UI.scale(PAD + 3)), 1);
            g.chcolor();
            g.image(title, Coord.of(UI.scale(12), (sz.y - title.sz().y) / 2));
            super.draw(g);
        }
    }

    /** The whole window: viewport underneath, toolbar on top, catalogue left, layers right, status bottom. */
    class Screen extends Widget {
        final PlannerView view;
        final StatusBar status;
        final Toolbar toolbar;
        final LayersPanel layers;
        CataloguePanel panel = null;
        final AreaPanel areapanel;
        final Speedget speeds;
        final TerrainPanel terrainpanel;
        final WallBar wallbar;
        final BlueprintsPanel blueprints;
        final RenderAudit audit;

        Screen(Coord sz) {
            super(sz);
            Coord2d start = (loaded != null && loaded.camera != null) ? loaded.camera : world.spawn();
            view = add(new PlannerView(sz, sess.glob, start, world.bounds(), plan), Coord.z);
            toolbar = add(new Toolbar(sz.x), Coord.z);
            status = add(new StatusBar(sz.x, this::lefttext, this::righttext), 0, sz.y - StatusBar.H);
            layers = add(new LayersPanel(plan, view.sel, sideh()), sz.x - LayersPanel.W, toolbar.sz.y);
            areapanel = add(new AreaPanel(view.areas, PlannerRunner.this::exportareas), (sz.x - AreaPanel.W) / 2, toolbar.sz.y + UI.scale(8));
            areapanel.hide();
            /* The game's own speed switcher (crawl/walk/run/sprint), driving play mode instead of the server. */
            speeds = add(new Speedget(view.play.speed, 3) {
                public void set(int s) {
                    view.play.speed = s;
                }

                public void tick(double dt) {
                    cur = view.play.speed;
                    super.tick(dt);
                }
            });
            speeds.hide();
            view.terrain = new TerrainTool(view, terrain, transport);
            terrainpanel = add(new TerrainPanel(view.terrain, terrain, () -> catalogue), (sz.x - TerrainPanel.W) / 2, toolbar.sz.y + UI.scale(8));
            terrainpanel.hide();
            wallbar = add(new WallBar(view::placing, r -> {
                view.place(r);
                setfocus(view);
            }), 0, 0);
            wallbar.hide();
            audit = Boolean.getBoolean("architect.renderaudit") ? new RenderAudit(view) : null;
            view.onmsg = PlannerRunner.this::msg;
            view.blueprints.placed = (p, n) -> msg(String.format("Placed %s (%d objects); Ctrl+Z takes it back", p.name, n));
            blueprints = add(new BlueprintsPanel(view, PlannerRunner.this::msg), 0, 0);
            blueprints.hide();
        }

        int sideh() {
            return(sz.y - toolbar.sz.y - StatusBar.H);
        }

        String lefttext() {
            if(toast != null && Utils.rtime() < toastuntil)
                return(toast);
            String p = view.placing();
            String s = world.describe() + "   ·   " + plan.size() + " planned";
            if(!view.sel.isEmpty())
                s += "   ·   " + view.sel.size() + " selected";
            if(view.collisions.count() > 0)
                s += "   ·   " + view.collisions.count() + " overlapping";
            if(p != null)
                s += "   ·   placing " + ((catalogue != null) ? catalogue.nameof(p) : p);
            if(dirty())
                s += "   ·   unsaved";
            return(s);
        }

        String righttext() {
            if(catalogue == null)
                return(progress);
            if(view.play.on)
                return("Play mode   Click: walk there   WASD: move   1-4: crawl/walk/run/sprint   Shift: walk   Right-drag: turn camera   Wheel: zoom   Esc or Play: back");
            if(view.blueprints.stamping())
                return("Placing blueprint " + view.blueprints.stampname() + "   Wheel / R: turn   F: mirror   Click: place   Esc / right click: cancel");
            if(view.blueprints.regionarmed())
                return("Save region: drag a rectangle on the ground   Esc / right click: cancel");
            if(architect.edit.Walls.kit(view.placing()) != null)
                return("Drag from a post: wall (8 directions)   Click: post / gate   Crossing a wall joins it   Ctrl/Shift+wheel, R: turn a lone gate   Esc / right click: stop");
            if(view.placing() != null)
                return("Click: place   R: turn 90°   Ctrl/Shift+wheel: 45°/15°   Shift: fine grid   Ctrl: snap to neighbours   Esc / right click: stop");
            if(!view.sel.isEmpty())
                return("Drag: move   R: turn   Del: delete   Arrows: nudge   Ctrl+C/V/D: copy/paste/duplicate   Esc: deselect");
            return("Right-drag: orbit   Middle-drag: pan   Wheel: zoom   WASD, Q/E   Tab: top-down   Click/drag: select");
        }

        void layout() {
            view.resize(sz);
            toolbar.resize(Coord.of(sz.x, toolbar.sz.y));
            status.c = Coord.of(0, sz.y - StatusBar.H);
            status.resize(Coord.of(sz.x, StatusBar.H));
            layers.c = Coord.of(sz.x - LayersPanel.W, toolbar.sz.y);
            speeds.c = Coord.of(sz.x - speeds.sz.x - UI.scale(12), toolbar.sz.y + UI.scale(10));
            layers.resize(Coord.of(LayersPanel.W, sideh()));
            if(panel != null)
                panel.resize(Coord.of(CataloguePanel.W, sideh()));
        }

        private double lastsave = Utils.rtime();
        private double bpdemo = 0;
        private double lastfpslog = 0;
        /* Dev: -Darchitect.autoplay=1 enters play mode a few seconds in and walks east (for screenshots). */
        private final double autoplay = Boolean.getBoolean("architect.autoplay") ? Utils.rtime() + 4 : -1;
        private boolean autoplayed = false;

        public void tick(double dt) {
            if(parent != null && !sz.equals(parent.sz)) {
                resize(parent.sz);
                layout();
            }
            if(areapanel.visible != view.areas.on) {
                areapanel.show(view.areas.on);
                areapanel.c = Coord.of((sz.x - AreaPanel.W) / 2, toolbar.sz.y + UI.scale(8));
            }
            /* Play mode is just you and the world: the planning panels step aside. */
            boolean planning = !view.play.on;
            if(speeds.visible == planning) {
                speeds.show(!planning);
                speeds.c = Coord.of(sz.x - speeds.sz.x - UI.scale(12), toolbar.sz.y + UI.scale(10));
            }
            if(!planning && blueprints.visible)
                blueprints.hide();
            blueprints.c = Coord.of(Math.max(0, sz.x - LayersPanel.W - blueprints.sz.x - UI.scale(8)), toolbar.sz.y + UI.scale(8));
            if(layers.visible != planning) {
                layers.show(planning);
                if(panel != null)
                    panel.show(planning);
            }
            if(terrainpanel.visible != view.terrain.on) {
                terrainpanel.show(view.terrain.on);
                terrainpanel.c = Coord.of((sz.x - TerrainPanel.W) / 2, toolbar.sz.y + UI.scale(8));
            }
            boolean walls = architect.edit.Walls.kit(view.placing()) != null;
            if(wallbar.visible != walls) {
                wallbar.show(walls);
                wallbar.c = Coord.of((sz.x - wallbar.sz.x) / 2, toolbar.sz.y + UI.scale(8));
            }
            Catalogue cat = catalogue;
            if(audit != null)
                audit.tick(cat);
            /* Dev: -Darchitect.bpdemo=File.json stamps that blueprint at the view centre a few seconds in, places
             * it after a few more (for screenshots without touching the mouse). */
            String demo = System.getProperty("architect.bpdemo");
            if(demo != null && cat != null) {
                if(bpdemo == 0)
                    bpdemo = Utils.rtime() + 6;
                else if(bpdemo > 0 && Utils.rtime() > bpdemo) {
                    blueprints.demo(demo);
                    view.blueprints.at(view.cc.floor(MCache.tilesz));
                    bpdemo = -(Utils.rtime() + 8);
                } else if(bpdemo < 0 && Utils.rtime() > -bpdemo && view.blueprints.stamping()) {
                    view.blueprints.place();
                }
            }
            if(panel == null && cat != null) {
                panel = add(new CataloguePanel(cat, sideh(), e -> {
                    view.place(e.res, e.sdt);
                    setfocus(view);
                }), 0, toolbar.sz.y);
            }
            if(autoplayed && autoplay > 0 && Boolean.getBoolean("architect.autostop") && Utils.rtime() > autoplay + 5 && view.play.on) {
                System.out.println("[play] autostop");
                view.play.stop();
                System.out.println("[play] stopped");
            }
            /* Dev: -Darchitect.fpslog=1 prints the frame rate every second; nopanels / ortho isolate their cost. */
            if(Boolean.getBoolean("architect.fpslog") && Utils.rtime() - lastfpslog > 1) {
                lastfpslog = Utils.rtime();
                System.out.println("[fps] " + nurgling.NUtils.getFps());
                if(Boolean.getBoolean("architect.nopanels") && layers.visible) {
                    layers.hide();
                    if(panel != null) panel.hide();
                    status.hide();
                    toolbar.hide();
                }
                if(Boolean.getBoolean("architect.ortho") && !(view.camera instanceof MapView.SOrthoCam))
                    view.camera = view.new SOrthoCam();
            }
            if(autoplay > 0 && !autoplayed && Utils.rtime() > autoplay) {
                autoplayed = true;
                view.play.start(view.cc);
                view.play.walkto(view.cc.add(400, 60));
            }
            if(Utils.rtime() - lastsave > AUTOSAVE) {
                lastsave = Utils.rtime();
                if(dirty())
                    save();
            }
            super.tick(dt);
        }

        public boolean keydown(KeyDownEvent ev) {
            if(ev.code == java.awt.event.KeyEvent.VK_B && (ev.mods & KeyMatch.C) != 0 && !view.play.on) {
                blueprints.saveselectionkey();
                return(true);
            }
            if(ev.code == java.awt.event.KeyEvent.VK_S && (ev.mods & KeyMatch.C) != 0) {
                if(save())
                    msg("Saved");
                return(true);
            }
            return(super.keydown(ev));
        }
    }

    public UI.Runner run(UI ui) throws InterruptedException {
        synchronized(ui) {
            ui.core.mode = nurgling.NCore.Mode.IDLE;
            screen = ui.root.add(new Screen(ui.root.sz), Coord.z);
            screen.setfocus(screen.view);
            ui.root.setfocus(screen);
            view = screen.view;
        }
        PlannerView v = screen.view;
        transport.focus(() -> v.cc);
        transport.range(v::gridrange);
        if(loaded == null)
            save();   /* a new plan exists on disk from the start, so it shows up under "Your plans" */
        else
            savedseq = plan.seq;
        savedlseq = plan.layers.seq;
        savedaseq = plan.areaseq;
        if(loaded != null && Files.exists(terrainfile(file)))
            savedtseq = terrain.seq;
        try {
            catalogue = Catalogue.shipped();
        } catch(IOException e) {
            progress = "Could not read the object catalogue: " + e.getMessage();
            new Warning(e, "catalogue missing").issue();
        }
        try {
            synchronized(this) {
                while(next == null)
                    wait();
            }
            return(next);
        } finally {
            /* Window closed (interrupt) or plan closed: never lose work. */
            if(dirty())
                save();
            transport.close();
        }
    }
}
