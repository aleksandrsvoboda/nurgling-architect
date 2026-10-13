package architect.ui;

import architect.TerrainTool;
import architect.res.Catalogue;
import architect.world.TerrainStore;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.*;
import java.util.function.Supplier;

/** Floating panel for Terrain mode: tool, brush size, ground to paint, and how much soil the plan moves. */
public class TerrainPanel extends Widget {
    public static final int W = UI.scale(340);
    static final Color TXT = new Color(223, 231, 230), DIM = new Color(143, 163, 164);
    private final TerrainTool tool;
    private final TerrainStore store;
    private final Supplier<Catalogue> cat;
    private final Map<TerrainTool.Mode, Button> modes = new EnumMap<>(TerrainTool.Mode.class);
    private final TextEntry search;
    private Listbox<Catalogue.Entry> tiles = null;
    private List<Catalogue.Entry> shown = Collections.emptyList();
    private String lastq = null;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 11).aa(true);
    private final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true);
    private final Map<String, Tex> tex = new HashMap<>();
    private final Map<String, Tex> icons = new HashMap<>();
    private long[] soil = {0, 0};
    private int soilseq = -1;
    static final int LISTY = 132;

    public TerrainPanel(TerrainTool tool, TerrainStore store, Supplier<Catalogue> cat) {
        super(Coord.of(W, UI.scale(LISTY)));
        this.tool = tool;
        this.store = store;
        this.cat = cat;
        int bw = (W - UI.scale(28)) / 4, x = UI.scale(8);
        for(TerrainTool.Mode m : TerrainTool.Mode.values()) {
            modes.put(m, add(new Button(bw, m.label, false, () -> tool.mode = m), x, UI.scale(28)));
            x += bw + UI.scale(4);
        }
        add(new Button(UI.scale(26), "-", false, () -> tool.radius = Math.max(0, tool.radius - 1)), UI.scale(8), UI.scale(62));
        add(new Button(UI.scale(26), "+", false, () -> tool.radius = Math.min(25, tool.radius + 1)), UI.scale(130), UI.scale(62));
        add(new Button(UI.scale(80), "Done", false, () -> tool.on = false), W - UI.scale(88), UI.scale(62));
        search = add(new TextEntry(W - UI.scale(16), ""), UI.scale(8), UI.scale(LISTY - 26));
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
            tiles = add(new Listbox<Catalogue.Entry>(W - UI.scale(16), 9, UI.scale(26)) {
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
                        g.image(ic, Coord.of(UI.scale(3), UI.scale(2)), Coord.of(UI.scale(22), UI.scale(22)));
                    g.image(text(e.name, TXT, fnd), Coord.of(UI.scale(30), UI.scale(2)));
                    g.image(text(e.category, DIM, fnd), Coord.of(UI.scale(30), UI.scale(13)));
                }
                public void change(Catalogue.Entry e) {
                    super.change(e);
                    if(e != null)
                        tool.paint = e.res;
                }
            }, UI.scale(8), UI.scale(LISTY + 4));
        }
        if(tiles != null) {
            String q = search.text();
            if(!q.equals(lastq)) {
                lastq = q;
                shown = c.search(c.tiles, q);
            }
            if(tiles.visible != paint) {
                tiles.show(paint);
                search.show(paint);
            }
        } else if(search.visible != paint) {
            search.show(paint);
        }
        int h = paint ? ((tiles != null) ? tiles.c.y + tiles.sz.y + UI.scale(8) : UI.scale(LISTY + 4)) : UI.scale(LISTY - 30);
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
        g.image(text("Terrain", NStyle.border, head), Coord.of(UI.scale(8), UI.scale(5)));
        g.image(text(hint(), DIM, fnd), Coord.of(UI.scale(80), UI.scale(8)));
        g.image(text("Brush " + tool.radius, TXT, fnd), Coord.of(UI.scale(42), UI.scale(66)));
        g.image(text(String.format("Soil vs your map: dug %d, filled %d units", soil[0], soil[1]), TXT, fnd), Coord.of(UI.scale(8), UI.scale(92)));
        super.draw(g);
        Button on = modes.get(tool.mode);
        g.chcolor(NStyle.border);
        g.rect(on.c, on.sz);
        g.chcolor();
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
