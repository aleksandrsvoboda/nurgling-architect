package architect.ui;

import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.*;
import java.util.List;
import java.util.function.*;

/**
 * A searchable list to pick one thing from: icon, name and a second line per row, a search box that filters
 * as you type, Cancel, and optionally a button that takes the typed text itself ("Other item").
 */
public class ListPicker<T> extends Widget {
    static final int PAD = UI.scale(8), GAP = UI.scale(5);
    static final Color TXT = new Color(223, 231, 230), DIM = new Color(143, 163, 164);
    private final List<T> all;
    private List<T> shown;
    private final Function<T, String> name, sub, key;
    private final Function<T, Tex> icon;
    private final Consumer<T> pick;
    private final TextEntry search;
    private final Listbox<T> lb;
    private final Tex title;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
    private final Map<String, Tex> tex = new HashMap<>();
    private String lastq = null;

    /**
     * key is what the search matches (name and second line if null); other, if given, gets the typed text
     * from a "Use as typed" button.
     */
    public ListPicker(String title, int w, int rows, List<T> items, Function<T, String> name, Function<T, String> sub,
                      Function<T, Tex> icon, Function<T, String> key, Consumer<T> pick, Consumer<String> other) {
        super(Coord.z);
        this.all = items;
        this.shown = items;
        this.name = name;
        this.sub = sub;
        this.icon = icon;
        this.key = (key != null) ? key : t -> name.apply(t) + " " + ((sub == null) ? "" : sub.apply(t));
        this.pick = pick;
        this.title = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true).render(title, NStyle.border).tex();
        int y = PAD + this.title.sz().y + GAP;
        search = add(new TextEntry(w - PAD * 2, ""), PAD, y);
        y += search.sz.y + GAP;
        int rowh = Math.max(UI.scale(28), fnd.render("Ag").sz().y + ((sub != null) ? small.render("Ag").sz().y : 0) + UI.scale(6));
        lb = add(new Listbox<T>(w - PAD * 2, rows, rowh) {
            protected T listitem(int i) {return(shown.get(i));}
            protected int listitems() {return(shown.size());}
            protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
            protected void drawitem(GOut g, T it, int i) {
                if((i & 1) != 0) {
                    g.chcolor(NStyle.rowOdd);
                    g.frect(Coord.z, g.sz());
                    g.chcolor();
                }
                int x = UI.scale(4), isz = rowh - UI.scale(6);
                Tex ic = (icon == null) ? null : icon.apply(it);
                if(icon != null) {
                    if(ic != null)
                        g.image(ic, Coord.of(x, UI.scale(3)), Coord.of(isz, isz));
                    x += isz + UI.scale(6);
                }
                Tex n = text(name.apply(it), TXT, fnd);
                g.image(n, Coord.of(x, UI.scale(2)));
                if(sub != null) {
                    String s = sub.apply(it);
                    if(s != null && !s.isEmpty())
                        g.image(text(s, DIM, small), Coord.of(x, UI.scale(2) + n.sz().y));
                }
            }
            public void change(T it) {
                super.change(it);
                if(it != null) {
                    ListPicker.this.pick.accept(it);
                    ListPicker.this.destroy();
                }
            }
        }, PAD, y);
        y += lb.sz.y + GAP;
        int bw = UI.scale(110);
        add(new Button(bw, "Cancel", false, this::destroy), w - PAD - bw, y);
        if(other != null)
            add(new Button(UI.scale(150), "Use as typed", false, () -> {
                String t = search.text().trim();
                if(!t.isEmpty()) {
                    other.accept(t);
                    destroy();
                }
            }), PAD, y);
        resize(Coord.of(w, y + UI.scale(28) + PAD));
    }

    protected void added() {
        super.added();
        setfocus(search);
    }

    private Tex text(String s, Color c, Text.Foundry f) {
        if(tex.size() > 800)
            tex.clear();
        return(tex.computeIfAbsent(s + "\u0001" + c.getRGB() + "\u0001" + System.identityHashCode(f), k -> f.render(s, c).tex()));
    }

    public void tick(double dt) {
        String q = search.text().trim().toLowerCase();
        if(!q.equals(lastq)) {
            lastq = q;
            if(q.isEmpty()) {
                shown = all;
            } else {
                List<T> r = new ArrayList<>();
                for(T t : all)
                    if(key.apply(t).toLowerCase().contains(q))
                        r.add(t);
                shown = r;
            }
            lb.sb.val = 0;
        }
        super.tick(dt);
    }

    public boolean keydown(KeyDownEvent ev) {
        if(ev.code == java.awt.event.KeyEvent.VK_ESCAPE) {
            destroy();
            return(true);
        }
        if(ev.code == java.awt.event.KeyEvent.VK_ENTER && shown.size() == 1) {
            pick.accept(shown.get(0));
            destroy();
            return(true);
        }
        return(super.keydown(ev));
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.rect(Coord.of(1, 1), sz.sub(2, 2));
        g.chcolor();
        g.image(title, Coord.of(PAD, PAD));
        super.draw(g);
    }
}
