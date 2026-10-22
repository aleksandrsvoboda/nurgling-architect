package architect.ui;

import architect.MeasureTool;
import haven.*;
import nurgling.NStyle;
import nurgling.widgets.NColorWidget;

import java.awt.Color;
import java.util.*;
import java.util.function.Function;

/**
 * The Measure window, from the toolbar's Measure button: the game's Zone Measure Tool. Select area, clear one
 * zone or all of them, the colours new zones get, what the tool did last, and the planned objects on the most
 * recent zone by type.
 */
public class MeasurePanel extends Widget {
    public static final int W = UI.scale(220);
    static final int PAD = UI.scale(8), GAP = UI.scale(5);
    static final Color TXT = new Color(223, 231, 230), DIM = new Color(143, 163, 164);
    private final MeasureTool tool;
    private final Function<String, String> names;
    private final NColorWidget fillw, edgew;
    private final Listbox<Map.Entry<String, Integer>> list;
    private List<Map.Entry<String, Integer>> rows = Collections.emptyList();
    private final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true);
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 11).aa(true);
    private final Map<String, Tex> tex = new HashMap<>();
    private final Map<String, String> named = new HashMap<>();
    private final int coloury, statusy, totaly;

    /** names: an object's display name from its resource. */
    public MeasurePanel(MeasureTool tool, Function<String, String> names) {
        super(Coord.of(W, 10));
        this.tool = tool;
        this.names = names;
        int bw = W - PAD * 2, y = PAD + head.render("Ag").sz().y + GAP;
        ToolButton b;
        b = add(new ToolButton(bw, "Select area", () -> tool.armed() == MeasureTool.Mode.SELECT, tool::armselect), PAD, y);
        y += b.sz.y + GAP;
        b = add(new ToolButton(bw, "Clear selection", () -> tool.armed() == MeasureTool.Mode.CLEAR, tool::armclear), PAD, y);
        y += b.sz.y + GAP;
        b = add(new ToolButton(bw, "Clear all", null, tool::clearall), PAD, y);
        y += b.sz.y + GAP * 2;
        coloury = y;
        y += fnd.render("Ag").sz().y + UI.scale(2);
        fillw = add(new NColorWidget("Fill"), PAD, y);
        fillw.color = tool.fill;
        y += fillw.sz.y + UI.scale(2);
        edgew = add(new NColorWidget("Edge"), PAD, y);
        edgew.color = tool.edge;
        y += edgew.sz.y + GAP * 2;
        statusy = y;
        y += fnd.render("Ag").sz().y + GAP;
        totaly = y;
        y += fnd.render("Ag").sz().y + UI.scale(2);
        list = add(new Listbox<Map.Entry<String, Integer>>(bw, 8, UI.scale(16)) {
            protected Map.Entry<String, Integer> listitem(int i) {return(rows.get(i));}
            protected int listitems() {return(rows.size());}
            protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
            protected void drawitem(GOut g, Map.Entry<String, Integer> e, int i) {
                if((i & 1) != 0) {
                    g.chcolor(NStyle.rowOdd);
                    g.frect(Coord.z, g.sz());
                    g.chcolor();
                }
                Tex n = text(String.valueOf(e.getValue()), TXT, small);
                int ty = (g.sz().y - n.sz().y) / 2;
                g.image(n, Coord.of(g.sz().x - n.sz().x - UI.scale(4), ty));
                g.image(text(nameof(e.getKey()), TXT, small), Coord.of(UI.scale(4), ty));
            }
            /* read-only, as in the game */
            protected void itemclick(Map.Entry<String, Integer> e, int button) {}
        }, PAD, y);
        y += list.sz.y + PAD;
        resize(Coord.of(W, y));
    }

    private String nameof(String res) {
        return(named.computeIfAbsent(res, names));
    }

    private Tex text(String s, Color c, Text.Foundry f) {
        if(tex.size() > 300)
            tex.clear();
        return(tex.computeIfAbsent(s + "\u0001" + c.getRGB() + "\u0001" + f.hashCode(), k -> f.render(s, c).tex()));
    }

    public void tick(double dt) {
        /* new zones take the colours picked here */
        tool.fill = fillw.color;
        tool.edge = edgew.color;
        MeasureTool.Zone z = tool.latest();
        rows = (z == null) ? Collections.emptyList() : z.counts;
        super.tick(dt);
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.chcolor();
        g.image(text("Measure", NStyle.border, head), Coord.of(PAD, PAD));
        g.image(text("Colors:", TXT, fnd), Coord.of(PAD, coloury));
        g.image(text(tool.status, TXT, fnd), Coord.of(PAD, statusy));
        MeasureTool.Zone z = tool.latest();
        if(z != null)
            g.image(text(z.total + ((z.total == 1) ? " object" : " objects") + " in the latest zone", DIM, fnd), Coord.of(PAD, totaly));
        super.draw(g);
    }
}
