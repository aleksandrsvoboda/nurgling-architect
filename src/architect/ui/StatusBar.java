package architect.ui;

import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.function.Supplier;

/** Bottom strip: world, cursor/plan facts and context help, re-rendered only when the text changes. */
public class StatusBar extends Widget {
    public static final int H = UI.scale(22);
    private final Supplier<String> left, right;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private String lt, rt;
    private Tex ltex, rtex;

    public StatusBar(int w, Supplier<String> left, Supplier<String> right) {
        super(Coord.of(w, H));
        this.left = left;
        this.right = right;
    }

    public void draw(GOut g) {
        String l = left.get(), r = right.get();
        if(!l.equals(lt)) {
            lt = l;
            ltex = fnd.render(l, new Color(223, 231, 230)).tex();
        }
        if(!r.equals(rt)) {
            rt = r;
            rtex = fnd.render(r, NStyle.border).tex();
        }
        g.chcolor(NStyle.titleBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.line(Coord.z, Coord.of(sz.x, 0), 1);
        g.chcolor();
        int y = (sz.y - ltex.sz().y) / 2;
        g.image(ltex, Coord.of(UI.scale(8), y));
        g.image(rtex, Coord.of(sz.x - rtex.sz().x - UI.scale(8), y));
    }
}
