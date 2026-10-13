package architect.ui;

import architect.PlannerView;
import architect.edit.PlanArea;
import architect.io.AreaPresets;
import haven.*;
import nurgling.areas.AreaPaths;
import org.json.JSONObject;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Area presets, in the game's own folder: yours (renamable, deletable) and the ones built into the game. Place
 * one (a stamp: wheel or R turns, F mirrors, click places it in a folder named after it), or save areas of the
 * plan as one: the selected areas, the open folder, all visible areas, or a dragged region.
 */
public class AreaPresetsPanel extends PresetsPanel<AreaPresets.Preset> {
    public AreaPresetsPanel(PlannerView view, Consumer<String> msg) {
        super(view, msg, AreaPresets.dir(), "Area presets", "Area preset");
    }

    protected List<AreaPresets.Preset> load() {
        List<AreaPresets.Preset> l = new ArrayList<>(AreaPresets.user(dir));
        l.addAll(AreaPresets.builtins());
        return(l);
    }

    protected String name(AreaPresets.Preset p) {return(p.name);}
    protected void name(AreaPresets.Preset p, String n) {p.name = n;}
    protected String description(AreaPresets.Preset p) {return(p.description);}
    protected void description(AreaPresets.Preset p, String t) {p.description = t;}
    protected boolean usable(AreaPresets.Preset p) {return(!p.foreign);}
    protected boolean mine(AreaPresets.Preset p) {return(!p.builtin);}
    protected String file(AreaPresets.Preset p) {return(p.file);}
    protected void write(AreaPresets.Preset p) throws IOException {AreaPresets.write(dir, p);}
    protected void remove(AreaPresets.Preset p) throws IOException {AreaPresets.delete(dir, p);}
    protected void export(AreaPresets.Preset p, Path to) throws IOException {AreaPresets.export(p, to);}
    protected AreaPresets.Preset importfile(Path src) throws IOException {return(AreaPresets.importFile(dir, src));}

    protected String summary(AreaPresets.Preset p) {
        int f = p.folders();
        return(String.format("%d × %d tiles · %d area%s%s · %s", p.w, p.h, p.count(), (p.count() == 1) ? "" : "s",
                             (f > 0) ? " in " + f + " folder" + ((f == 1) ? "" : "s") : "",
                             p.builtin ? ("Built in" + (p.category.isEmpty() ? "" : ", " + p.category)) : "Yours"));
    }

    protected void place(AreaPresets.Preset p) {
        view.areas.stamp(p);
        msg.accept("Placing " + p.name + ": wheel or R turns, F mirrors, click places, Esc cancels");
    }

    /** The areas as coloured rectangles, scaled into the square. */
    protected void thumb(GOut g, AreaPresets.Preset p, Coord at, int sz) {
        double span = Math.max(1, Math.max(p.w, p.h)), sc = (sz - UI.scale(8)) / span;
        Coord o = at.add(UI.scale(4) + (int)((span - p.w) * sc / 2), UI.scale(4) + (int)((span - p.h) * sc / 2));
        for(JSONObject a : p.areas) {
            JSONObject c = a.optJSONObject("color");
            Color col = (c == null) ? new Color(194, 194, 65) : new Color(c.optInt("r"), c.optInt("g"), c.optInt("b"));
            Coord ul = o.add((int)(a.getInt("x") * sc), (int)(a.getInt("y") * sc));
            Coord s = Coord.of(Math.max(1, (int)(a.getInt("w") * sc)), Math.max(1, (int)(a.getInt("h") * sc)));
            g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), 120);
            g.frect(ul, s);
            g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), 255);
            g.rect(ul, s);
        }
        g.chcolor();
    }

    protected void saverow(int x, int y, int bw) {
        ToolButton b;
        b = add(new ToolButton(bw, "Save selection", null, this::saveselection), x, y); x += bw + GAP;
        b.settip("The selected areas");
        b = add(new ToolButton(bw, "Save folder", null, this::savefolder), x, y); x += bw + GAP;
        b.settip("The Areas window's open folder, with everything in it");
        b = add(new ToolButton(bw, "Save visible", null, this::savevisible), x, y); x += bw + GAP;
        b.settip("Every area on a visible layer");
        b = add(new ToolButton(bw, "Save region", null, this::saveregion), x, y); x += bw + GAP;
        b.settip("Drag a rectangle on the ground: the areas whose middle is inside it");
        add(new ToolButton(bw, "Open folder", null, this::openfolder), x, y);
    }

    /* ---------- saving ---------- */

    private interface Capture {
        AreaPresets.Preset run(String name) throws AreaPresets.Empty;
    }

    private void save(String suggest, Capture c) {
        ask("Save as", suggest, name -> {
            try {
                AreaPresets.Preset p = c.run(name);
                AreaPresets.saveNew(dir, p);
                msg.accept(String.format("Saved %s (%d areas). In the game: Areas › Presets", p.name, p.count()));
                select(p.file);
            } catch(AreaPresets.Empty e) {
                msg.accept(e.getMessage());
            } catch(IOException e) {
                msg.accept("Could not save: " + e.getMessage());
            }
        });
    }

    public void saveselection() {
        List<PlanArea> s = view.areas.selected();
        if(s.isEmpty()) {
            msg.accept("Select some areas first (Areas mode: click, Shift+click)");
            return;
        }
        save((s.size() == 1) ? s.get(0).name : "", n -> AreaPresets.capture(plan, n, s, Collections.emptyList()));
    }

    private void savefolder() {
        String f = view.areas.folder;
        if(f.isEmpty()) {
            msg.accept("Open a folder in the Areas window first");
            return;
        }
        save(AreaPaths.name(f), n -> AreaPresets.capture(plan, n, Collections.emptyList(), Collections.singleton(f)));
    }

    private List<PlanArea> shownareas() {
        List<PlanArea> l = new ArrayList<>();
        for(PlanArea a : plan.areas())
            if(plan.layers.shown(a.layer))
                l.add(a);
        return(l);
    }

    private void savevisible() {
        List<PlanArea> l = shownareas();
        save("", n -> AreaPresets.capture(plan, n, l, Collections.emptyList()));
    }

    private void saveregion() {
        msg.accept("Drag a rectangle on the ground; the areas whose middle is inside it are saved");
        view.blueprints.armregion((ul, br) -> {
            List<PlanArea> l = new ArrayList<>();
            for(PlanArea a : shownareas()) {
                Coord2d m = Coord2d.of(a.tiles.ul.add(a.tiles.br)).div(2).mul(MCache.tilesz);
                if(m.x >= ul.x && m.y >= ul.y && m.x < br.x && m.y < br.y)
                    l.add(a);
            }
            save("", n -> AreaPresets.capture(plan, n, l, Collections.emptyList()));
        });
    }
}
