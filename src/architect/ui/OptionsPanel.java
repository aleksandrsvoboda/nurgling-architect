package architect.ui;

import architect.PlannerView;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;

/**
 * Planner options, from the toolbar's Options button. The camera inversions apply to the planning cameras
 * (orbit and top-down); play mode keeps the game's own controls. The placement granularity is the game's
 * own pair of sliders: Shift's grid (steps per tile, or free) and Shift+wheel's turn. Saved in the planner's
 * preferences.
 */
public class OptionsPanel extends Widget {
    static final int PAD = UI.scale(8), GAP = UI.scale(5);
    static final Color TXT = new Color(223, 231, 230), DIM = new Color(143, 163, 164);
    /* The game's angle steps: a full turn in this many. */
    static final int[] ANGLES = {4, 5, 6, 8, 9, 10, 12, 15, 18, 20, 24, 30, 36, 40, 45, 60, 72, 90, 120, 180, 360};
    /* The position slider's end, standing for "free" (plobpgran 0). */
    static final int FREE = 17;
    private final Tex title, hint;

    public OptionsPanel() {
        super(Coord.z);
        Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
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

        Label gran = add(new Label("Object fine-placement granularity", fnd), PAD, y);
        gran.setcolor(TXT);
        gran.settip("As in the game's options: what Shift snaps to while placing, moving and pasting, and how far Shift+wheel turns");
        y += gran.sz.y + UI.scale(4);
        Label pos = new Label("Position", fnd), ang = new Label("Angle", fnd);
        pos.setcolor(TXT);
        ang.setcolor(TXT);
        int sx = PAD + UI.scale(5) + Math.max(pos.sz.x, ang.sz.x) + GAP, sw = UI.scale(150);
        Label posv = new Label("", fnd), angv = new Label("", fnd);
        posv.setcolor(NStyle.border);
        angv.setcolor(NStyle.border);
        int ival = (int)Math.round(MapView.plobpgran);
        HSlider ps = new HSlider(sw, 2, FREE, (ival <= 0) ? FREE : Utils.clip(ival, 2, FREE - 1)) {
            protected void added() {
                dpy();
            }

            void dpy() {
                posv.settext((this.val == FREE) ? "∞" : Integer.toString(this.val));
            }

            public void changed() {
                Utils.setprefd("plobpgran", MapView.plobpgran = (this.val == FREE) ? 0 : this.val);
                dpy();
            }
        };
        ps.settip("Shift's grid: steps per tile; ∞ places freely");
        y = row(pos, ps, posv, sx, y);
        int aval = 0;
        for(int i = 0; i < ANGLES.length; i++)
            if(Math.abs((MapView.plobagran * 2) - ANGLES[i]) < Math.abs((MapView.plobagran * 2) - ANGLES[aval]))
                aval = i;
        HSlider as = new HSlider(sw, 0, ANGLES.length - 1, aval) {
            protected void added() {
                dpy();
            }

            void dpy() {
                angv.settext(String.format("%d°", 360 / ANGLES[this.val]));
            }

            public void changed() {
                Utils.setprefd("plobagran", MapView.plobagran = ANGLES[this.val] / 2.0);
                dpy();
            }
        };
        as.settip("How far each Shift+wheel notch turns");
        y = row(ang, as, angv, sx, y);
        y += GAP * 2;

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
        /* the slider rows end in a value up to "360°" wide */
        int rows = sx + sw + GAP + UI.scale(30) + PAD;
        int w = Math.max(Math.max(Math.max(v.sz.x, h.sz.x), Math.max(bb.sz.x, gw.sz.x)), Math.max(hint.sz().x, gran.sz.x)) + PAD * 2;
        w = Math.max(w, rows);
        resize(Coord.of(w, y + hint.sz().y + PAD));
    }

    /** One slider row: its name, the slider, and the value it shows; returns the next row's y. */
    private int row(Label name, HSlider s, Label val, int sx, int y) {
        int rh = Math.max(s.sz.y, name.sz.y);
        add(name, PAD + UI.scale(5), y + (rh - name.sz.y) / 2);
        add(s, sx, y + (rh - s.sz.y) / 2);
        add(val, sx + s.sz.x + GAP, y + (rh - name.sz.y) / 2);
        return(y + rh + UI.scale(2));
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
