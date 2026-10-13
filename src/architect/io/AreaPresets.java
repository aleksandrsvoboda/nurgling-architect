package architect.io;

import architect.ArchitectEnv;
import architect.edit.*;
import haven.*;
import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;

/**
 * nurgling area presets: the built-in ones shipped in the engine jar (nurgling/areas/presets) and your own
 * (%APPDATA%\Haven and Hearth\area_presets, read only). Plan areas export in the same format, so the game's
 * Areas › Presets › Place puts them down.
 */
public class AreaPresets {
    public static class Preset {
        public final String name, category;
        public final boolean mine;
        public final JSONObject json;

        Preset(JSONObject json, boolean mine) {
            this.json = json;
            this.mine = mine;
            this.name = json.optString("name", "Preset");
            this.category = mine ? "Your presets" : json.optString("category", "");
        }
    }

    public static Path userdir() {
        return(ArchitectEnv.gameRoot.resolve("area_presets"));
    }

    public static List<Preset> all() {
        List<Preset> ret = new ArrayList<>();
        try {
            Path engine = Paths.get(Resource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try(JarFile jar = new JarFile(engine.toFile())) {
                for(JarEntry e : Collections.list(jar.entries())) {
                    if(!e.getName().startsWith("nurgling/areas/presets/") || !e.getName().endsWith(".json"))
                        continue;
                    try(InputStream in = jar.getInputStream(e)) {
                        ret.add(new Preset(new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)), false));
                    } catch(JSONException ex) {
                        /* a broken preset is skipped, the rest stay usable */
                    }
                }
            }
        } catch(IOException | java.net.URISyntaxException e) {
            new Warning(e, "cannot read built-in area presets").issue();
        }
        if(Files.isDirectory(userdir())) {
            try(Stream<Path> s = Files.list(userdir())) {
                for(Path p : s.filter(f -> f.toString().endsWith(".json")).collect(Collectors.toList())) {
                    try {
                        ret.add(new Preset(new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)), true));
                    } catch(JSONException | IOException ex) {
                        /* skipped */
                    }
                }
            } catch(IOException e) {
                new Warning(e, "cannot list your area presets").issue();
            }
        }
        ret.sort(Comparator.comparing((Preset p) -> !p.mine).thenComparing(p -> p.category).thenComparing(p -> p.name));
        return(ret);
    }

    /** Writes the plan's visible areas as one area preset. Returns how many areas went in. */
    public static int export(Path file, String name, Plan plan) throws IOException {
        List<PlanArea> as = plan.areas().stream().filter(a -> plan.layers.shown(a.layer)).collect(Collectors.toList());
        if(as.isEmpty())
            return(0);
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for(PlanArea a : as) {
            x0 = Math.min(x0, a.tiles.ul.x); y0 = Math.min(y0, a.tiles.ul.y);
            x1 = Math.max(x1, a.tiles.br.x); y1 = Math.max(y1, a.tiles.br.y);
        }
        Coord origin = Coord.of(x0, y0);
        JSONObject o = new JSONObject();
        o.put("format", 1);
        o.put("name", name);
        o.put("category", "Nurgling Architect");
        o.put("description", "Planned in Nurgling Architect.");
        JSONObject sz = new JSONObject();
        sz.put("w", x1 - x0);
        sz.put("h", y1 - y0);
        o.put("size", sz);
        JSONArray arr = new JSONArray();
        for(PlanArea a : as)
            arr.put(a.preset(origin));
        o.put("areas", arr);
        PlanFile.write(file, o.toString(2));
        return(as.size());
    }
}
