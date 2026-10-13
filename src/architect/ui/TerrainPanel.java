package architect.ui;

import architect.TerrainTool;
import architect.res.Catalogue;
import architect.world.TerrainStore;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.*;
import java.util.function.Supplier;

/**
 * Floating panel for Terrain mode: tool (lit while chosen), brush size, how much soil the plan moves, and,
 * for painting, the ground to paint with search. Laid out from the real button and text heights.
 */
public class TerrainPanel extends Widget {
    public static final int W = UI.scale(360);
    static final Color TXT = new Color(223, 231, 230), DIM = new Color(143, 163, 164);
    static final int PAD = UI.scale(8), GAP = UI.scale(6);
    private final TerrainTool tool;
    private final TerrainStore store;
    private final Supplier<Catalogue> cat;
    private final TextEntry search;
    private Listbox<Catalogue.Entry> tiles = null;
    private List<Catalogue.Entry> shown = Collections.emptyList();
    private String lastq = null;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 11).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
    private final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true);
    private final Map<String, Tex> tex = new HashMap<>();
    private final Map<String, Tex> icons = new HashMap<>();
    private long[] soil = {0, 0};
    private int soilseq = -1;
    /* Layout rows (computed once): header text, brush label, soil text, and where the paint section starts. */
    private final int hdry, brushy, brushh, soily, painty;
    private final int rowh;

    public TerrainPanel(TerrainTool tool, TerrainStore store, Supplier<Catalogue> cat) {
        super(Coord.of(W, 10));
        this.tool = tool;
        this.store = store;
        this.cat = cat;
        int y = PAD;
        hdry = y;
        y += head.render("Terrain").sz().y + GAP;
        int bw = (W - PAD * 2 - GAP * 3) / 4, x = PAD, bh = 0;
        for(TerrainTool.Mode m : TerrainTool.Mode.values()) {
            ToolButton b = add(new ToolButton(bw, m.label, () -> tool.mode == m, () -> tool.mode = m), x, y);
            x += bw + GAP;
            bh = b.sz.y;
        }
        y += bh + GAP;
        brushy = y;
        Button minus = add(new Button(UI.scale(28), "-", false, () -> tool.radius = Math.max(0, tool.radius - 1)), PAD, y);
        add(new Button(UI.scale(28), "+", false, () -> tool.radius = Math.min(25, tool.radius + 1)), PAD + UI.scale(28) + UI.scale(90), y);
        add(new Button(UI.scale(80), "Done", false, () -> tool.on = false), W - PAD - UI.scale(80), y);
        brushh = minus.sz.y;
        y += brushh + GAP;
        soily = y;
        y += fnd.render("Ag").sz().y + GAP;
        painty = y;
        search = add(new TextEntry(W - PAD * 2, ""), PAD, painty);
        rowh = Math.max(UI.scale(24), fnd.render("Ag").sz().y + small.render("Ag").sz().y + UI.scale(4));
        resize(Coord.of(W, painty));
    }

    private Tex text(String s, Color c, Text.Foundry f) {
        if(tex.size() > 400)
            tex.clear();
        return(tex.computeIfAbsent(s + "\u0001" + c.getRGB() + "\u0001" + f.hashCode(), k -> f.render(s, c).tex()));
    }

    private Tex icon(String res) {
        if(icons.containsKey(res))
            return(icons.get(res));
        try {
            Resource.Image img = Resource.remote().load(res).get().layer(Resource.imgc);
            icons.put(res, (img == null) ? null : img.tex());
        } catch(Loading l) {
            return(null);
        } catch(Resource.LoadException | Resource.BadResourceException e) {
            icons.put(res, null);
        }
        return(icons.get(res));
    }

    public void tick(double dt) {
        boolean paint = tool.mode == TerrainTool.Mode.PAINT;
        Catalogue c = cat.get();
        if(paint && c != null && tiles == null) {
            int icon = rowh - UI.scale(4);
            tiles = add(new Listbox<Catalogue.Entry>(W - PAD * 2, 8, rowh) {
                protected Catalogue.Entry listitem(int i) {return(shown.get(i));}
                protected int listitems() {return(shown.size());}
                protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
                protected void drawitem(GOut g, Catalogue.Entry e, int i) {
                    if(e.res.equals(tool.paint)) {
                        g.chcolor(233, 156, 84, 60);
                        g.frect(Coord.z, g.sz());
                        g.chcolor();
                    }
                    Tex ic = icon(e.res);
                    if(ic != null)
                        g.image(ic, Coord.of(UI.scale(2), UI.scale(2)), Coord.of(icon, icon));
                    Tex n = text(e.name, TXT, fnd);
                    g.image(n, Coord.of(icon + UI.scale(8), UI.scale(1)));
                    g.image(text(e.category, DIM, small), Coord.of(icon + UI.scale(8), UI.scale(1) + n.sz().y));
                }
                public void change(Catalogue.Entry e) {
                    super.change(e);
                    if(e != null)
                        tool.paint = e.res;
                }
            }, PAD, painty + search.sz.y + UI.scale(4));
        }
        if(tiles != null) {
            String q = search.text();
            if(!q.equals(lastq)) {
                lastq = q;
                shown = c.search(c.tiles, q);
            }
            if(tiles.visible != paint)
                tiles.show(paint);
        }
        if(search.visible != paint)
            search.show(paint);
        int h = paint ? ((tiles != null) ? tiles.c.y + tiles.sz.y + PAD : painty + search.sz.y + PAD) : painty;
        if(sz.y != h)
            resize(Coord.of(W, h));
        if(store.seq != soilseq) {
            soilseq = store.seq;
            soil = store.soil();
        }
        super.tick(dt);
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.chcolor();
        Tex t = text("Terrain", NStyle.border, head);
        g.image(t, Coord.of(PAD, hdry));
        Tex hint = text(hint(), DIM, fnd);
        g.image(hint, Coord.of(sz.x - PAD - hint.sz().x, hdry + (t.sz().y - hint.sz().y) / 2));
        Tex b = text("Brush " + tool.radius, TXT, fnd);
        g.image(b, Coord.of(PAD + UI.scale(28) + (UI.scale(90) - b.sz().x) / 2, brushy + (brushh - b.sz().y) / 2));
        g.image(text(String.format("Soil vs. the original ground: dug %d, filled %d units", soil[0], soil[1]), TXT, fnd), Coord.of(PAD, soily));
        super.draw(g);
    }

    private String hint() {
        switch(tool.mode) {
        case RAISE: return("Click/drag: fill   Shift: dig   [ ]: size");
        case LEVEL: return("Drag a rectangle   Ctrl: to average");
        case SMOOTH: return("Drag to smooth   [ ]: size");
        default: return("Drag to paint   Alt+click: pick up");
        }
    }
}
