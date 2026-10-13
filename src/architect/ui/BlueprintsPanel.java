package architect.ui;

import architect.PlannerView;
import architect.edit.*;
import architect.io.Blueprints;
import haven.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Blueprints window: nurgling's Base planner presets, from the game's own folder. Place one into the plan,
 * save part of the plan as one (a layer or folder, a dragged region, the selection), rename, describe, delete,
 * export and import.
 */
public class BlueprintsPanel extends PresetsPanel<Blueprints.Preset> {
    public BlueprintsPanel(PlannerView view, Consumer<String> msg) {
        super(view, msg, Blueprints.dir(), "Blueprints", "Blueprint");
    }

    protected List<Blueprints.Preset> load() {return(Blueprints.all(dir));}
    protected String name(Blueprints.Preset p) {return(p.name);}
    protected void name(Blueprints.Preset p, String n) {p.name = n;}
    protected String description(Blueprints.Preset p) {return(p.description);}
    protected void description(Blueprints.Preset p, String t) {p.description = t;}
    protected boolean usable(Blueprints.Preset p) {return(!p.foreign);}
    protected boolean mine(Blueprints.Preset p) {return(true);}
    protected String file(Blueprints.Preset p) {return(p.file);}
    protected void write(Blueprints.Preset p) throws IOException {Blueprints.write(dir, p);}
    protected void remove(Blueprints.Preset p) throws IOException {Blueprints.delete(dir, p);}
    protected void export(Blueprints.Preset p, Path to) throws IOException {Blueprints.export(p, to);}
    protected Blueprints.Preset importfile(Path src) throws IOException {return(Blueprints.importFile(dir, src));}

    protected String summary(Blueprints.Preset p) {
        return(String.format("%d × %d tiles · %d object%s · %s", p.w, p.h, p.count(), (p.count() == 1) ? "" : "s",
                             (p.kind == Blueprints.Kind.FOLDER) ? p.layers.size() + " layers" : "layer"));
    }

    protected void place(Blueprints.Preset p) {
        view.stampblueprint(p);
        msg.accept("Placing " + p.name + ": wheel or R turns, F mirrors, click places, Esc or right click cancels");
    }

    protected void saverow(int x, int y, int bw) {
        ToolButton b;
        b = add(new ToolButton(bw, "Save layer", null, () -> savenode(false)), x, y); x += bw + GAP;
        b.settip("Save the active layer as a blueprint");
        b = add(new ToolButton(bw, "Save folder", null, () -> savenode(true)), x, y); x += bw + GAP;
        b.settip("Save the active layer's folder, all its layers, as a blueprint");
        b = add(new ToolButton(bw, "Save region", null, this::saveregion), x, y); x += bw + GAP;
        b.settip("Drag a rectangle on the ground: the objects of visible layers inside it");
        b = add(new ToolButton(bw, "Save selection", null, this::saveselection), x, y); x += bw + GAP;
        b.settip("The selected objects, keeping their layers");
        add(new ToolButton(bw, "Open folder", null, this::openfolder), x, y);
    }

    /** The objects' footprints from above, scaled into the square. */
    protected void thumb(GOut g, Blueprints.Preset p, Coord at, int sz) {
        double span = Math.max(p.w, p.h) * Blueprints.TILE, sc = (sz - UI.scale(8)) / span;
        Coord o = at.add(UI.scale(4) + (int)((span - p.w * Blueprints.TILE) * sc / 2), UI.scale(4) + (int)((span - p.h * Blueprints.TILE) * sc / 2));
        g.chcolor(233, 156, 84, 60);
        g.frect(o, Coord.of((int)(p.w * Blueprints.TILE * sc), (int)(p.h * Blueprints.TILE * sc)));
        g.chcolor(143, 200, 190, 220);
        for(Blueprints.Ghost gh : p.ghosts()) {
            double[] b = Blueprints.footprint(gh);
            g.frect(o.add((int)(b[0] * sc), (int)(b[1] * sc)),
                    Coord.of(Math.max(1, (int)((b[2] - b[0]) * sc)), Math.max(1, (int)((b[3] - b[1]) * sc))));
        }
        g.chcolor();
    }

    /* ---------- saving from the plan ---------- */

    private interface Capture {
        Blueprints.Preset run(String name) throws BlueprintPlan.Empty;
    }

    /** Asks for a name, captures, saves under a new file and selects it. */
    private void save(String suggest, Capture c) {
        ask("Save as", suggest, name -> {
            try {
                Blueprints.Preset p = c.run(name);
                Blueprints.saveNew(dir, p);
                msg.accept(String.format("Saved %s (%d objects). In the game: Base planner › Presets", p.name, p.count()));
                select(p.file);
            } catch(BlueprintPlan.Empty e) {
                msg.accept(e.getMessage());
            } catch(IOException e) {
                msg.accept("Could not save: " + e.getMessage());
            }
        });
    }

    private void savenode(boolean folder) {
        Layers.Node n = plan.layers.get(plan.layers.active());
        if(n != null && folder)
            n = (n.folder) ? n : plan.layers.get(n.parent);
        if(n == null) {
            msg.accept(folder ? "The active layer is not in a folder" : "No active layer");
            return;
        }
        String id = n.id;
        save(n.name, name -> BlueprintPlan.capturenode(plan, name, id));
    }

    private void saveregion() {
        msg.accept("Drag a rectangle on the ground; the objects of visible layers inside it are saved");
        view.blueprints.armregion((ul, br) -> save("", name -> BlueprintPlan.captureregion(plan, name, ul, br)));
    }

    private void saveselection() {
        List<PlanObject> objs = view.sel.objects();
        if(objs.isEmpty()) {
            msg.accept("Select some objects first");
            return;
        }
        save("", name -> BlueprintPlan.captureobjects(plan, name, objs));
    }

    /** Dev: selects a preset by file name and starts placing it. */
    public void demo(String file) {
        show();
        select(file);
        if(selected() != null)
            place(selected());
    }

    /** Ctrl+B in the view: save the selection. */
    public void saveselectionkey() {
        show();
        saveselection();
    }
}
