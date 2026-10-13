package architect.ui;

import architect.AreaTool;
import architect.PlannerView;
import architect.edit.*;
import architect.res.AreaRules;
import haven.*;
import nurgling.NStyle;
import nurgling.areas.AreaPaths;
import org.json.*;

import java.awt.Color;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Areas window, laid out like the game's: folders and areas on the left, the selected area in the middle
 * (name, colour, hidden in game, fill direction, specialisations with their subtype and harvest stage), and
 * its Take and Put rules on the right (item, threshold, storage type). Specialisations and items are picked
 * from the game's own lists (see {@link AreaRules}). Every change is one undo step.
 */
public class AreasPanel extends Widget {
    public static final int W = UI.scale(900);
    static final int PAD = UI.scale(8), GAP = UI.scale(5);
    static final Color TXT = new Color(223, 231, 230), DIM = new Color(143, 163, 164), OFF = new Color(95, 105, 106);
    static final String[] FILLS = {"DEFAULT", "LEFT_TO_RIGHT", "RIGHT_TO_LEFT", "TOP_TO_BOTTOM", "BOTTOM_TO_TOP"};
    static final String[] FILLNAMES = {"Fill: bot's default", "Fill: left to right", "Fill: right to left", "Fill: top to bottom", "Fill: bottom to top"};
    static final String[] TYPES = {null, "CONTAINER", "PILE", "BARREL", "BARTER"};
    static final String[] TYPENAMES = {"Type: default", "Type: container", "Type: pile", "Type: barrel", "Type: barter"};

    private final PlannerView view;
    private final AreaTool tool;
    private final Plan plan;
    private final Consumer<String> msg;
    private final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true);
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
    private final Map<String, Tex> tex = new HashMap<>();

    /* columns */
    private final int treex = PAD, treew = UI.scale(240), detx, detw = UI.scale(330), inx, colw = UI.scale(148), outx;
    private final int top, rowh;

    /* folders and areas */
    private static class Row {
        final int kind;          /* 0 up, 1 folder, 2 area */
        final String path;
        final PlanArea area;

        Row(int kind, String path, PlanArea area) {
            this.kind = kind;
            this.path = path;
            this.area = area;
        }
    }
    private List<Row> rows = new ArrayList<>();
    private final Listbox<Row> tree;
    /** Folders made here that hold no area yet (a folder exists through its areas' paths). */
    private final Set<String> emptyfolders = new HashSet<>();

    /* the selected area */
    private PlanArea cur = null;
    private String shownsig = null;
    private boolean refreshing = false;
    private final TextEntry name;
    private double namedirty = 0;
    private final ToolButton hide;
    private final Button fill;
    private final List<Widget> swatches = new ArrayList<>();
    private List<JSONObject> specs = new ArrayList<>(), ins = new ArrayList<>(), outs = new ArrayList<>();
    private final Listbox<JSONObject> speclist, inlist, outlist;
    private final Button subtype, stage, spedel, inadd, indel, intype, outadd, outdel, outtype;
    private final TextEntry inth, outth;
    private final List<Widget> details = new ArrayList<>();

    /* the prompt (folder names) */
    private final TextEntry entry;
    private final Button ok, cancel;
    private String askwhat = null;
    private Consumer<String> askdone = null;
    private final int prompty, bottomy;

    private ListPicker<?> picker = null;
    private final Map<String, Tex> icons = new HashMap<>();
    private final Set<String> noicon = new HashSet<>();

    public AreasPanel(PlannerView view, Consumer<String> msg, Runnable openpresets, Runnable savepreset) {
        super(Coord.of(W, 10));
        this.view = view;
        this.tool = view.areas;
        this.plan = view.plan;
        this.msg = msg;
        detx = treex + treew + PAD * 2;
        inx = detx + detw + PAD * 2;
        outx = inx + colw + PAD;
        top = PAD + head.render("Ag").sz().y + small.render("Ag").sz().y + GAP;
        rowh = Math.max(UI.scale(26), fnd.render("Ag").sz().y + small.render("Ag").sz().y + UI.scale(4));

        /* ----- folders and areas ----- */
        tree = add(new Listbox<Row>(treew, 11, UI.scale(22)) {
            protected Row listitem(int i) {return(rows.get(i));}
            protected int listitems() {return(rows.size());}
            protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
            protected void drawitem(GOut g, Row r, int i) {
                if(r.kind == 2 && tool.isselected(r.area.id)) {
                    g.chcolor(233, 156, 84, 60);
                    g.frect(Coord.z, g.sz());
                } else if((i & 1) != 0) {
                    g.chcolor(NStyle.rowOdd);
                    g.frect(Coord.z, g.sz());
                }
                g.chcolor();
                int cy = g.sz().y / 2;
                if(r.kind == 2) {
                    Color c = r.area.color;
                    g.chcolor(c.getRed(), c.getGreen(), c.getBlue(), 255);
                    g.frect(Coord.of(UI.scale(6), cy - UI.scale(5)), Coord.of(UI.scale(10), UI.scale(10)));
                    g.chcolor();
                    Tex t = text(r.area.name, TXT, fnd);
                    g.image(t, Coord.of(UI.scale(22), cy - t.sz().y / 2));
                } else {
                    String label = (r.kind == 0) ? "..  (up)" : AreaPaths.name(r.path) + "  (" + count(r.path) + ")";
                    Tex t = text((r.kind == 1 ? "▸ " : "") + label, NStyle.border, fnd);
                    g.image(t, Coord.of(UI.scale(6), cy - t.sz().y / 2));
                }
            }
            public void change(Row r) {
                super.change(null);
                if(r != null)
                    clicked(r);
            }
        }, treex, top + small.render("Ag").sz().y + GAP);
        int ty = tree.c.y + tree.sz.y + GAP, tbw = (treew - GAP) / 2;
        add(new Button(tbw, "+ Folder", false, this::newfolder), treex, ty);
        add(new Button(tbw, "Rename folder", false, this::renamefolder), treex + tbw + GAP, ty);
        ty += UI.scale(28);
        add(new Button(tbw, "Move here", false, this::movehere), treex, ty).settip("Move the selected areas into the open folder");
        add(new Button(tbw, "Delete", false, tool::delete), treex + tbw + GAP, ty).settip("Delete the selected areas (Ctrl+Z brings them back)");

        /* ----- the selected area ----- */
        int y = top + small.render("Ag").sz().y + GAP;
        name = add(new TextEntry(detw - UI.scale(60), "") {
            protected void changed() {
                super.changed();
                if(!refreshing)
                    namedirty = Utils.rtime();
            }
            public void activate(String text) {
                commitname();
            }
        }, detx + UI.scale(60), y);
        details.add(name);
        y += name.sz.y + GAP;
        for(int i = 0; i < AreaTool.PALETTE.length; i++) {
            Color col = AreaTool.PALETTE[i];
            Widget sw = add(new Widget(Coord.of(UI.scale(20), UI.scale(20))) {
                public void draw(GOut g) {
                    g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), 255);
                    g.frect(Coord.z, sz);
                    if(cur != null && cur.color.getRGB() == col.getRGB()) {
                        g.chcolor(Color.WHITE);
                        g.rect(Coord.z, sz);
                        g.rect(Coord.of(1, 1), sz.sub(2, 2));
                    }
                    g.chcolor();
                }
                public boolean mousedown(MouseDownEvent ev) {
                    edit("Area colour", e -> e.color = col);
                    return(true);
                }
            }, detx + UI.scale(60) + i * UI.scale(24), y);
            swatches.add(sw);
            details.add(sw);
        }
        y += UI.scale(24) + GAP;
        int hw = (detw - GAP) / 2;
        hide = add(new ToolButton(hw, "Hidden in game", () -> cur != null && cur.hide, () -> edit("Hide area", e -> e.hide = !e.hide)), detx, y);
        hide.settip("Saved as the area's 'hide' flag: the game doesn't draw it on the map");
        fill = add(new Button(hw, FILLNAMES[0], false, this::cyclefill), detx + hw + GAP, y);
        fill.settip("The corner bots fill piles and containers from");
        details.add(hide);
        details.add(fill);
        y += hide.sz.y + GAP;
        speclisty = y + small.render("Ag").sz().y + UI.scale(2);
        speclist = add(new Listbox<JSONObject>(detw, 6, rowh) {
            protected JSONObject listitem(int i) {return(specs.get(i));}
            protected int listitems() {return(specs.size());}
            protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
            protected void drawitem(GOut g, JSONObject s, int i) {
                if((i & 1) != 0) {
                    g.chcolor(NStyle.rowOdd);
                    g.frect(Coord.z, g.sz());
                    g.chcolor();
                }
                String n = s.optString("name", "?");
                AreaRules.Spec sp = AreaRules.spec(n);
                int isz = rowh - UI.scale(6);
                Tex ic = (sp == null || sp.icon == null) ? null : icons.computeIfAbsent("spec:" + n, k -> new TexI(sp.icon));
                if(ic != null)
                    g.image(ic, Coord.of(UI.scale(4), UI.scale(3)), Coord.of(isz, isz));
                String sub = s.optString("subtype", "");
                Tex t = text(AreaRules.pretty(n) + (sub.isEmpty() ? "" : " (" + sub + ")"), (sp == null) ? OFF : TXT, fnd);
                int x = isz + UI.scale(10);
                g.image(t, Coord.of(x, UI.scale(2)));
                String second = (sp == null) ? "Not known to this game version; kept as it is" : (s.has("min_harvest_stage") ? "Harvest at stage " + s.optInt("min_harvest_stage") + " or later" : "");
                if(!second.isEmpty())
                    g.image(text(second, DIM, small), Coord.of(x, UI.scale(2) + t.sz().y));
            }
        }, detx, speclisty);
        details.add(speclist);
        y = speclisty + speclist.sz.y + GAP;
        int sbw = (detw - GAP * 3) / 4;
        Button addspec = add(new Button(sbw, "+ Add", false, this::addspec), detx, y);
        subtype = add(new Button(sbw, "Subtype…", false, this::picksubtype), detx + (sbw + GAP), y);
        stage = add(new Button(sbw, "Stage…", false, this::pickstage), detx + (sbw + GAP) * 2, y);
        spedel = add(new Button(sbw, "Remove", false, this::removespec), detx + (sbw + GAP) * 3, y);
        stage.settip("Crop fields: wait until every plant reaches this harvest stage");
        details.addAll(Arrays.asList(addspec, subtype, stage, spedel));

        /* ----- Take / Put ----- */
        int iy = top + small.render("Ag").sz().y + GAP;
        inlist = itemlist(inx, iy, () -> ins);
        outlist = itemlist(outx, iy, () -> outs);
        int by = inlist.c.y + inlist.sz.y + GAP, ibw = (colw - GAP) / 2;
        inadd = add(new Button(ibw, "+ Add", false, () -> additem("in")), inx, by);
        indel = add(new Button(ibw, "Remove", false, () -> removeitem("in")), inx + ibw + GAP, by);
        outadd = add(new Button(ibw, "+ Add", false, () -> additem("out")), outx, by);
        outdel = add(new Button(ibw, "Remove", false, () -> removeitem("out")), outx + ibw + GAP, by);
        by += UI.scale(28);
        intype = add(new Button(colw, TYPENAMES[0], false, () -> cycletype("in")), inx, by);
        outtype = add(new Button(colw, TYPENAMES[0], false, () -> cycletype("out")), outx, by);
        by += UI.scale(28);
        inth = add(thentry("in"), inx + UI.scale(70), by);
        outth = add(thentry("out"), outx + UI.scale(70), by);
        thy = by;
        details.addAll(Arrays.asList(inlist, outlist, inadd, indel, outadd, outdel, intype, outtype, inth, outth));

        /* ----- bottom ----- */
        bottomy = Math.max(Math.max(ty + UI.scale(28), y + UI.scale(28)), by + inth.sz.y) + GAP + UI.scale(14);
        int bw = UI.scale(150), x = PAD;
        add(new ToolButton(bw, "Presets…", null, openpresets), x, bottomy); x += bw + GAP;
        add(new ToolButton(bw, "Save as preset…", null, savepreset), x, bottomy).settip("Save the selected areas as an area preset for the game");
        x += bw + GAP;
        add(new ToolButton(bw, "Copy settings", null, tool::copysettings), x, bottomy).settip("Ctrl+Shift+C: the selected area's specialisations, Take/Put and fill");
        x += bw + GAP;
        add(new ToolButton(bw, "Paste settings", null, tool::pastesettings), x, bottomy).settip("Ctrl+Shift+V: onto every selected area");
        add(new ToolButton(UI.scale(100), "Done", null, () -> {
            tool.cancelstamp();
            tool.on = false;
            tool.clearsel();
        }), W - PAD - UI.scale(100), bottomy);
        prompty = bottomy + UI.scale(28) + GAP;
        int pw = UI.scale(80);
        entry = add(new TextEntry(W - PAD * 2 - pw * 2 - GAP * 2 - UI.scale(130), "") {
            public void activate(String text) {
                answer();
            }
        }, PAD + UI.scale(130), prompty);
        ok = add(new Button(pw, "OK", false, this::answer), W - PAD - pw * 2 - GAP, prompty - UI.scale(2));
        cancel = add(new Button(pw, "Cancel", false, this::unask), W - PAD - pw, prompty - UI.scale(2));
        unask();
        tool.selchanged = () -> shownsig = null;
        resize(Coord.of(W, prompty));
    }

    private final int speclisty, thy;

    private Listbox<JSONObject> itemlist(int x, int y, java.util.function.Supplier<List<JSONObject>> src) {
        return(add(new Listbox<JSONObject>(colw, 8, rowh) {
            protected JSONObject listitem(int i) {return(src.get().get(i));}
            protected int listitems() {return(src.get().size());}
            protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
            protected void drawitem(GOut g, JSONObject it, int i) {
                if((i & 1) != 0) {
                    g.chcolor(NStyle.rowOdd);
                    g.frect(Coord.z, g.sz());
                    g.chcolor();
                }
                int isz = rowh - UI.scale(6);
                Tex ic = itemicon(it.optString("static", null));
                if(ic != null)
                    g.image(ic, Coord.of(UI.scale(3), UI.scale(3)), Coord.of(isz, isz));
                int tx = isz + UI.scale(7);
                Tex t = text(it.optString("name", "?"), TXT, small);
                g.image(t, Coord.of(tx, UI.scale(2)));
                List<String> bits = new ArrayList<>();
                if(it.has("th"))
                    bits.add("th " + it.optInt("th"));
                String ty = it.optString("type", "");
                if(!ty.isEmpty())
                    bits.add(ty.charAt(0) + ty.substring(1).toLowerCase());
                if(!bits.isEmpty())
                    g.image(text(String.join(" · ", bits), DIM, small), Coord.of(tx, UI.scale(2) + t.sz().y));
            }
            public void change(JSONObject it) {
                super.change(it);
                shownsig = null;
            }
        }, x, y));
    }

    private TextEntry thentry(String which) {
        return(new TextEntry(colw - UI.scale(70), "") {
            public void activate(String text) {
                setthreshold(which, text.trim());
            }
        });
    }

    private Tex text(String s, Color c, Text.Foundry f) {
        if(tex.size() > 800)
            tex.clear();
        return(tex.computeIfAbsent(s + "\u0001" + c.getRGB() + "\u0001" + System.identityHashCode(f), k -> f.render(s, c).tex()));
    }

    /** An item's inventory icon, downloaded on demand; null while loading or when it has none. */
    private Tex itemicon(String res) {
        if(res == null || noicon.contains(res))
            return(null);
        Tex t = icons.get(res);
        if(t != null)
            return(t);
        try {
            Resource.Image img = Resource.remote().load(res).get().layer(Resource.imgc);
            if(img == null)
                noicon.add(res);
            else
                icons.put(res, t = img.tex());
        } catch(Loading l) {
            /* next frame */
        } catch(Resource.LoadException | Resource.BadResourceException e) {
            noicon.add(res);
        }
        return(t);
    }

    /* ---------- the tree ---------- */

    private Set<String> paths() {
        Set<String> ps = new HashSet<>(emptyfolders);
        for(PlanArea a : plan.areas())
            ps.add(a.path);
        return(ps);
    }

    private int count(String folder) {
        int n = 0;
        for(PlanArea a : plan.areas())
            if(AreaPaths.isUnder(a.path, folder))
                n++;
        return(n);
    }

    private void rebuildtree() {
        String f = tool.folder;
        if(!f.isEmpty() && !paths().contains(f) && AreaPaths.allFolders(paths()).stream().noneMatch(p -> p.equals(f)))
            tool.folder = AreaPaths.ROOT;
        List<Row> r = new ArrayList<>();
        if(!tool.folder.isEmpty())
            r.add(new Row(0, AreaPaths.parent(tool.folder), null));
        for(String c : AreaPaths.childFolders(paths(), tool.folder))
            r.add(new Row(1, c, null));
        List<PlanArea> here = new ArrayList<>();
        for(PlanArea a : plan.areas())
            if(a.path.equals(tool.folder) && plan.layers.shown(a.layer))
                here.add(a);
        here.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        for(PlanArea a : here)
            r.add(new Row(2, a.path, a));
        rows = r;
    }

    private void clicked(Row r) {
        if(r.kind == 2) {
            if((ui.modflags() & UI.MOD_SHIFT) != 0)
                tool.toggle(r.area.id);
            else
                tool.select(Collections.singleton(r.area.id), false);
        } else {
            tool.folder = r.path;
            shownsig = null;
        }
    }

    private void newfolder() {
        ask("New folder name", "", n -> {
            String c = AreaPaths.clean(n);
            if(c.isEmpty())
                return;
            String f = AreaPaths.join(tool.folder, c);
            emptyfolders.add(f);
            tool.folder = f;
            shownsig = null;
        });
    }

    private void renamefolder() {
        String f = tool.folder;
        if(f.isEmpty()) {
            msg.accept("Open a folder to rename it");
            return;
        }
        ask("Rename folder to", AreaPaths.name(f), n -> {
            String c = AreaPaths.clean(n).replace('/', ' ');
            if(c.isEmpty())
                return;
            String nf = AreaPaths.join(AreaPaths.parent(f), c);
            plan.begin("Rename folder");
            try {
                for(PlanArea a : plan.areas()) {
                    if(AreaPaths.isUnder(a.path, f)) {
                        PlanArea.Edit e = a.edit();
                        e.path = AreaPaths.reparent(a.path, f, nf);
                        plan.putarea(a.with(e));
                    }
                }
            } finally {
                plan.commit();
            }
            Set<String> moved = new HashSet<>();
            for(String ef : emptyfolders)
                moved.add(AreaPaths.isUnder(ef, f) ? AreaPaths.reparent(ef, f, nf) : ef);
            emptyfolders.clear();
            emptyfolders.addAll(moved);
            tool.folder = nf;
            shownsig = null;
        });
    }

    private void movehere() {
        List<PlanArea> s = tool.selected();
        if(s.isEmpty()) {
            msg.accept("Select areas, open the folder they go in, then Move here");
            return;
        }
        plan.begin("Move areas to folder");
        try {
            for(PlanArea a : s) {
                PlanArea.Edit e = a.edit();
                e.path = tool.folder;
                plan.putarea(a.with(e));
            }
        } finally {
            plan.commit();
        }
        emptyfolders.remove(tool.folder);
        msg.accept("Moved " + s.size() + " area" + ((s.size() == 1) ? "" : "s") + " to " + (tool.folder.isEmpty() ? "the top level" : tool.folder));
    }

    /* ---------- editing the selected area ---------- */

    /** Changes the selected area as one undo step. */
    private void edit(String what, Consumer<PlanArea.Edit> f) {
        PlanArea a = (cur == null) ? null : plan.area(cur.id);
        if(a == null)
            return;
        PlanArea.Edit e = a.edit();
        f.accept(e);
        plan.begin(what);
        try {
            plan.putarea(a.with(e));
        } finally {
            plan.commit();
        }
        shownsig = null;
    }

    private void commitname() {
        namedirty = 0;
        String n = name.text().trim();
        if(cur != null && !n.isEmpty() && !n.equals(cur.name))
            edit("Rename area", e -> e.name = n);
    }

    private void cyclefill() {
        if(cur == null)
            return;
        int i = Arrays.asList(FILLS).indexOf(cur.fill);
        String next = FILLS[(i + 1) % FILLS.length];
        edit("Fill direction", e -> e.fill = next);
    }

    private int specindex() {
        return(speclist.sel == null ? -1 : specs.indexOf(speclist.sel));
    }

    /** Replaces the specs, keeping the row at index selected. */
    private void setspecs(String what, Consumer<JSONArray> f, int keep) {
        edit(what, e -> f.accept(e.spec));
        keepspec = keep;
    }
    private int keepspec = -1;

    /** Pickers cover the middle and right columns, inside the window. */
    private int pickw() {
        return(W - detx - PAD);
    }

    private int prows() {
        /* title, search, buttons and paddings take about 110 px; rows are about 30 */
        return(Math.max(4, (prompty - top - UI.scale(110)) / UI.scale(30)));
    }

    private void openpicker(ListPicker<?> p) {
        if(picker != null)
            picker.destroy();
        picker = add(p, detx, top);
        picker.raise();
    }

    private void addspec() {
        if(cur == null)
            return;
        openpicker(new ListPicker<AreaRules.Spec>("Add a specialisation", pickw(), prows(), AreaRules.specs(),
            s -> s.pretty, s -> {String b = AreaRules.bots(s.name); return((b == null) ? "…" : b);},
            s -> (s.icon == null) ? null : icons.computeIfAbsent("spec:" + s.name, k -> new TexI(s.icon)),
            s -> s.pretty + " " + s.name, s -> {
                PlanArea a = plan.area(cur.id);
                boolean rename = a != null && a.name.matches("Area \\d+");
                edit("Add specialisation", e -> {
                    JSONObject o = new JSONObject();
                    o.put("name", s.name);
                    e.spec.put(o);
                    if(rename)
                        e.name = s.pretty;
                });
                keepspec = specs.size();
            }, null));
    }

    private void removespec() {
        int i = specindex();
        if(i < 0)
            return;
        setspecs("Remove specialisation", arr -> arr.remove(i), Math.min(i, specs.size() - 2));
    }

    private void picksubtype() {
        int i = specindex();
        if(i < 0)
            return;
        JSONObject s = specs.get(i);
        List<String> opts = AreaRules.subtypes(s.optString("name"));
        if(opts.isEmpty()) {
            msg.accept(AreaRules.pretty(s.optString("name")) + " has no subtypes");
            return;
        }
        List<String> l = new ArrayList<>();
        l.add("(none)");
        l.addAll(opts);
        openpicker(new ListPicker<String>("Subtype of " + AreaRules.pretty(s.optString("name")), pickw(), prows(), l,
            o -> o, null, null, null, o -> {
                String pretty = AreaRules.pretty(s.optString("name"));
                edit("Subtype", e -> {
                    JSONObject t = e.spec.getJSONObject(i);
                    String old = t.optString("subtype", "");
                    if(o.equals("(none)"))
                        t.remove("subtype");
                    else
                        t.put("subtype", o);
                    if(!o.equals(old))
                        t.remove("min_harvest_stage");   /* a stage number means something else for another crop */
                    /* the game's habit: an area named after its specialisation follows the subtype */
                    if(e.name.equals(pretty) || e.name.equals(pretty + "(" + old + ")"))
                        e.name = o.equals("(none)") ? pretty : pretty + "(" + o + ")";
                });
                keepspec = i;
            }, null));
    }

    private void pickstage() {
        int i = specindex();
        if(i < 0)
            return;
        JSONObject s = specs.get(i);
        String sub = s.optString("subtype", null);
        List<Integer> st = (sub == null) ? Collections.emptyList() : AreaRules.stages(sub);
        if(st.isEmpty()) {
            msg.accept("Only crop fields with a choice of harvest stage have one");
            return;
        }
        List<Integer> l = new ArrayList<>();
        l.add(-1);
        l.addAll(st);
        openpicker(new ListPicker<Integer>("Harvest " + sub + " at", pickw(), Math.min(8, prows()), l,
            n -> (n < 0) ? "Any stage" : "Stage " + n, n -> (n < 0) ? "" : AreaRules.describestage(sub, n), null, null, n -> {
                edit("Harvest stage", e -> {
                    JSONObject t = e.spec.getJSONObject(i);
                    if(n < 0)
                        t.remove("min_harvest_stage");
                    else
                        t.put("min_harvest_stage", n);
                });
                keepspec = i;
            }, null));
    }

    /* ---------- Take / Put ---------- */

    private Listbox<JSONObject> listof(String which) {
        return(which.equals("in") ? inlist : outlist);
    }

    private List<JSONObject> itemsof(String which) {
        return(which.equals("in") ? ins : outs);
    }

    private JSONArray arrof(PlanArea.Edit e, String which) {
        return(which.equals("in") ? e.in : e.out);
    }

    private void additem(String which) {
        if(cur == null)
            return;
        openpicker(new ListPicker<AreaRules.Item>((which.equals("in") ? "Take" : "Put") + ": add an item", pickw(), prows(), AreaRules.items(),
            it -> it.name, it -> String.join(", ", it.categories), it -> itemicon(it.res), it -> it.name + " " + String.join(" ", it.categories),
            it -> additem(which, it.entry()), typed -> {
                JSONObject o = new JSONObject();
                o.put("name", typed);
                additem(which, o);
            }));
    }

    private void additem(String which, JSONObject o) {
        for(JSONObject x : itemsof(which))
            if(x.optString("name").equals(o.optString("name"))) {
                msg.accept(o.optString("name") + " is already there");
                return;
            }
        edit((which.equals("in") ? "Take " : "Put ") + o.optString("name"), e -> arrof(e, which).put(o));
    }

    private int itemindex(String which) {
        Listbox<JSONObject> lb = listof(which);
        return(lb.sel == null ? -1 : itemsof(which).indexOf(lb.sel));
    }

    private void removeitem(String which) {
        int i = itemindex(which);
        if(i < 0)
            return;
        edit("Remove item", e -> arrof(e, which).remove(i));
        listof(which).sel = null;
    }

    private void cycletype(String which) {
        int i = itemindex(which);
        if(i < 0)
            return;
        String t = itemsof(which).get(i).optString("type", null);
        int k = Arrays.asList(TYPES).indexOf(t);
        String next = TYPES[(Math.max(k, 0) + 1) % TYPES.length];
        edit("Storage type", e -> {
            JSONObject o = arrof(e, which).getJSONObject(i);
            if(next == null)
                o.remove("type");
            else
                o.put("type", next);
        });
    }

    private void setthreshold(String which, String text) {
        int i = itemindex(which);
        if(i < 0)
            return;
        Integer v = null;
        if(!text.isEmpty()) {
            try {
                v = Integer.parseInt(text);
            } catch(NumberFormatException ex) {
                msg.accept("The threshold is a whole number (or empty for none)");
                return;
            }
        }
        Integer val = v;
        edit("Threshold", e -> {
            JSONObject o = arrof(e, which).getJSONObject(i);
            if(val == null)
                o.remove("th");
            else
                o.put("th", val);
        });
    }

    /** Dev: opens a picker without a click ("spec" or "item"). */
    public void demopicker(String which) {
        if(which.equals("spec"))
            addspec();
        else
            additem("out");
    }

    /* ---------- the prompt ---------- */

    private void ask(String what, String init, Consumer<String> done) {
        askwhat = what;
        askdone = done;
        entry.settext(init);
        entry.show();
        ok.show();
        cancel.show();
        setfocus(entry);
    }

    private void answer() {
        String t = entry.text().trim();
        Consumer<String> d = askdone;
        unask();
        if(d != null && !t.isEmpty())
            d.accept(t);
    }

    private void unask() {
        askwhat = null;
        askdone = null;
        entry.hide();
        ok.hide();
        cancel.hide();
    }

    /* ---------- keeping in step with the plan ---------- */

    public void tick(double dt) {
        if(picker != null && picker.parent == null)
            picker = null;
        if(namedirty > 0 && Utils.rtime() - namedirty > 0.8)
            commitname();
        List<PlanArea> sel = tool.selected();
        PlanArea one = (sel.size() == 1) ? sel.get(0) : null;
        String sig = tool.folder + "\u0001" + plan.areaseq + "\u0001" + plan.layers.seq + "\u0001" + sel.size() + "\u0001" + ((one == null) ? "" : one.id)
            + "\u0001" + System.identityHashCode(inlist.sel) + "\u0001" + System.identityHashCode(outlist.sel);
        if(!sig.equals(shownsig)) {
            if(namedirty > 0 && cur != null && (one == null || !one.id.equals(cur.id)))
                commitname();
            shownsig = sig;
            refresh(one);
        }
        int h = (askwhat != null) ? prompty + entry.sz.y + PAD : prompty;
        if(picker != null)
            h = Math.max(h, picker.c.y + picker.sz.y + PAD);
        if(sz.y != h)
            resize(Coord.of(W, h));
        super.tick(dt);
    }

    private void refresh(PlanArea one) {
        rebuildtree();
        boolean changed = (one == null) != (cur == null) || (one != null && !one.id.equals(cur.id));
        cur = one;
        refreshing = true;
        try {
            if(cur == null) {
                specs = new ArrayList<>();
                ins = new ArrayList<>();
                outs = new ArrayList<>();
            } else {
                if(changed || namedirty == 0)
                    name.settext(cur.name);
                int ks = changed ? -1 : ((keepspec >= 0) ? keepspec : specindex());
                int ki = changed ? -1 : itemindex("in"), ko = changed ? -1 : itemindex("out");
                specs = list(cur.specs());
                ins = list(cur.ins());
                outs = list(cur.outs());
                speclist.sel = (ks >= 0 && ks < specs.size()) ? specs.get(ks) : null;
                inlist.sel = (ki >= 0 && ki < ins.size()) ? ins.get(ki) : null;
                outlist.sel = (ko >= 0 && ko < outs.size()) ? outs.get(ko) : null;
                fill.change(FILLNAMES[Math.max(0, Arrays.asList(FILLS).indexOf(cur.fill))]);
            }
            keepspec = -1;
            for(String w : new String[] {"in", "out"}) {
                JSONObject it = listof(w).sel;
                Button tb = w.equals("in") ? intype : outtype;
                TextEntry te = w.equals("in") ? inth : outth;
                tb.change(TYPENAMES[Math.max(0, Arrays.asList(TYPES).indexOf((it == null) ? null : it.optString("type", null)))]);
                te.settext((it != null && it.has("th")) ? String.valueOf(it.optInt("th")) : "");
            }
            for(Widget w : details)
                w.show(cur != null);
            subtype.show(cur != null && specindex() >= 0 && !AreaRules.subtypes(specs.get(specindex()).optString("name")).isEmpty());
            stage.show(cur != null && specindex() >= 0 && !AreaRules.stages(specs.get(specindex()).optString("subtype", "")).isEmpty());
            spedel.show(cur != null && specindex() >= 0);
            for(String w : new String[] {"in", "out"}) {
                boolean has = cur != null && listof(w).sel != null;
                (w.equals("in") ? intype : outtype).show(has);
                (w.equals("in") ? inth : outth).show(has);
                (w.equals("in") ? indel : outdel).show(has);
            }
        } finally {
            refreshing = false;
        }
    }

    private static List<JSONObject> list(JSONArray a) {
        List<JSONObject> l = new ArrayList<>();
        for(int i = 0; i < a.length(); i++)
            l.add(a.getJSONObject(i));
        return(l);
    }

    public boolean mousedown(MouseDownEvent ev) {
        if(!ev.propagate(this))
            super.mousedown(ev);
        /* a click on a spec row picks it: re-show the buttons that act on it; the panel keeps the click off the map */
        shownsig = null;
        return(true);
    }

    /* ---------- drawing ---------- */

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.chcolor();
        g.image(text("Areas", NStyle.border, head), Coord.of(PAD, PAD));
        g.image(text("For area presets the game places as they are. Ground: drag a new area · click selects, Shift+click adds · drag moves · drag an edge resizes · Ctrl+C/V/D · Del", DIM, small),
                Coord.of(PAD + UI.scale(60), PAD + UI.scale(3)));
        int ly = top;
        g.image(text(tool.folder.isEmpty() ? "Folders and areas" : "In " + tool.folder, DIM, small), Coord.of(treex, ly));
        List<PlanArea> sel = tool.selected();
        if(cur != null) {
            g.image(text("Name", TXT, fnd), Coord.of(detx, name.c.y + UI.scale(3)));
            g.image(text("Colour", TXT, fnd), Coord.of(detx, swatches.get(0).c.y + UI.scale(2)));
            g.image(text("Specialisations", DIM, small), Coord.of(detx, speclisty - small.render("Ag").sz().y - UI.scale(2)));
            if(specs.isEmpty())
                g.image(text("None: + Add picks one", OFF, small), Coord.of(detx + UI.scale(6), speclisty + UI.scale(6)));
            g.image(text("Take (bots fetch from here)", DIM, small), Coord.of(inx, ly));
            g.image(text("Put (bots store here)", DIM, small), Coord.of(outx, ly));
            for(String w : new String[] {"in", "out"}) {
                if(listof(w).sel != null)
                    g.image(text("Threshold", TXT, small), Coord.of(w.equals("in") ? inx : outx, thy + UI.scale(4)));
            }
        } else {
            String s = sel.isEmpty() ? "Select an area: click it on the ground or in the list, or drag on the ground to draw one"
                : sel.size() + " areas selected. Paste settings sets them all; select one to edit it.";
            int yy = ly;
            for(String line : PresetsPanel.wrap(s, 48)) {
                g.image(text(line, DIM, fnd), Coord.of(detx, yy));
                yy += UI.scale(16);
            }
        }
        if(askwhat != null)
            g.image(text(askwhat + ":", TXT, fnd), Coord.of(PAD, prompty + UI.scale(2)));
        super.draw(g);
    }
}
