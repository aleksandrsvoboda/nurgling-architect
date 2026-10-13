package architect.edit;

import architect.io.Blueprints;
import haven.*;

import java.util.*;
import java.util.function.Predicate;

/**
 * Between a plan and blueprint presets: capturing objects as a preset, and stamping a preset into the plan,
 * the way nurgling's Base planner does it (a one-layer preset becomes a layer in the open folder, a folder
 * preset a new folder of layers).
 */
public class BlueprintPlan {
    /** Thrown when nothing can be saved; the message is shown. */
    public static class Empty extends Exception {
        public Empty(String msg) {
            super(msg);
        }
    }

    /**
     * The state an object is saved with: its own (a variant), else the look the planner draws it with, so the
     * game draws it too instead of an empty ghost (tar kiln, stockpiles, crops).
     */
    static byte[] sdtof(PlanObject o) {
        if(o.sdt.length > 0)
            return(o.sdt);
        byte[] d = Looks.known(o.res);
        return((d == null) ? new byte[0] : d);
    }

    /**
     * The objects on the given layers that pass keep, one preset layer per plan layer (in the given order),
     * normalized to the box. Layers left empty are dropped.
     */
    public static Blueprints.Preset capture(Plan plan, String name, List<String> layerids, Predicate<PlanObject> keep) throws Empty {
        Blueprints.Preset p = new Blueprints.Preset();
        p.name = name;
        Map<String, Blueprints.Layer> bylayer = new LinkedHashMap<>();
        for(String id : layerids) {
            Layers.Node n = plan.layers.get(id);
            bylayer.put(id, new Blueprints.Layer((n != null) ? n.name : "Layer"));
        }
        for(PlanObject o : plan.objects()) {
            Blueprints.Layer l = bylayer.get(o.layer);
            if(l != null && keep.test(o))
                l.ghosts.add(new Blueprints.Ghost(o.res, sdtof(o), o.rc.x, o.rc.y, o.a));
        }
        for(Blueprints.Layer l : bylayer.values())
            if(!l.ghosts.isEmpty())
                p.layers.add(l);
        if(p.layers.isEmpty())
            throw(new Empty("Nothing to save there"));
        p.kind = (p.layers.size() > 1) ? Blueprints.Kind.FOLDER : Blueprints.Kind.LAYER;
        p.normalize();
        return(p);
    }

    /** Layer ids of a node: the layer itself, or a folder's layers in their order. */
    public static List<String> layersof(Plan plan, String nodeid) {
        List<String> ret = new ArrayList<>();
        Layers.Node n = plan.layers.get(nodeid);
        if(n == null)
            return(ret);
        if(!n.folder) {
            ret.add(n.id);
        } else {
            for(Layers.Node c : plan.layers.all())
                if(!c.folder && n.id.equals(c.parent))
                    ret.add(c.id);
        }
        return(ret);
    }

    /** "Save layer/folder": like the game, a folder saves as a folder preset even with one layer. */
    public static Blueprints.Preset capturenode(Plan plan, String name, String nodeid) throws Empty {
        Layers.Node n = plan.layers.get(nodeid);
        Blueprints.Preset p = capture(plan, name, layersof(plan, nodeid), o -> true);
        if(n != null && n.folder)
            p.kind = Blueprints.Kind.FOLDER;
        return(p);
    }

    /** Visible layers, top to bottom. */
    public static List<String> visiblelayers(Plan plan) {
        List<String> ret = new ArrayList<>();
        for(Layers.Node n : plan.layers.rows())
            if(!n.folder && plan.layers.shown(n.id))
                ret.add(n.id);
        return(ret);
    }

    /** "Save region": objects of visible layers whose centre lies in the world rectangle [ul, br). */
    public static Blueprints.Preset captureregion(Plan plan, String name, Coord2d ul, Coord2d br) throws Empty {
        return(capture(plan, name, visiblelayers(plan),
                       o -> o.rc.x >= ul.x && o.rc.y >= ul.y && o.rc.x < br.x && o.rc.y < br.y));
    }

    /** "Save selection": the selected objects, keeping their layers. */
    public static Blueprints.Preset captureobjects(Plan plan, String name, Collection<PlanObject> objs) throws Empty {
        Set<PlanObject> keep = new HashSet<>(objs);
        List<String> ids = new ArrayList<>();
        for(Layers.Node n : plan.layers.rows())
            if(!n.folder)
                ids.add(n.id);
        for(PlanObject o : objs)
            if(!ids.contains(o.layer))
                ids.add(o.layer);
        return(capture(plan, name, ids, keep::contains));
    }

    /** Objects the preset puts down with its box's top-left corner on tile origin, in preset layer order. */
    public static List<List<PlanObject>> objects(Blueprints.Preset p, Coord origin) {
        Coord2d o = Coord2d.of(origin.x * Blueprints.TILE, origin.y * Blueprints.TILE);
        List<List<PlanObject>> ret = new ArrayList<>();
        for(Blueprints.Layer l : p.layers) {
            List<PlanObject> os = new ArrayList<>();
            for(Blueprints.Ghost g : l.ghosts)
                os.add(new PlanObject(g.res, g.sdt, o.add(g.x, g.y), g.angle));
            ret.add(os);
        }
        return(ret);
    }

    /**
     * Stamps a preset into the plan as one undo step: a layer preset becomes a new layer in folder (null = top
     * level), a folder preset a new folder holding its layers. Returns the new node's id.
     */
    public static String place(Plan plan, Blueprints.Preset p, Coord origin, String folder) {
        List<List<PlanObject>> objs = objects(p, origin);
        Layers ls = plan.layers;
        List<Layers.Node> made = new ArrayList<>();
        List<String> into = new ArrayList<>();
        Layers.Node top;
        if(p.kind == Blueprints.Kind.FOLDER) {
            top = ls.addFolder(ls.uniqueName(p.name));
            made.add(top);
            for(Blueprints.Layer l : p.layers) {
                Layers.Node n = ls.addLayer(l.name, top.id);
                made.add(n);
                into.add(n.id);
            }
        } else {
            top = ls.addLayer(ls.uniqueName(p.name), folder);
            made.add(top);
            into.add(top.id);
        }
        String active = ls.active();
        ls.activate(into.get(0));
        plan.begin("Place " + p.name);
        try {
            for(int i = 0; i < objs.size(); i++) {
                String layer = into.get(Math.min(i, into.size() - 1));
                for(PlanObject o : objs.get(i)) {
                    o.layer = layer;
                    plan.add(o);
                }
            }
            /* the new layers and folder go and come back with the objects */
            plan.custom("Place " + p.name, () -> {
                ls.remove(top.id);
                if(ls.get(active) != null)
                    ls.activate(active);
            }, () -> {
                for(Layers.Node n : made)
                    ls.restore(n);
                ls.activate(into.get(0));
            });
        } finally {
            plan.commit();
        }
        return(top.id);
    }
}
