package architect.io;

import architect.ArchitectEnv;
import architect.edit.*;
import haven.*;
import nurgling.areas.AreaPaths;
import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * nurgling area presets, read and written in the game's own format and folder: the built-in ones shipped in
 * the engine jar (nurgling/areas/presets, read only) and your own in %APPDATA%\Haven and Hearth\area_presets,
 * so a preset made here is in the game's Areas › Presets window and the other way round.
 *
 * Format 1, as nurgling2 writes it (nurgling.areas.AreaPreset): {format, name, description?, category?,
 * size: {w, h}, areas: [{name, path, x, y, w, h, color, hide, spec, in, out, pile_fill_direction}]} with x/y in
 * tiles from the preset's top-left corner. Separate implementation; the projects share files, not code.
 */
public class AreaPresets {
    public static final int FORMAT = 1;
    /** The game splits an area over at most two grids per axis, so no side may pass a grid's width. */
    public static final int MAXSIDE = 100;

    public static class Preset {
        public String name = "Areas", description = "", category = "";
        public int w, h;
        /** The "areas" entries, in the game's format. */
        public final List<JSONObject> areas = new ArrayList<>();
        public boolean builtin = false, foreign = false;
        public String file = null;
        JSONObject raw = null;

        public int count() {
            return(areas.size());
        }

        /** Number of sub-folders the preset makes (its own top level not counted). */
        public int folders() {
            Set<String> ps = new HashSet<>();
            for(JSONObject a : areas)
                ps.add(a.optString("path", ""));
            return(AreaPaths.allFolders(ps).size());
        }

        /** Moves the entries so the box starts at (0,0) and sizes the box, as the game does on loading. */
        void normalize() {
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for(JSONObject a : areas) {
                x0 = Math.min(x0, a.getInt("x"));
                y0 = Math.min(y0, a.getInt("y"));
                x1 = Math.max(x1, a.getInt("x") + a.getInt("w"));
                y1 = Math.max(y1, a.getInt("y") + a.getInt("h"));
            }
            for(JSONObject a : areas) {
                a.put("x", a.getInt("x") - x0);
                a.put("y", a.getInt("y") - y0);
            }
            w = x1 - x0;
            h = y1 - y0;
        }

        /**
         * A copy turned turns quarter turns clockwise, then mirrored left to right if asked; fill directions
         * turn with the rectangles. The game's AreaPreset.transformed, step for step.
         */
        public Preset transformed(int turns, boolean mirror) {
            Preset p = new Preset();
            p.name = name; p.description = description; p.category = category;
            p.builtin = builtin; p.file = file;
            int t = Math.floorMod(turns, 4);
            for(JSONObject src : areas) {
                JSONObject e = new JSONObject(src.toString());
                int x = e.getInt("x"), y = e.getInt("y"), ew = e.getInt("w"), eh = e.getInt("h");
                String fill = e.optString(FILL, "DEFAULT");
                int cw = w, ch = h;
                for(int i = 0; i < t; i++) {
                    int nx = ch - y - eh, ny = x, nw = eh;
                    x = nx; y = ny; eh = ew; ew = nw;
                    fill = turn(fill);
                    int tmp = cw; cw = ch; ch = tmp;
                }
                if(mirror) {
                    x = cw - x - ew;
                    fill = mirror(fill);
                }
                e.put("x", x); e.put("y", y); e.put("w", ew); e.put("h", eh);
                e.put(FILL, fill);
                p.areas.add(e);
            }
            p.normalize();
            return(p);
        }

        public JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("format", FORMAT);
            o.put("name", name);
            if(!description.isEmpty())
                o.put("description", description);
            if(!category.isEmpty())
                o.put("category", category);
            JSONObject size = new JSONObject();
            size.put("w", w);
            size.put("h", h);
            o.put("size", size);
            JSONArray arr = new JSONArray();
            for(JSONObject a : areas)
                arr.put(a);
            o.put("areas", arr);
            return(o);
        }
    }

    static final String FILL = "pile_fill_direction";

    /** The direction a zone is worked in after the layout turns a quarter clockwise (AreaPreset.turn). */
    static String turn(String d) {
        switch(d) {
        case "LEFT_TO_RIGHT": return("TOP_TO_BOTTOM");
        case "TOP_TO_BOTTOM": return("RIGHT_TO_LEFT");
        case "RIGHT_TO_LEFT": return("BOTTOM_TO_TOP");
        case "BOTTOM_TO_TOP": return("LEFT_TO_RIGHT");
        default: return(d);
        }
    }

    static String mirror(String d) {
        switch(d) {
        case "LEFT_TO_RIGHT": return("RIGHT_TO_LEFT");
        case "RIGHT_TO_LEFT": return("LEFT_TO_RIGHT");
        default: return(d);
        }
    }

    /* ---------- reading ---------- */

    /** Throws JSONException or IllegalArgumentException for JSON that isn't a usable preset. */
    public static Preset parse(JSONObject o) {
        Preset p = new Preset();
        p.raw = o;
        p.name = o.optString("name", "Areas");
        p.description = o.optString("description", "");
        p.category = o.optString("category", "");
        if(o.optInt("format", FORMAT) != FORMAT) {
            p.foreign = true;
            return(p);
        }
        JSONArray arr = o.getJSONArray("areas");
        for(int i = 0; i < arr.length(); i++) {
            JSONObject a = new JSONObject(arr.getJSONObject(i).toString());
            a.getString("name");
            int w = a.getInt("w"), h = a.getInt("h");
            a.getInt("x");
            a.getInt("y");
            if(w <= 0 || h <= 0 || w > MAXSIDE || h > MAXSIDE)
                throw(new IllegalArgumentException("bad size for area " + a.getString("name")));
            p.areas.add(a);
        }
        if(p.areas.isEmpty())
            throw(new IllegalArgumentException("no areas"));
        p.normalize();
        return(p);
    }

    public static Preset load(Path f) throws IOException {
        try {
            return(parse(new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))));
        } catch(JSONException | IllegalArgumentException e) {
            throw(new IOException("not an area preset: " + f.getFileName() + " (" + e.getMessage() + ")", e));
        }
    }

    /** The game's folder for your presets. */
    public static Path dir() {
        return(ArchitectEnv.gameRoot.resolve("area_presets"));
    }

    /** The presets shipped in the engine jar, by category then name. */
    public static List<Preset> builtins() {
        List<Preset> ret = new ArrayList<>();
        try {
            Path engine = Paths.get(Resource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try(JarFile jar = new JarFile(engine.toFile())) {
                for(JarEntry e : Collections.list(jar.entries())) {
                    String n = e.getName();
                    if(!n.startsWith("nurgling/areas/presets/") || !n.endsWith(".json") || n.endsWith("/index.json"))
                        continue;
                    try(InputStream in = jar.getInputStream(e)) {
                        Preset p = parse(new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                        p.builtin = true;
                        p.file = n.substring(n.lastIndexOf('/') + 1);
                        ret.add(p);
                    } catch(JSONException | IllegalArgumentException ex) {
                        /* a broken preset is skipped, the rest stay usable */
                    }
                }
            }
        } catch(IOException | java.net.URISyntaxException e) {
            new Warning(e, "cannot read built-in area presets").issue();
        }
        ret.sort(Comparator.comparing((Preset p) -> p.category).thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        return(ret);
    }

    /** Your presets in dir, A-Z; files that don't parse are skipped. */
    public static List<Preset> user(Path dir) {
        List<Preset> ret = new ArrayList<>();
        if(!Files.isDirectory(dir))
            return(ret);
        try(DirectoryStream<Path> fs = Files.newDirectoryStream(dir, "*.json")) {
            for(Path f : fs) {
                try {
                    Preset p = load(f);
                    p.file = f.getFileName().toString();
                    ret.add(p);
                } catch(IOException e) {
                    System.out.println("[area presets] skipping " + f + ": " + e.getMessage());
                }
            }
        } catch(IOException e) {
            System.out.println("[area presets] cannot list " + dir + ": " + e.getMessage());
        }
        ret.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        return(ret);
    }

    /* ---------- writing ---------- */

    public static void saveNew(Path dir, Preset p) throws IOException {
        Files.createDirectories(dir);
        p.file = PresetFiles.freename(dir, p.name);
        p.builtin = false;
        p.raw = null;
        write(dir, p);
    }

    /** Rewrites one of your presets in its own file; read from disk, only its name and description change. */
    public static void write(Path dir, Preset p) throws IOException {
        if(p.builtin || p.file == null)
            throw(new IOException("not one of your presets: " + p.name));
        if(p.foreign)
            throw(new IOException("made by a different version of the game: " + p.name));
        JSONObject o = (p.raw != null) ? PresetFiles.renamed(p.raw, p.name, p.description) : p.json();
        PresetFiles.atomic(dir.resolve(p.file), o.toString(2));
        p.raw = o;
    }

    public static void delete(Path dir, Preset p) throws IOException {
        if(!p.builtin)
            PresetFiles.delete(dir, p.file);
    }

    public static void export(Preset p, Path target) throws IOException {
        PresetFiles.atomic(target, ((p.raw != null) ? p.raw : p.json()).toString(2));
    }

    public static Preset importFile(Path dir, Path source) throws IOException {
        Preset p = load(source);
        if(p.foreign)
            throw(new IOException("made by a different version of the game"));
        JSONObject raw = p.raw;
        saveNew(dir, p);
        p.raw = raw;
        write(dir, p);
        return(p);
    }

    /* ---------- plan <-> preset ---------- */

    /** Thrown when there is nothing to save; the message is shown. */
    public static class Empty extends Exception {
        public Empty(String msg) {
            super(msg);
        }
    }

    /** The deepest folder holding every given area path and folder (AreaPreset.commonRoot). */
    static String commonroot(Collection<String> paths, Collection<String> folders) {
        List<String> all = new ArrayList<>(paths);
        for(String f : folders)
            all.add(AreaPaths.parent(f));
        String root = null;
        for(String p : all) {
            if(root == null) {
                root = p;
                continue;
            }
            while(!AreaPaths.isUnder(p, root))
                root = AreaPaths.parent(root);
        }
        return((root == null) ? AreaPaths.ROOT : root);
    }

    /**
     * The given areas and every area under the given folders as a preset, folders kept relative to the deepest
     * folder holding them all (as the game's AreaPreset.capture).
     */
    public static Preset capture(Plan plan, String name, Collection<PlanArea> picked, Collection<String> folders) throws Empty {
        LinkedHashMap<String, PlanArea> chosen = new LinkedHashMap<>();
        for(PlanArea a : picked)
            chosen.put(a.id, a);
        for(PlanArea a : plan.areas())
            for(String f : folders)
                if(AreaPaths.isUnder(a.path, f))
                    chosen.put(a.id, a);
        if(chosen.isEmpty())
            throw(new Empty("No areas to save"));
        for(PlanArea a : chosen.values())
            if(a.tiles.sz().x > MAXSIDE || a.tiles.sz().y > MAXSIDE)
                throw(new Empty(a.name + " is wider than " + MAXSIDE + " tiles; the game would refuse the preset"));
        List<String> paths = new ArrayList<>();
        for(PlanArea a : picked)
            paths.add(a.path);
        String root = commonroot(paths, folders);
        Preset p = new Preset();
        p.name = name;
        for(PlanArea a : chosen.values()) {
            JSONObject e = a.preset(Coord.z);
            e.put("path", AreaPaths.reparent(a.path, root, AreaPaths.ROOT));
            p.areas.add(e);
        }
        p.normalize();
        return(p);
    }

    /** The folder a placed preset goes in: parent/name, or "name 2", "name 3"... if that is taken (the game's rule). */
    public static String freefolder(Plan plan, String parent, String name) {
        Set<String> used = new HashSet<>();
        for(PlanArea a : plan.areas())
            used.addAll(AreaPaths.crumbs(a.path));
        String clean = AreaPaths.clean(name).replace('/', ' ');
        if(clean.isEmpty())
            clean = "Preset";
        String folder = AreaPaths.join(parent, clean);
        for(int i = 2; used.contains(folder); i++)
            folder = AreaPaths.join(parent, clean + " " + i);
        return(folder);
    }

    /** The areas a preset puts down with its top-left corner on tile ul, in the given folder and layer. */
    public static List<PlanArea> areas(Preset p, Coord ul, String folder, String layer) {
        List<PlanArea> ret = new ArrayList<>();
        for(JSONObject e : p.areas) {
            PlanArea a = PlanArea.frompreset(e, ul, layer);
            PlanArea.Edit ed = a.edit();
            ed.path = folder + e.optString("path", "");
            ret.add(a.with(ed));
        }
        return(ret);
    }

    /** Places a preset as one undo step, in a new folder named after it; returns that folder. */
    public static String place(Plan plan, Preset p, Coord ul, String parent, String layer) {
        String folder = freefolder(plan, (parent == null) ? AreaPaths.ROOT : parent, p.name);
        plan.begin("Place " + p.name);
        try {
            for(PlanArea a : areas(p, ul, folder, layer))
                plan.putarea(a);
        } finally {
            plan.commit();
        }
        return(folder);
    }
}
