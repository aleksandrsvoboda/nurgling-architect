package architect.ui;

import architect.res.Catalogue;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.*;
import java.util.function.Consumer;

/**
 * Left-hand object browser: the game's build menu, or every object, with search. Clicking an entry starts
 * placing it. Entries without a model offline (never seen in game) are listed greyed out.
 */
public class CataloguePanel extends Widget {
    public static final int W = UI.scale(270), ROWH = UI.scale(26), ICON = UI.scale(22);
    static final Color DIM = new Color(143, 163, 164), OFF = new Color(95, 105, 106);
    private final Catalogue cat;
    private final Consumer<Catalogue.Entry> pick;
    private final TextEntry search;
    private final Button tbuild, tall;
    private boolean buildtab = true;
    private List<Catalogue.Entry> shown = Collections.emptyList();
    private EntryList list;
    private String lastq = null;
    private final Map<Catalogue.Entry, Tex[]> labels = new HashMap<>();
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
    private Tex title, footer;
    private int lastfooter = -1;

    public CataloguePanel(Catalogue cat, int h, Consumer<Catalogue.Entry> pick) {
        super(Coord.of(W, h));
        this.cat = cat;
        this.pick = pick;
        title = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 14).aa(true).render("Objects", NStyle.border).tex();
        int y = UI.scale(28);
        int bw = (W - UI.scale(18)) / 2;
        tbuild = add(new Button(bw, "Build menu", false, () -> tab(true)), UI.scale(6), y);
        tall = add(new Button(bw, "All objects", false, () -> tab(false)), UI.scale(12) + bw, y);
        y += tbuild.sz.y + UI.scale(6);
        search = add(new TextEntry(W - UI.scale(12), "") {
            protected void changed() {
                super.changed();
                refilter();
            }
        }, UI.scale(6), y);
        relayout();
    }

    private int listtop() {
        return(search.c.y + search.sz.y + UI.scale(6));
    }

    private void relayout() {
        int sb = 0;
        if(list != null) {
            sb = list.sb.val;
            list.destroy();
        }
        int rows = Math.max(1, (sz.y - listtop() - UI.scale(24)) / ROWH);
        list = add(new EntryList(W - UI.scale(12), rows), UI.scale(6), listtop());
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
        shown = cat.search(buildtab ? cat.build : cat.objects, search.text());
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
        g.image(title, Coord.of(UI.scale(8), UI.scale(6)));
        if(shown.size() != lastfooter) {
            lastfooter = shown.size();
            long ok = shown.stream().filter(Catalogue.Entry::placeable).count();
            footer = small.render(String.format("%d shown, %d placeable", shown.size(), ok), DIM).tex();
        }
        g.image(footer, Coord.of(UI.scale(8), sz.y - UI.scale(18)));
        super.draw(g);
        /* Mark the active tab. */
        Button on = buildtab ? tbuild : tall;
        g.chcolor(NStyle.border);
        g.rect(on.c, on.sz);
        g.chcolor();
    }

    private Tex[] label(Catalogue.Entry e) {
        return(labels.computeIfAbsent(e, k -> new Tex[] {
            fnd.render(k.name, k.placeable() ? Color.WHITE : OFF).tex(),
            small.render(k.placeable() ? k.category : k.category + " · not seen in game yet", k.placeable() ? DIM : OFF).tex(),
        }));
    }

    private final Map<String, Tex> icons = new HashMap<>();
    private Tex icon(Catalogue.Entry e) {
        if(e.pagina == null)
            return(null);
        Tex t = icons.get(e.pagina);
        if(t != null)
            return(t);
        try {
            Resource.Image img = Resource.remote().load(e.pagina).get().layer(Resource.imgc);
            if(img != null)
                icons.put(e.pagina, t = img.tex());
        } catch(Loading l) {
            /* still loading; drawn next frame */
        } catch(Resource.LoadException | Resource.BadResourceException ex) {
            icons.put(e.pagina, null);
        }
        return(t);
    }

    private class EntryList extends Listbox<Catalogue.Entry> {
        EntryList(int w, int rows) {
            super(w, rows, ROWH);
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
            Tex ic = icon(e);
            int x = UI.scale(3);
            if(ic != null)
                g.image(ic, Coord.of(x, (ROWH - ICON) / 2), Coord.of(ICON, ICON));
            x += ICON + UI.scale(5);
            Tex[] l = label(e);
            g.image(l[0], Coord.of(x, UI.scale(1)));
            g.image(l[1], Coord.of(x, ROWH - l[1].sz().y - UI.scale(1)));
        }

        public void change(Catalogue.Entry e) {
            super.change(e);
            if(e != null && e.placeable())
                pick.accept(e);
        }
    }
}
