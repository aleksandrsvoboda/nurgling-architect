package architect.ui;

import architect.edit.Walls;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shown while placing a wall or fence: switches between laying wall (posts and sections) and setting gates,
 * the way the game builds them, with a line on how each works.
 */
public class WallBar extends Widget {
    static final int PAD = UI.scale(8), GAP = UI.scale(6);
    static final Color DIM = new Color(143, 163, 164);
    private static final Walls.Piece[] MODES = {Walls.Piece.POST, Walls.Piece.GATE, Walls.Piece.BIGGATE};
    private final Supplier<String> placing;
    private final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true);
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 11).aa(true);
    private Tex title, hint;
    private String lastres = null;
    private final int btny, hinty;

    public WallBar(Supplier<String> placing, Consumer<String> place) {
        super(Coord.z);
        this.placing = placing;
        int y = PAD + head.render("Ag").sz().y + GAP, x = PAD, h = 0;
        btny = y;
        for(Walls.Piece m : MODES) {
            ToolButton b = add(new ToolButton(UI.scale(96), m.label, () -> mode() == m, () -> {
                Walls.Kit k = Walls.kit(placing.get());
                if(k != null)
                    place.accept(k.res(m));
            }), x, y);
            x += b.sz.x + GAP;
            h = b.sz.y;
        }
        hinty = y + h + GAP;
        resize(Coord.of(Math.max(x - GAP + PAD, UI.scale(520)), hinty + fnd.render("Ag").sz().y + PAD));
    }

    /** The bar's mode for the piece being placed: a lone section counts as laying wall. */
    private Walls.Piece mode() {
        String r = placing.get();
        Walls.Kit k = Walls.kit(r);
        if(k == null)
            return(null);
        Walls.Piece p = k.piece(r);
        return(p.gate() ? p : Walls.Piece.POST);
    }

    public void tick(double dt) {
        String r = placing.get();
        if(r != null && !r.equals(lastres) && Walls.kit(r) != null) {
            lastres = r;
            Walls.Kit k = Walls.kit(r);
            title = head.render(k.name, NStyle.border).tex();
            hint = fnd.render(mode().gate()
                              ? "Hover a wall to set the gate into it, or drag from a post to lead a gate off it."
                              : "Click: a post.  Drag: a wall from post to post, straight or diagonal.", DIM).tex();
        }
        super.tick(dt);
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.chcolor();
        if(title != null)
            g.image(title, Coord.of(PAD, PAD));
        if(hint != null)
            g.image(hint, Coord.of(PAD, hinty));
        super.draw(g);
    }
}
