package architect.ui;

import architect.PlannerView;
import architect.edit.*;
import architect.io.Blueprints;
import haven.*;
import nurgling.NStyle;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Blueprints window: nurgling's Base planner presets, from the game's own folder. Place one into the plan,
 * save part of the plan as one (a layer or folder, a dragged region, the selection), rename, describe, delete,
 * export and import. Presets the game saves while this is open show up within a few seconds.
 */
public class BlueprintsPanel extends Widget {
    public static final int W = UI.scale(600);
    static final int PAD = UI.scale(8), GAP = UI.scale(5);
    static final Color TXT = new Color(223, 231, 230), DIM = new Color(143, 163, 164), OFF = new Color(95, 105, 106);
    private final PlannerView view;
    private final Plan plan;
    private final Consumer<String> msg;
    private final Path dir;
    private List<Blueprints.Preset> list = new ArrayList<>();
    private long liststamp = Long.MIN_VALUE;
    private double nextcheck = 0;
    private final Listbox<Blueprints.Preset> lb;
    private final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true);
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
    private final Map<String, Tex> tex = new HashMap<>();
    private final ToolButton place, rename, describe, delete, export;
    private final int listy, rowh, thumbx, thumbsz, buttony, prompty;
    /* The name/description prompt: what it is for, and what to do with the text. */
    private final TextEntry entry;
    private final Button ok, cancel;
    private String askwhat = null;
    private Consumer<String> askdone = null;
    private boolean confirmdelete = false;
    /** Work handed back from the Swing file dialogs, run on the UI thread. */
    private final List<Runnable> later = Collections.synchronizedList(new ArrayList<>());

    public BlueprintsPanel(PlannerView view, Consumer<String> msg) {
        super(Coord.of(W, 10));
        this.view = view;
        this.plan = view.plan;
        this.msg = msg;
        this.dir = Blueprints.dir();
        int y = PAD + head.render("Ag").sz().y + small.render("Ag").sz().y + GAP;
        listy = y;
        rowh = Math.max(UI.scale(30), fnd.render("Ag").sz().y + small.render("Ag").sz().y + UI.scale(6));
        int lw = UI.scale(300), rows = 7;
        lb = add(new Listbox<Blueprints.Preset>(lw, rows, rowh) {
            protected Blueprints.Preset listitem(int i) {return(list.get(i));}
            protected int listitems() {return(list.size());}
            protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
            protected void drawitem(GOut g, Blueprints.Preset p, int i) {
                if((i & 1) != 0) {
                    g.chcolor(NStyle.rowOdd);
                    g.frect(Coord.z, g.sz());
                    g.chcolor();
                }
                Tex n = text(p.name, p.foreign ? OFF : TXT, fnd);
                g.image(n, Coord.of(UI.scale(6), UI.scale(2)));
                g.image(text(p.foreign ? "made by a different game version" : describe(p), p.foreign ? OFF : DIM, small),
                        Coord.of(UI.scale(6), UI.scale(2) + n.sz().y));
            }
            public void change(Blueprints.Preset p) {
                super.change(p);
                confirmdelete = false;
            }
        }, PAD, listy);
        thumbx = PAD + lw + PAD;
        thumbsz = Math.min(W - thumbx - PAD, UI.scale(150));
        int bw = (W - PAD * 2 - GAP * 5) / 6;
        buttony = listy + lb.sz.y + GAP;
        int x = PAD;
        place = add(new ToolButton(bw, "Place", null, this::place), x, buttony); x += bw + GAP;
        rename = add(new ToolButton(bw, "Rename", null, this::rename), x, buttony); x += bw + GAP;
        describe = add(new ToolButton(bw, "Describe", null, this::describe), x, buttony); x += bw + GAP;
        delete = add(new ToolButton(bw, "Delete", () -> confirmdelete, this::delete), x, buttony); x += bw + GAP;
        export = add(new ToolButton(bw, "Export…", null, this::export), x, buttony); x += bw + GAP;
        add(new ToolButton(bw, "Import…", null, this::importfile), x, buttony);
        int y2 = buttony + place.sz.y + GAP;
        x = PAD;
        ToolButton b;
        b = add(new ToolButton(bw, "Save layer", null, () -> savenode(false)), x, y2); x += bw + GAP;
        b.settip("Save the active layer as a blueprint");
        b = add(new ToolButton(bw, "Save folder", null, () -> savenode(true)), x, y2); x += bw + GAP;
        b.settip("Save the active layer's folder, all its layers, as a blueprint");
        b = add(new ToolButton(bw, "Save region", null, this::saveregion), x, y2); x += bw + GAP;
        b.settip("Drag a rectangle on the ground: the objects of visible layers inside it");
        b = add(new ToolButton(bw, "Save selection", null, this::saveselection), x, y2); x += bw + GAP;
        b.settip("The selected objects, keeping their layers");
        add(new ToolButton(bw, "Open folder", null, this::openfolder), x, y2); x += bw + GAP;
        add(new ToolButton(bw, "Done", null, this::hide), x, y2);
        prompty = y2 + place.sz.y + GAP;
        int pw = UI.scale(80);
        entry = add(new TextEntry(W - PAD * 2 - pw * 2 - GAP * 2 - UI.scale(90), "") {
            public void activate(String text) {
                answer();
            }
        }, PAD + UI.scale(90), prompty);
        ok = add(new Button(pw, "OK", false, this::answer), W - PAD - pw * 2 - GAP, prompty - UI.scale(2));
        cancel = add(new Button(pw, "Cancel", false, this::unask), W - PAD - pw, prompty - UI.scale(2));
        unask();
        reload(true);
    }

    private Tex text(String s, Color c, Text.Foundry f) {
        if(tex.size() > 600)
            tex.clear();
        return(tex.computeIfAbsent(s + "\u0001" + c.getRGB() + "\u0001" + System.identityHashCode(f), k -> f.render(s, c).tex()));
    }

    private static String describe(Blueprints.Preset p) {
        return(String.format("%d × %d tiles · %d object%s · %s", p.w, p.h, p.count(), (p.count() == 1) ? "" : "s",
                             (p.kind == Blueprints.Kind.FOLDER) ? p.layers.size() + " layers" : "layer"));
    }

    /* ---------- the list ---------- */

    private void reload(boolean force) {
        long st = Blueprints.stamp(dir);
        if(!force && st == liststamp)
            return;
        liststamp = st;
        String sel = (lb.sel != null) ? lb.sel.file : null;
        list = Blueprints.all(dir);
        lb.sel = null;
        for(Blueprints.Preset p : list)
            if(p.file != null && p.file.equals(sel))
                lb.sel = p;
    }

    private void select(String file) {
        reload(true);
        for(Blueprints.Preset p : list)
            if(p.file != null && p.file.equals(file))
                lb.change(p);
    }

    public void show() {
        super.show();
        reload(true);
    }

    public void tick(double dt) {
        while(!later.isEmpty())
            later.remove(0).run();
        if(visible && Utils.rtime() > nextcheck) {
            nextcheck = Utils.rtime() + 2;
            reload(false);
        }
        Blueprints.Preset p = lb.sel;
        boolean usable = (p != null) && !p.foreign;
        place.disable(!usable);
        rename.disable(!usable);
        describe.disable(!usable);
        export.disable(p == null);
        delete.disable(p == null);
        int h = ((askwhat != null) ? prompty + entry.sz.y + PAD : prompty);
        if(sz.y != h)
            resize(Coord.of(W, h));
        super.tick(dt);
    }

    /* ---------- actions ---------- */

    private void place() {
        Blueprints.Preset p = lb.sel;
        if(p == null || p.foreign)
            return;
        view.stampblueprint(p);
        msg.accept("Placing " + p.name + ": wheel or R turns, F mirrors, click places, Esc or right click cancels");
    }

    private void rename() {
        Blueprints.Preset p = lb.sel;
        if(p == null)
            return;
        ask("Rename to", p.name, name -> {
            p.name = name;
            write(p, "Renamed to " + name);
        });
    }

    private void describe() {
        Blueprints.Preset p = lb.sel;
        if(p == null)
            return;
        ask("Description", p.description, text -> {
            p.description = text;
            write(p, "Description saved");
        }, true);
    }

    private void write(Blueprints.Preset p, String done) {
        try {
            Blueprints.write(dir, p);
            msg.accept(done);
        } catch(IOException e) {
            msg.accept("Could not save: " + e.getMessage());
        }
        select(p.file);
    }

    /** Delete takes a second click: the file is the game's too. */
    private void delete() {
        Blueprints.Preset p = lb.sel;
        if(p == null)
            return;
        if(!confirmdelete) {
            confirmdelete = true;
            msg.accept("Click Delete again to delete " + p.name + " (also gone from the game's Presets window)");
            return;
        }
        confirmdelete = false;
        try {
            Blueprints.delete(dir, p);
            msg.accept("Deleted " + p.name);
        } catch(IOException e) {
            msg.accept("Could not delete: " + e.getMessage());
        }
        reload(true);
    }

    private void export() {
        Blueprints.Preset p = lb.sel;
        if(p == null)
            return;
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Export blueprint");
            fc.setFileFilter(new FileNameExtensionFilter("Blueprint (JSON)", "json"));
            fc.setSelectedFile(new java.io.File(Blueprints.slug(p.name) + ".json"));
            if(fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            String f = fc.getSelectedFile().getAbsolutePath();
            if(!f.endsWith(".json"))
                f += ".json";
            String target = f;
            try {
                Blueprints.export(p, Paths.get(target));
                later.add(() -> msg.accept("Exported " + p.name + " to " + target));
            } catch(IOException e) {
                later.add(() -> msg.accept("Export failed: " + e.getMessage()));
            }
        });
    }

    private void importfile() {
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Import a blueprint");
            fc.setFileFilter(new FileNameExtensionFilter("Blueprint (JSON)", "json"));
            if(fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            Path src = fc.getSelectedFile().toPath();
            try {
                Blueprints.Preset p = Blueprints.importFile(dir, src);
                later.add(() -> {
                    msg.accept("Imported " + p.name);
                    select(p.file);
                });
            } catch(IOException e) {
                later.add(() -> msg.accept("Import failed: " + e.getMessage()));
            }
        });
    }

    private void openfolder() {
        try {
            Files.createDirectories(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch(IOException | UnsupportedOperationException e) {
            msg.accept("Blueprints are in " + dir);
        }
    }

    /* ---------- saving from the plan ---------- */

    private interface Capture {
        Blueprints.Preset run(String name) throws BlueprintPlan.Empty;
    }

    /** Asks for a name, captures, saves under a new file and selects it. */
    private void save(String suggest, Capture c) {
        ask("Save as", suggest, name -> {
            try {
                Blueprints.Preset p = c.run(name);
                Blueprints.saveNew(dir, p);
                msg.accept(String.format("Saved %s (%d objects). In the game: Base planner › Presets", p.name, p.count()));
                select(p.file);
            } catch(BlueprintPlan.Empty e) {
                msg.accept(e.getMessage());
            } catch(IOException e) {
                msg.accept("Could not save: " + e.getMessage());
            }
        });
    }

    private void savenode(boolean folder) {
        Layers.Node n = plan.layers.get(plan.layers.active());
        if(n != null && folder)
            n = (n.folder) ? n : plan.layers.get(n.parent);
        if(n == null) {
            msg.accept(folder ? "The active layer is not in a folder" : "No active layer");
            return;
        }
        String id = n.id;
        save(n.name, name -> BlueprintPlan.capturenode(plan, name, id));
    }

    private void saveregion() {
        msg.accept("Drag a rectangle on the ground; the objects of visible layers inside it are saved");
        view.blueprints.armregion((ul, br) -> save("", name -> BlueprintPlan.captureregion(plan, name, ul, br)));
    }

    private void saveselection() {
        List<PlanObject> objs = view.sel.objects();
        if(objs.isEmpty()) {
            msg.accept("Select some objects first");
            return;
        }
        save("", name -> BlueprintPlan.captureobjects(plan, name, objs));
    }

    /** Dev: selects a preset by file name and starts placing it. */
    public void demo(String file) {
        show();
        select(file);
        if(lb.sel != null)
            place();
    }

    /** Ctrl+B in the view: save the selection. */
    public void saveselectionkey() {
        show();
        saveselection();
    }

    /* ---------- the prompt ---------- */

    private boolean multi = false;

    private void ask(String what, String init, Consumer<String> done) {
        ask(what, init, done, false);
    }

    private void ask(String what, String init, Consumer<String> done, boolean allowempty) {
        askwhat = what;
        askdone = done;
        multi = allowempty;
        entry.settext(init);
        entry.show();
        ok.show();
        cancel.show();
        setfocus(entry);
    }

    private void answer() {
        String t = entry.text().trim();
        if(t.isEmpty() && !multi)
            return;
        Consumer<String> d = askdone;
        unask();
        if(d != null)
            d.accept(t);
    }

    private void unask() {
        askwhat = null;
        askdone = null;
        entry.hide();
        ok.hide();
        cancel.hide();
    }

    /* ---------- drawing ---------- */

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.chcolor();
        Tex t = text("Blueprints", NStyle.border, head);
        g.image(t, Coord.of(PAD, PAD));
        g.image(text("Shared with the game: " + dir, DIM, small), Coord.of(PAD, PAD + t.sz().y));
        Blueprints.Preset p = lb.sel;
        if(p != null && !p.foreign)
            thumb(g, p, Coord.of(thumbx, listy));
        int ty = listy + thumbsz + GAP;
        if(p == null) {
            g.image(text(list.isEmpty() ? "No blueprints yet: save one below, or in the game" : "Pick a blueprint", DIM, fnd), Coord.of(thumbx, listy));
        } else {
            g.image(text(p.foreign ? "Made by a different version of the game" : describe(p), TXT, small), Coord.of(thumbx, ty));
            ty += UI.scale(16);
            for(String line : wrap(p.description.isEmpty() ? "No description" : p.description, 44)) {
                if(ty > buttony - UI.scale(14))
                    break;
                g.image(text(line, DIM, small), Coord.of(thumbx, ty));
                ty += UI.scale(13);
            }
        }
        if(askwhat != null)
            g.image(text(askwhat + ":", TXT, fnd), Coord.of(PAD, prompty + UI.scale(2)));
        super.draw(g);
    }

    private static List<String> wrap(String s, int width) {
        List<String> ret = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for(String w : s.split("\\s+")) {
            if(line.length() > 0 && line.length() + w.length() + 1 > width) {
                ret.add(line.toString());
                line.setLength(0);
            }
            if(line.length() > 0)
                line.append(' ');
            line.append(w);
        }
        if(line.length() > 0)
            ret.add(line.toString());
        return(ret);
    }

    /** The objects' footprints from above, scaled into the square. */
    private void thumb(GOut g, Blueprints.Preset p, Coord at) {
        g.chcolor(NStyle.infoBg);
        g.frect(at, Coord.of(thumbsz, thumbsz));
        g.chcolor(70, 84, 86, 255);
        g.rect(at, Coord.of(thumbsz, thumbsz));
        double span = Math.max(p.w, p.h) * Blueprints.TILE, sc = (thumbsz - UI.scale(8)) / span;
        Coord o = at.add(UI.scale(4) + (int)((span - p.w * Blueprints.TILE) * sc / 2), UI.scale(4) + (int)((span - p.h * Blueprints.TILE) * sc / 2));
        g.chcolor(233, 156, 84, 60);
        g.frect(o, Coord.of((int)(p.w * Blueprints.TILE * sc), (int)(p.h * Blueprints.TILE * sc)));
        g.chcolor(143, 200, 190, 220);
        for(Blueprints.Ghost gh : p.ghosts()) {
            double[] b = Blueprints.footprint(gh);
            Coord ul = o.add((int)(b[0] * sc), (int)(b[1] * sc));
            Coord sz = Coord.of(Math.max(1, (int)((b[2] - b[0]) * sc)), Math.max(1, (int)((b[3] - b[1]) * sc)));
            g.frect(ul, sz);
        }
        g.chcolor();
    }
}
