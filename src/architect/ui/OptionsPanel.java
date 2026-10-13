package architect.ui;

import architect.PlannerView;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;

/**
 * Planner options, from the toolbar's Options button. The camera inversions apply to the planning cameras
 * (orbit and top-down); play mode keeps the game's own controls. Saved in the planner's preferences.
 */
public class OptionsPanel extends Widget {
    static final int PAD = UI.scale(8), GAP = UI.scale(5);
    static final Color DIM = new Color(143, 163, 164);
    private final Tex title, hint;

    public OptionsPanel() {
        super(Coord.z);
        title = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true).render("Options", NStyle.border).tex();
        hint = new Text.Foundry(Text.sans, 10).aa(true).render("Camera inversions: planning cameras only; play mode is unchanged", DIM).tex();
        int y = PAD + title.sz().y + GAP;
        ToolButton v = add(new ToolButton("Invert vertical camera", () -> PlannerView.inverty, () -> {
            PlannerView.inverty = !PlannerView.inverty;
            Utils.setprefb(PlannerView.PREF_INVERTY, PlannerView.inverty);
        }), PAD, y);
        y += v.sz.y + GAP;
        ToolButton h = add(new ToolButton("Invert horizontal camera", () -> PlannerView.invertx, () -> {
            PlannerView.invertx = !PlannerView.invertx;
            Utils.setprefb(PlannerView.PREF_INVERTX, PlannerView.invertx);
        }), PAD, y);
        y += h.sz.y + GAP * 3;
        ToolButton bb = add(new ToolButton("Bounding boxes  (Ctrl+B)", () -> (Boolean)nurgling.NConfig.get(nurgling.NConfig.Key.showBB), () -> {
            boolean on = !(Boolean)nurgling.NConfig.get(nurgling.NConfig.Key.showBB);
            nurgling.NConfig.set(nurgling.NConfig.Key.showBB, on);
            Utils.setprefb(PlannerView.PREF_BB, on);
        }), PAD, y);
        y += bb.sz.y + GAP;
        ToolButton gw = add(new ToolButton("Grid boundaries", () -> PlannerView.gridwalls, () -> {
            PlannerView.gridwalls = !PlannerView.gridwalls;
            Utils.setprefb(PlannerView.PREF_GRIDWALLS, PlannerView.gridwalls);
        }), PAD, y);
        gw.settip("Walls on the edges of the game's map grids (100 x 100 tiles), as nurgling draws them");
        y += gw.sz.y + GAP;
        int w = Math.max(Math.max(Math.max(v.sz.x, h.sz.x), Math.max(bb.sz.x, gw.sz.x)), hint.sz().x) + PAD * 2;
        resize(Coord.of(w, y + hint.sz().y + PAD));
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.chcolor();
        g.image(title, Coord.of(PAD, PAD));
        g.image(hint, Coord.of(PAD, sz.y - PAD - hint.sz().y));
        super.draw(g);
    }
}
