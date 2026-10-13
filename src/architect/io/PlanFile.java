package architect.io;

import architect.edit.*;
import architect.world.WorldSource;
import haven.*;
import org.json.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * The planner's own file (.hplan, JSON): the world it is drawn on, the camera, the layer tree and every object.
 * Written atomically with the previous version kept as .bak.
 */
public class PlanFile {
    public static final int FORMAT = 1;
    public static final String EXT = ".hplan";

    /** A plan as read from disk, before it is attached to a running world. */
    public static class Data {
        public String name;
        public JSONObject world;
        public Coord2d camera;
        public final List<Layers.Node> layers = new ArrayList<>();
        public String active;
        public final List<PlanObject> objects = new ArrayList<>();
        public final List<PlanArea> areas = new ArrayList<>();
    }

    public static void save(Path file, String name, WorldSource world, Plan plan, Coord2d camera) throws IOException {
        JSONObject o = new JSONObject();
        o.put("format", FORMAT);
        o.put("app", "Nurgling Architect");
        o.put("name", name);
        o.put("world", WorldRef.of(world));
        o.put("camera", new JSONArray().put(camera.x).put(camera.y));
        JSONArray ls = new JSONArray();
        for(Layers.Node n : plan.layers.all()) {
            JSONObject l = new JSONObject();
            l.put("id", n.id);
            l.put("type", n.folder ? "folder" : "layer");
            l.put("name", n.name);
            l.put("visible", n.visible);
            l.put("locked", n.locked);
            l.put("open", n.open);
            if(n.parent != null)
                l.put("parent", n.parent);
            ls.put(l);
        }
        o.put("layers", ls);
        o.put("active", plan.layers.active());
        JSONArray objs = new JSONArray();
        for(PlanObject p : plan.objects()) {
            JSONObject j = new JSONObject();
            j.put("id", p.id);
            j.put("res", p.res);
            if(p.sdt.length > 0)
                j.put("sdt", Base64.getEncoder().encodeToString(p.sdt));
            j.put("x", p.rc.x);
            j.put("y", p.rc.y);
            j.put("a", p.a);
            j.put("layer", p.layer);
            objs.put(j);
        }
        o.put("objects", objs);
        JSONArray as = new JSONArray();
        for(PlanArea a : plan.areas())
            as.put(a.save());
        o.put("areas", as);
        write(file, o.toString(1));
    }

    public static Data load(Path file) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch(JSONException e) {
            throw(new IOException("not a plan file: " + e.getMessage(), e));
        }
        if(o.optInt("format") != FORMAT)
            throw(new IOException("unsupported plan format " + o.optInt("format")));
        Data d = new Data();
        d.name = o.optString("name", file.getFileName().toString().replace(EXT, ""));
        d.world = o.getJSONObject("world");
        JSONArray cam = o.optJSONArray("camera");
        d.camera = (cam != null) ? Coord2d.of(cam.getDouble(0), cam.getDouble(1)) : null;
        JSONArray ls = o.optJSONArray("layers");
        if(ls != null) {
            for(int i = 0; i < ls.length(); i++) {
                JSONObject l = ls.getJSONObject(i);
                Layers.Node n = new Layers.Node(l.getString("id"), "folder".equals(l.optString("type")),
                                                l.optString("name", "Layer"), l.optString("parent", null));
                n.visible = l.optBoolean("visible", true);
                n.locked = l.optBoolean("locked", false);
                n.open = l.optBoolean("open", true);
                d.layers.add(n);
            }
        }
        d.active = o.optString("active", null);
        JSONArray objs = o.optJSONArray("objects");
        if(objs != null) {
            for(int i = 0; i < objs.length(); i++) {
                JSONObject j = objs.getJSONObject(i);
                byte[] sdt = j.has("sdt") ? Base64.getDecoder().decode(j.getString("sdt")) : null;
                d.objects.add(new PlanObject(j.getString("id"), j.getString("res"), sdt,
                                             Coord2d.of(j.getDouble("x"), j.getDouble("y")), j.optDouble("a", 0),
                                             j.optString("layer", null)));
            }
        }
        JSONArray as = o.optJSONArray("areas");
        for(int i = 0; as != null && i < as.length(); i++)
            d.areas.add(PlanArea.load(as.getJSONObject(i)));
        return(d);
    }

    /** Puts loaded layers and objects into an empty plan (no undo history). */
    public static void apply(Data d, Plan plan) {
        if(!d.layers.isEmpty()) {
            plan.layers.clear();
            for(Layers.Node n : d.layers)
                plan.layers.restore(n);
            if(d.active != null)
                plan.layers.activate(d.active);
        }
        plan.load(d.objects);
        plan.loadareas(d.areas);
    }

    public static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        if(Files.exists(file))
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
