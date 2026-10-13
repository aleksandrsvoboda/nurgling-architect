package architect.ui;

import architect.PlannerView;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;

/**
 * Shown while objects are selected: what can be done to them, as buttons (the keys still work). Mirroring
 * flips positions and facings across the middle of the selection; there are no mirrored models, so an
 * object that isn't symmetric comes out turned.
 */
public class SelectionBar extends Widget {
    static final int PAD = UI.scale(6), GAP = UI.scale(4);
    private final PlannerView view;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12).aa(true);
    private Tex count = null;
    private int shown = -1;
    private final int labelw;

    public SelectionBar(PlannerView view, Runnable saveblueprint) {
        super(Coord.z);
        this.view = view;
        labelw = UI.scale(100);
        int x = PAD + labelw, y = PAD, h = 0;
        Object[][] bs = {
            {"Turn ↺", "Shift+R: a quarter turn back", (Runnable)() -> view.edit.turnsel(true)},
            {"Turn ↻", "R: a quarter turn", (Runnable)() -> view.edit.turnsel(false)},
            {"Mirror ↔", "F: mirror east-west across the middle of the selection", (Runnable)() -> view.edit.mirror(false)},
            {"Mirror ↕", "Shift+F: mirror north-south across the middle of the selection", (Runnable)() -> view.edit.mirror(true)},
            {"Duplicate", "Ctrl+D", (Runnable)view.edit::duplicate},
            {"Delete", "Del", (Runnable)view.edit::deletesel},
            {"Save as blueprint", "A blueprint preset the game can place too", saveblueprint},
        };
        for(Object[] b : bs) {
            ToolButton btn = add(new ToolButton((String)b[0], (Runnable)b[2]), x, y);
            btn.settip((String)b[1]);
            x += btn.sz.x + GAP;
            h = btn.sz.y;
        }
        resize(Coord.of(x - GAP + PAD, h + PAD * 2));
    }

    public void tick(double dt) {
        int n = view.sel.size();
        if(n != shown) {
            shown = n;
            count = fnd.render(n + " selected", NStyle.border).tex();
        }
        super.tick(dt);
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.chcolor();
        if(count != null)
            g.image(count, Coord.of(PAD + UI.scale(4), (sz.y - count.sz().y) / 2));
        super.draw(g);
    }
}
