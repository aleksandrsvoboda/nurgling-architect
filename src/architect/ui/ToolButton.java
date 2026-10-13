package architect.ui;

import haven.*;
import nurgling.NStyle;

import java.awt.Font;
import java.util.function.BooleanSupplier;

/**
 * A button sized to its label, optionally a toggle: while its state is on it is lit in the nurgling orange
 * (tinted fill and an orange frame), so modes and switches show at a glance whether they are active.
 */
public class ToolButton extends Button {
    /* Same face as Button's own label font (Button.tf is package-private), for measuring. */
    private static final Text.Foundry measure = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(12f))).aa(true);
    private final BooleanSupplier on;
    /** Switches sized from their label carry a lamp; fixed-width mode buttons and tabs only light up. */
    private boolean lamp = false;

    public static int width(String text) {
        return(measure.render(text).sz().x + Button.margin * 2 + UI.scale(6));
    }

    public ToolButton(String text, Runnable action) {
        this(text, null, action);
    }

    public ToolButton(String text, BooleanSupplier on, Runnable action) {
        this(width(text) + ((on != null) ? LAMP : 0), text, on, action);
        this.lamp = (on != null);
    }

    /* Room for the lamp a toggle shows left of its label. */
    private static final int LAMP = UI.scale(18);

    public ToolButton(int w, String text, BooleanSupplier on, Runnable action) {
        super(w, text, false, action);
        this.on = on;
    }

    public boolean lit() {
        return((on != null) && on.getAsBoolean());
    }

    public void draw(GOut g) {
        super.draw(g);
        if(lamp) {
            /* The lamp: a lit orange dot while on, a hollow one while off, so a switch reads on/off even
             * under the mouse, where the hover plate looks much like the lit one. */
            int d = UI.scale(8);
            Coord lc = Coord.of(Button.margin - UI.scale(2), (sz.y - d) / 2);
            if(lit()) {
                g.chcolor(255, 170, 80, 255);
                g.fellipse(lc.add(d / 2, d / 2), Coord.of(d / 2, d / 2));
            } else {
                g.chcolor(110, 125, 126, 255);
                g.fellipse(lc.add(d / 2, d / 2), Coord.of(d / 2, d / 2));
                g.chcolor(30, 36, 37, 255);
                g.fellipse(lc.add(d / 2, d / 2), Coord.of(d / 2 - UI.scale(1), d / 2 - UI.scale(1)));
            }
            g.chcolor();
        }
        if(lit()) {
            g.chcolor(233, 156, 84, 55);
            g.frect(Coord.z, sz);
            g.chcolor(NStyle.border);
            g.rect(Coord.z, sz);
            g.rect(Coord.of(1, 1), sz.sub(2, 2));
            g.chcolor();
        }
    }
}
