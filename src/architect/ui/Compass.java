package architect.ui;

import architect.PlannerView;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;

/**
 * Which way north, east, south and west lie on screen, however the camera is turned or tilted: each
 * direction is projected from the view's centre, so N points where the game's map has north (up on the
 * minimap). Clicking it turns the planning camera north-up.
 */
public class Compass extends Widget {
    public static final int SZ = UI.scale(76);
    private final PlannerView view;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12).aa(true);
    private final Tex[] letters = new Tex[4];
    /* world directions of N, E, S, W: north is -y, as on the game's map */
    private static final Coord2d[] DIRS = {Coord2d.of(0, -1), Coord2d.of(1, 0), Coord2d.of(0, 1), Coord2d.of(-1, 0)};

    public Compass(PlannerView view) {
        super(Coord.of(SZ, SZ));
        this.view = view;
        String[] l = {"N", "E", "S", "W"};
        for(int i = 0; i < 4; i++)
            letters[i] = Text.renderstroked(l[i], (i == 0) ? NStyle.border : Color.WHITE, Color.BLACK, fnd).tex();
        settip("Compass: north is up on the game's map. Click to turn the camera north-up.");
    }

    public void draw(GOut g) {
        Coord c = sz.div(2);
        int r = sz.x / 2 - UI.scale(2);
        g.chcolor(20, 27, 29, 170);
        g.fellipse(c, Coord.of(r, r));
        g.chcolor(233, 156, 84, 160);
        for(int i = 0; i < 48; i++) {
            double a0 = Math.PI * 2 * i / 48, a1 = Math.PI * 2 * (i + 1) / 48;
            g.line(c.add((int)(Math.cos(a0) * r), (int)(Math.sin(a0) * r)), c.add((int)(Math.cos(a1) * r), (int)(Math.sin(a1) * r)), 1);
        }
        g.chcolor();
        Coord3f o;
        try {
            Coord2d cc = view.cc;
            o = view.screenxf(cc);
            for(int i = 0; i < 4; i++) {
                Coord3f p = view.screenxf(cc.add(DIRS[i].mul(MCache.tilesz.x * 4)));
                double dx = p.x - o.x, dy = p.y - o.y, len = Math.hypot(dx, dy);
                if(len < 0.001)
                    continue;
                dx /= len;
                dy /= len;
                int lr = r - UI.scale(11);
                Coord tip = c.add((int)Math.round(dx * (lr - UI.scale(6))), (int)Math.round(dy * (lr - UI.scale(6))));
                if(i == 0) {
                    /* the north needle: an orange wedge */
                    Coord side = Coord.of((int)Math.round(-dy * UI.scale(5)), (int)Math.round(dx * UI.scale(5)));
                    g.chcolor(233, 156, 84, 255);
                    g.line(c.add(side), tip, 2);
                    g.line(c.sub(side), tip, 2);
                    g.line(c.add(side), c.sub(side), 2);
                } else if(i == 2) {
                    g.chcolor(143, 163, 164, 200);
                    g.line(c, tip, 2);
                }
                g.chcolor();
                Tex t = letters[i];
                Coord lc = c.add((int)Math.round(dx * lr), (int)Math.round(dy * lr));
                g.image(t, lc.sub(t.sz().div(2)));
            }
        } catch(Loading l) {
            /* map not ready yet */
        }
    }

    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1) {
            view.northup();
            return(true);
        }
        return(super.mousedown(ev));
    }
}
