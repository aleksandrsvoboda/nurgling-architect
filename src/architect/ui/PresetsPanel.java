package architect.ui;

import architect.PlannerView;
import architect.edit.Plan;
import architect.io.PresetFiles;
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
 * A window over one of the game's preset folders (blueprints, area presets): the list with a thumbnail and
 * description, Place / Rename / Describe / Delete (second click confirms) / Export / Import, a row of save
 * actions the kind provides, and a name prompt. Presets the game saves while it is open show up within a
 * few seconds. Built-in presets (shipped with the game) are listed but can't be renamed or deleted.
 */
public abstract class PresetsPanel<P> extends Widget {
    public static final int W = UI.scale(600);
    static final int PAD = UI.scale(8), GAP = UI.scale(5);
    static final Color TXT = new Color(223, 231, 230), DIM = new Color(143, 163, 164), OFF = new Color(95, 105, 106);
    protected final PlannerView view;
    protected final Plan plan;
    protected final Consumer<String> msg;
    protected final Path dir;
    private final String title, kind;
    private List<P> list = new ArrayList<>();
    private long liststamp = Long.MIN_VALUE;
    private double nextcheck = 0;
    private final Listbox<P> lb;
    private final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true);
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
    private final Map<String, Tex> tex = new HashMap<>();
    private final ToolButton place, rename, describe, delete, export;
    private final int listy, rowh, thumbx, buttony, prompty;
    protected final int thumbsz;
    private final TextEntry entry;
    private final Button ok, cancel;
    private String askwhat = null;
    private Consumer<String> askdone = null;
    private boolean askempty = false;
    private boolean confirmdelete = false;
    /** Work handed back from the Swing file dialogs, run on the UI thread. */
    protected final List<Runnable> later = Collections.synchronizedList(new ArrayList<>());

    /* ---------- what a kind of preset provides ---------- */

    /** Every preset to list, in order. */
    protected abstract List<P> load();
    protected abstract String name(P p);
    protected abstract void name(P p, String name);
    protected abstract String description(P p);
    protected abstract void description(P p, String text);
    /** One line under the name: size, contents, category. */
    protected abstract String summary(P p);
    /** Readable by this version (not a newer format). */
    protected abstract boolean usable(P p);
    /** Yours: renamable and deletable (not built in). */
    protected abstract boolean mine(P p);
    protected abstract String file(P p);
    protected abstract void write(P p) throws IOException;
    protected abstract void remove(P p) throws IOException;
    protected abstract void export(P p, Path to) throws IOException;
    protected abstract P importfile(Path src) throws IOException;
    protected abstract void thumb(GOut g, P p, Coord at, int sz);
    protected abstract void place(P p);
    /** Adds the kind's save buttons in a row at y, each bw wide, up to five. */
    protected abstract void saverow(int x, int y, int bw);

    protected PresetsPanel(PlannerView view, Consumer<String> msg, Path dir, String title, String kind) {
        super(Coord.of(W, 10));
        this.view = view;
        this.plan = view.plan;
        this.msg = msg;
        this.dir = dir;
        this.title = title;
        this.kind = kind;
        listy = PAD + head.render("Ag").sz().y + small.render("Ag").sz().y + GAP;
        rowh = Math.max(UI.scale(30), fnd.render("Ag").sz().y + small.render("Ag").sz().y + UI.scale(6));
        int lw = UI.scale(300);
        lb = add(new Listbox<P>(lw, 7, rowh) {
            protected P listitem(int i) {return(list.get(i));}
            protected int listitems() {return(list.size());}
            protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
            protected void drawitem(GOut g, P p, int i) {
                if((i & 1) != 0) {
                    g.chcolor(NStyle.rowOdd);
                    g.frect(Coord.z, g.sz());
                    g.chcolor();
                }
                boolean ok = usable(p);
                Tex n = text(name(p), ok ? TXT : OFF, fnd);
                g.image(n, Coord.of(UI.scale(6), UI.scale(2)));
                g.image(text(ok ? summary(p) : "made by a different game version", ok ? DIM : OFF, small),
                        Coord.of(UI.scale(6), UI.scale(2) + n.sz().y));
            }
            public void change(P p) {
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
        saverow(PAD, y2, bw);
        add(new ToolButton(bw, "Done", null, this::hide), PAD + (bw + GAP) * 5, y2);
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
    }

    protected Tex text(String s, Color c, Text.Foundry f) {
        if(tex.size() > 600)
            tex.clear();
        return(tex.computeIfAbsent(s + "\u0001" + c.getRGB() + "\u0001" + System.identityHashCode(f), k -> f.render(s, c).tex()));
    }

    /* ---------- the list ---------- */

    private void reload(boolean force) {
        long st = PresetFiles.stamp(dir);
        if(!force && st == liststamp)
            return;
        liststamp = st;
        String sel = (lb.sel != null) ? file(lb.sel) + "\u0001" + mine(lb.sel) : null;
        list = load();
        lb.sel = null;
        for(P p : list)
            if(sel != null && sel.equals(file(p) + "\u0001" + mine(p)))
                lb.sel = p;
    }

    /** Re-reads the folder and selects one of your presets by file name. */
    protected void select(String file) {
        reload(true);
        for(P p : list)
            if(mine(p) && file != null && file.equals(file(p)))
                lb.change(p);
    }

    protected P selected() {
        return(lb.sel);
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
        P p = lb.sel;
        boolean ok = (p != null) && usable(p), own = ok && mine(p);
        place.disable(!ok);
        rename.disable(!own);
        describe.disable(!own);
        delete.disable(p == null || !mine(p));
        export.disable(p == null);
        int h = ((askwhat != null) ? prompty + entry.sz.y + PAD : prompty);
        if(sz.y != h)
            resize(Coord.of(W, h));
        super.tick(dt);
    }

    /* ---------- actions ---------- */

    private void place() {
        P p = lb.sel;
        if(p != null && usable(p))
            place(p);
    }

    private void rename() {
        P p = lb.sel;
        if(p == null || !mine(p))
            return;
        ask("Rename to", name(p), n -> {
            name(p, n);
            save(p, "Renamed to " + n);
        });
    }

    private void describe() {
        P p = lb.sel;
        if(p == null || !mine(p))
            return;
        ask("Description", description(p), t -> {
            description(p, t);
            save(p, "Description saved");
        }, true);
    }

    private void save(P p, String done) {
        try {
            write(p);
            msg.accept(done);
        } catch(IOException e) {
            msg.accept("Could not save: " + e.getMessage());
        }
        select(file(p));
    }

    /** Delete takes a second click: the file is the game's too. */
    private void delete() {
        P p = lb.sel;
        if(p == null || !mine(p))
            return;
        if(!confirmdelete) {
            confirmdelete = true;
            msg.accept("Click Delete again to delete " + name(p) + " (also gone from the game's Presets window)");
            return;
        }
        confirmdelete = false;
        try {
            remove(p);
            msg.accept("Deleted " + name(p));
        } catch(IOException e) {
            msg.accept("Could not delete: " + e.getMessage());
        }
        reload(true);
    }

    private void export() {
        P p = lb.sel;
        if(p == null)
            return;
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Export " + kind);
            fc.setFileFilter(new FileNameExtensionFilter(kind + " (JSON)", "json"));
            fc.setSelectedFile(new java.io.File(PresetFiles.slug(name(p)) + ".json"));
            if(fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            String f = fc.getSelectedFile().getAbsolutePath();
            if(!f.endsWith(".json"))
                f += ".json";
            String target = f;
            try {
                export(p, Paths.get(target));
                later.add(() -> msg.accept("Exported " + name(p) + " to " + target));
            } catch(IOException e) {
                later.add(() -> msg.accept("Export failed: " + e.getMessage()));
            }
        });
    }

    private void importfile() {
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Import " + kind);
            fc.setFileFilter(new FileNameExtensionFilter(kind + " (JSON)", "json"));
            if(fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            Path src = fc.getSelectedFile().toPath();
            try {
                P p = importfile(src);
                later.add(() -> {
                    msg.accept("Imported " + name(p));
                    select(file(p));
                });
            } catch(IOException e) {
                later.add(() -> msg.accept("Import failed: " + e.getMessage()));
            }
        });
    }

    protected void openfolder() {
        try {
            Files.createDirectories(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch(IOException | UnsupportedOperationException e) {
            msg.accept(title + " are in " + dir);
        }
    }

    /* ---------- the prompt ---------- */

    protected void ask(String what, String init, Consumer<String> done) {
        ask(what, init, done, false);
    }

    protected void ask(String what, String init, Consumer<String> done, boolean allowempty) {
        askwhat = what;
        askdone = done;
        askempty = allowempty;
        entry.settext(init);
        entry.show();
        ok.show();
        cancel.show();
        setfocus(entry);
    }

    private void answer() {
        String t = entry.text().trim();
        if(t.isEmpty() && !askempty)
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
        Tex t = text(title, NStyle.border, head);
        g.image(t, Coord.of(PAD, PAD));
        g.image(text("Shared with the game: " + dir, DIM, small), Coord.of(PAD, PAD + t.sz().y));
        P p = lb.sel;
        if(p != null && usable(p)) {
            Coord at = Coord.of(thumbx, listy);
            g.chcolor(NStyle.infoBg);
            g.frect(at, Coord.of(thumbsz, thumbsz));
            g.chcolor(70, 84, 86, 255);
            g.rect(at, Coord.of(thumbsz, thumbsz));
            g.chcolor();
            thumb(g, p, at, thumbsz);
        }
        int ty = listy + thumbsz + GAP;
        if(p == null) {
            g.image(text(list.isEmpty() ? "None yet: save one below, or in the game" : "Pick one", DIM, fnd), Coord.of(thumbx, listy));
        } else {
            g.image(text(usable(p) ? summary(p) : "Made by a different version of the game", TXT, small), Coord.of(thumbx, ty));
            ty += UI.scale(16);
            for(String line : wrap(description(p).isEmpty() ? "No description" : description(p), 44)) {
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

    static List<String> wrap(String s, int width) {
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
}
