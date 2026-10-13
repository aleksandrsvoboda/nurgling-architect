package architect.ui;

import architect.res.Catalogue;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.*;

/**
 * Left-hand object browser: the game's build menu, or every object grouped by kind, with search. Clicking an
 * entry starts placing it. Build entries whose model the game server doesn't have are listed greyed out.
 */
public class CataloguePanel extends Widget {
    public static final int W = UI.scale(280);
    static final Color DIM = new Color(143, 163, 164), OFF = new Color(95, 105, 106);
    static final int PAD = UI.scale(6);
    /** Object folders (gfx/terobjs/<folder>) in display order, with their names; unknown folders go after. */
    static final String[][] KINDS = {
        {"misc", "Objects"}, {"arch", "Buildings"}, {"furn", "Furniture"}, {"vehicle", "Vehicles"},
        {"trees", "Trees"}, {"bushes", "Bushes"}, {"bumlings", "Rocks"}, {"plants", "Crops"}, {"herbs", "Herbs"},
        {"road", "Roads"}, {"map", "Map features"}, {"wonders", "Wonders"}, {"dng", "Dungeon"}, {"mm", "Markers"},
        {"items", "Dropped items"},
    };
    private final Catalogue cat;
    private final Consumer<Catalogue.Entry> pick;
    private final TextEntry search;
    private final ToolButton tbuild, tall;
    private boolean buildtab = true;
    private List<Catalogue.Entry> shown = Collections.emptyList();
    private EntryList list;
    private String lastq = null;
    private final Map<Catalogue.Entry, Tex[]> labels = new HashMap<>();
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
    private final Text.Foundry initial = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12).aa(true);
    private final int rowh, icon;
    private final Map<String, String> objicon = new HashMap<>();
    private final Map<String, Integer> kindrank = new HashMap<>();
    private Tex title, footer;
    private int lastfooter = -1;

    public CataloguePanel(Catalogue cat, int h, Consumer<Catalogue.Entry> pick) {
        super(Coord.of(W, h));
        this.cat = cat;
        this.pick = pick;
        /* Rows fit a name line and a category line in the actual fonts, so they never overlap. */
        int nh = fnd.render("Ag").sz().y, ch = small.render("Ag").sz().y;
        this.rowh = Math.max(nh + ch + UI.scale(4), UI.scale(26));
        this.icon = rowh - UI.scale(6);
        /* Objects that are also build-menu entries show that entry's icon. */
        for(Catalogue.Entry e : cat.build)
            if(e.res != null && e.pagina != null)
                objicon.putIfAbsent(e.res, e.pagina);
        for(int i = 0; i < KINDS.length; i++)
            kindrank.put(KINDS[i][1], i);
        title = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 14).aa(true).render("Objects", NStyle.border).tex();
        int y = PAD + title.sz().y + PAD;
        int bw = (W - PAD * 3) / 2;
        tbuild = add(new ToolButton(bw, "Build menu", () -> buildtab, () -> tab(true)), PAD, y);
        tall = add(new ToolButton(bw, "All objects", () -> !buildtab, () -> tab(false)), PAD * 2 + bw, y);
        y += tbuild.sz.y + PAD;
        search = add(new TextEntry(W - PAD * 2, "") {
            protected void changed() {
                super.changed();
                refilter();
            }
        }, PAD, y);
        relayout();
    }

    /** Display name of an object folder. */
    static String kind(String folder) {
        for(String[] k : KINDS)
            if(k[0].equals(folder))
                return(k[1]);
        return(Character.toUpperCase(folder.charAt(0)) + folder.substring(1));
    }

    private int listtop() {
        return(search.c.y + search.sz.y + PAD);
    }

    private void relayout() {
        int sb = 0;
        if(list != null) {
            sb = list.sb.val;
            list.destroy();
        }
        int rows = Math.max(1, (sz.y - listtop() - UI.scale(24)) / rowh);
        list = add(new EntryList(W - PAD * 2, rows), PAD, listtop());
        list.sb.val = sb;
        lastq = null;
        refilter();
    }

    private void tab(boolean build) {
        buildtab = build;
        lastq = null;
        refilter();
    }

    private void refilter() {
        String q = search.text() + (buildtab ? "\u0001b" : "\u0001a");
        if(q.equals(lastq))
            return;
        lastq = q;
        if(buildtab) {
            shown = cat.search(cat.build, search.text());
        } else {
            String s = search.text().trim().toLowerCase();
            shown = cat.objects.stream()
                .filter(e -> s.isEmpty() || e.name.toLowerCase().contains(s) || kind(e.category).toLowerCase().contains(s) || e.res.toLowerCase().contains(s))
                .sorted(Comparator.comparingInt((Catalogue.Entry e) -> kindrank.getOrDefault(kind(e.category), KINDS.length))
                        .thenComparing(e -> kind(e.category)).thenComparing(e -> e.name))
                .collect(Collectors.toList());
        }
        if(list != null)
            list.sb.val = 0;
    }

    public void resize(Coord sz) {
        super.resize(sz);
        relayout();
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.line(Coord.of(sz.x - 1, 0), Coord.of(sz.x - 1, sz.y), 1);
        g.chcolor();
        g.image(title, Coord.of(PAD + UI.scale(2), PAD));
        if(shown.size() != lastfooter) {
            lastfooter = shown.size();
            long ok = shown.stream().filter(Catalogue.Entry::placeable).count();
            footer = small.render((ok == shown.size()) ? String.format("%d objects", shown.size())
                                  : String.format("%d shown, %d placeable", shown.size(), ok), DIM).tex();
        }
        g.image(footer, Coord.of(PAD + UI.scale(2), sz.y - UI.scale(18)));
        super.draw(g);
    }

    private Tex[] label(Catalogue.Entry e) {
        return(labels.computeIfAbsent(e, k -> {
            String c = buildtab ? k.category : kind(k.category);
            if(!k.placeable())
                c += " · not available";
            return(new Tex[] {
                fnd.render(k.name, k.placeable() ? Color.WHITE : OFF).tex(),
                small.render(c, k.placeable() ? DIM : OFF).tex(),
            });
        }));
    }

    /* ---------- icons ---------- */

    private final Map<String, Tex> icons = new HashMap<>();
    private final Set<String> noicon = new HashSet<>();

    /** The resource whose image represents an entry: its menu entry, the build entry that places it, or for
     *  a dropped item its inventory icon. Null when there is none. */
    private String iconres(Catalogue.Entry e) {
        if(e.pagina != null)
            return(e.pagina);
        if(e.res == null)
            return(null);
        String p = objicon.get(e.res);
        if(p != null)
            return(p);
        if(e.res.startsWith("gfx/terobjs/items/"))
            return("gfx/invobjs/" + e.res.substring(e.res.lastIndexOf('/') + 1));
        return(null);
    }

    private Tex icon(Catalogue.Entry e) {
        String r = iconres(e);
        if(r == null || noicon.contains(r))
            return(null);
        Tex t = icons.get(r);
        if(t != null)
            return(t);
        try {
            Resource.Image img = Resource.remote().load(r).get().layer(Resource.imgc);
            if(img != null)
                icons.put(r, t = img.tex());
            else
                noicon.add(r);
        } catch(Loading l) {
            /* still loading; drawn next frame */
        } catch(Resource.LoadException | Resource.BadResourceException ex) {
            noicon.add(r);
        }
        return(t);
    }

    private final Map<Character, Tex> initials = new HashMap<>();
    /** A framed initial for entries without an icon, so every row has the same shape. */
    private void placeholder(GOut g, Coord c, Catalogue.Entry e) {
        g.chcolor(NStyle.rowOdd);
        g.frect(c, Coord.of(icon, icon));
        g.chcolor(70, 84, 86, 255);
        g.rect(c, Coord.of(icon, icon));
        g.chcolor();
        char ch = Character.toUpperCase(e.name.isEmpty() ? '?' : e.name.charAt(0));
        Tex t = initials.computeIfAbsent(ch, k -> initial.render(String.valueOf(k), DIM).tex());
        g.image(t, c.add((icon - t.sz().x) / 2, (icon - t.sz().y) / 2));
    }

    private class EntryList extends Listbox<Catalogue.Entry> {
        EntryList(int w, int rows) {
            super(w, rows, rowh);
        }

        protected Catalogue.Entry listitem(int i) {return(shown.get(i));}
        protected int listitems() {return(shown.size());}

        protected void drawbg(GOut g) {
            g.chcolor(NStyle.infoBg);
            g.frect(Coord.z, sz);
            g.chcolor();
        }

        protected void drawitem(GOut g, Catalogue.Entry e, int i) {
            if((i & 1) != 0) {
                g.chcolor(NStyle.rowOdd);
                g.frect(Coord.z, g.sz());
                g.chcolor();
            }
            Coord ic = Coord.of(UI.scale(3), (rowh - icon) / 2);
            Tex t = icon(e);
            if(t != null)
                g.image(t, ic, Coord.of(icon, icon));
            else
                placeholder(g, ic, e);
            int x = ic.x + icon + UI.scale(6);
            Tex[] l = label(e);
            int top = (rowh - l[0].sz().y - l[1].sz().y) / 2;
            g.image(l[0], Coord.of(x, top));
            g.image(l[1], Coord.of(x, top + l[0].sz().y));
        }

        public void change(Catalogue.Entry e) {
            super.change(e);
            if(e != null && e.placeable())
                pick.accept(e);
        }
    }
}
