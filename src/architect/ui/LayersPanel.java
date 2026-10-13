package architect.ui;

import architect.edit.*;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.*;
import java.util.function.Supplier;

/**
 * Right-hand layer tree. Click a layer to make it active (new objects go there); click the eye or the lock
 * to hide or lock; click a folder's arrow to fold it. Buttons below manage layers and move the selection.
 */
public class LayersPanel extends Widget {
    public static final int W = UI.scale(240), ROWH = UI.scale(24);
    static final Color DIM = new Color(143, 163, 164), TXT = new Color(223, 231, 230), OFF = new Color(95, 105, 106);
    private final Plan plan;
    private final Layers layers;
    private final Selection sel;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
    private final Tex title;
    private final Map<String, Tex> names = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private int countseq = -1;
    private TextEntry rename = null;
    private String renaming = null;
    private final List<Button> buttons = new ArrayList<>();
    private int scroll = 0;

    public LayersPanel(Plan plan, Selection sel, int h) {
        super(Coord.of(W, h));
        this.plan = plan;
        this.layers = plan.layers;
        this.sel = sel;
        title = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 14).aa(true).render("Layers", NStyle.border).tex();
        int bw = (W - UI.scale(16)) / 3;
        button(bw, "+ Layer", () -> {
            Layers.Node cur = layers.get(layers.active());
            String folder = (cur != null) ? cur.parent : null;
            Layers.Node n = layers.addLayer(layers.uniqueName("Layer"), folder);
            layers.activate(n.id);
            startrename(n.id);
        });
        button(bw, "+ Folder", () -> {
            Layers.Node f = layers.addFolder(layers.uniqueName("Folder"));
            Layers.Node n = layers.addLayer(layers.uniqueName("Layer"), f.id);
            layers.activate(n.id);
            startrename(f.id);
        });
        button(bw, "Rename", () -> startrename(layers.active()));
        button(bw, "Up", () -> layers.shift(layers.active(), -1));
        button(bw, "Down", () -> layers.shift(layers.active(), 1));
        button(bw, "Delete", () -> {
            /* Deleting the last layer would leave nowhere to place; Layers.active() recreates one anyway. */
            plan.removelayer(layers.active());
        });
        button(W - UI.scale(12), "Move selection to this layer", () -> {
            List<PlanObject> objs = sel.objects();
            plan.begin("Move to layer");
            try {
                for(PlanObject o : objs)
                    plan.setlayer(o, layers.active());
            } finally {
                plan.commit();
            }
        });
        place();
    }

    private void button(int w, String text, Runnable act) {
        buttons.add(add(new Button(w, text, false, act)));
    }

    /** Buttons sit at the bottom, three per row, the last one full width. */
    private void place() {
        int bh = buttons.get(0).sz.y + UI.scale(4);
        int rows = (buttons.size() - 1 + 2) / 3 + 1;
        int y = sz.y - rows * bh - UI.scale(6);
        for(int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            if(i == buttons.size() - 1)
                b.c = Coord.of(UI.scale(6), y + ((i + 2) / 3) * bh);
            else
                b.c = Coord.of(UI.scale(6) + (i % 3) * (b.sz.x + UI.scale(2)), y + (i / 3) * bh);
        }
    }

    private int listtop() {return(UI.scale(30));}
    private int listh() {return(buttons.get(0).c.y - listtop() - UI.scale(6));}

    public void resize(Coord sz) {
        super.resize(sz);
        place();
    }

    private void startrename(String id) {
        Layers.Node n = layers.get(id);
        if(n == null)
            return;
        if(rename != null)
            rename.destroy();
        renaming = id;
        rename = add(new TextEntry(W - UI.scale(60), n.name) {
            public void activate(String text) {
                finishrename(text);
            }
        }, UI.scale(48), listtop());
        setfocus(rename);
    }

    private void finishrename(String text) {
        Layers.Node n = layers.get(renaming);
        if(n != null && !text.trim().isEmpty()) {
            n.name = text.trim();
            layers.changed();
        }
        if(rename != null)
            rename.destroy();
        rename = null;
        renaming = null;
    }

    private void recount() {
        if(plan.seq == countseq)
            return;
        countseq = plan.seq;
        counts.clear();
        for(PlanObject o : plan.objects())
            counts.merge(o.layer, 1, Integer::sum);
    }

    private Tex name(Layers.Node n, boolean active) {
        int c = n.folder ? 0 : counts.getOrDefault(n.id, 0);
        String key = n.id + "\u0001" + n.name + "\u0001" + active + "\u0001" + c;
        return(names.computeIfAbsent(key, k -> fnd.render(n.folder ? n.name : n.name + "  (" + c + ")",
                                                          active ? NStyle.border : (n.folder ? DIM : TXT)).tex()));
    }

    public void draw(GOut g) {
        recount();
        if(names.size() > 500)
            names.clear();
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.line(Coord.z, Coord.of(0, sz.y), 1);
        g.chcolor();
        g.image(title, Coord.of(UI.scale(8), UI.scale(6)));
        List<Layers.Node> rows = layers.rows();
        String active = layers.active();
        int top = listtop(), vis = Math.max(1, listh() / ROWH);
        scroll = Math.max(0, Math.min(scroll, rows.size() - vis));
        GOut lg = g.reclip(Coord.of(0, top), Coord.of(sz.x, listh()));
        for(int i = scroll; i < rows.size() && (i - scroll) < vis; i++) {
            Layers.Node n = rows.get(i);
            int y = (i - scroll) * ROWH;
            if(((i & 1) != 0)) {
                lg.chcolor(NStyle.rowOdd);
                lg.frect(Coord.of(UI.scale(4), y), Coord.of(sz.x - UI.scale(8), ROWH));
            }
            if(n.id.equals(active)) {
                lg.chcolor(233, 156, 84, 50);
                lg.frect(Coord.of(UI.scale(4), y), Coord.of(sz.x - UI.scale(8), ROWH));
            }
            int cy = y + ROWH / 2;
            /* eye: open (outline and pupil) when visible, a struck-through outline when hidden */
            Coord ec = Coord.of(UI.scale(14), cy);
            lg.chcolor(n.visible ? TXT : OFF);
            lg.fellipse(ec, Coord.of(UI.scale(7), UI.scale(5)));
            lg.chcolor(NStyle.windowBg);
            lg.fellipse(ec, Coord.of(UI.scale(6), UI.scale(4)));
            if(n.visible) {
                lg.chcolor(TXT);
                lg.fellipse(ec, Coord.of(UI.scale(3), UI.scale(3)));
            } else {
                lg.chcolor(OFF);
                lg.line(ec.add(-UI.scale(7), UI.scale(5)), ec.add(UI.scale(7), -UI.scale(5)), 2);
            }
            /* lock: a closed orange padlock when locked, a faint open one when not */
            int lx = UI.scale(28);
            lg.chcolor(n.locked ? NStyle.border : OFF);
            lg.frect(Coord.of(lx, cy - UI.scale(1)), Coord.of(UI.scale(11), UI.scale(8)));
            if(n.locked) {
                lg.rect(Coord.of(lx + UI.scale(2), cy - UI.scale(7)), Coord.of(UI.scale(7), UI.scale(7)));
            } else {
                lg.rect(Coord.of(lx + UI.scale(6), cy - UI.scale(8)), Coord.of(UI.scale(7), UI.scale(7)));
            }
            lg.chcolor();
            int x = UI.scale(48) + ((n.parent != null) ? UI.scale(14) : 0);
            if(n.folder) {
                lg.chcolor(DIM);
                Coord t = Coord.of(x, cy);
                if(n.open) {
                    lg.line(t.add(0, -2), t.add(4, 2), 2);
                    lg.line(t.add(4, 2), t.add(8, -2), 2);
                } else {
                    lg.line(t.add(2, -4), t.add(6, 0), 2);
                    lg.line(t.add(6, 0), t.add(2, 4), 2);
                }
                lg.chcolor();
                x += UI.scale(12);
            }
            if(n.id.equals(renaming))
                continue;
            Tex t = name(n, n.id.equals(active));
            lg.image(t, Coord.of(x, cy - t.sz().y / 2));
        }
        if(rename != null) {
            int i = rows.indexOf(layers.get(renaming));
            if(i >= scroll)
                rename.c = Coord.of(rename.c.x, top + (i - scroll) * ROWH + (ROWH - rename.sz.y) / 2);
        }
        super.draw(g);
    }

    public boolean mousedown(MouseDownEvent ev) {
        /* Children (buttons, text entries) first: the engine asks this panel before them, and answering
         * true here would swallow every click meant for a button. */
        if(ev.propagate(this) || super.mousedown(ev))
            return(true);
        int top = listtop();
        if(ev.b != 1 || ev.c.y < top || ev.c.y >= top + listh())
            return(true);
        List<Layers.Node> rows = layers.rows();
        int i = scroll + (ev.c.y - top) / ROWH;
        if(i < 0 || i >= rows.size())
            return(true);
        Layers.Node n = rows.get(i);
        if(ev.c.x < UI.scale(25)) {
            n.visible = !n.visible;
            layers.changed();
        } else if(ev.c.x < UI.scale(42)) {
            n.locked = !n.locked;
            layers.changed();
        } else if(n.folder) {
            n.open = !n.open;
            layers.changed();
        } else {
            layers.activate(n.id);
        }
        return(true);
    }

    public boolean mousewheel(MouseWheelEvent ev) {
        scroll += ev.a;
        return(true);
    }
}
