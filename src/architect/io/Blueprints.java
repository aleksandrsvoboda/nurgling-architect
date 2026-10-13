package architect.io;

import architect.ArchitectEnv;
import org.json.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * nurgling's Base planner presets ("blueprints"), read and written in the game's own folder and format, so a
 * blueprint made here shows up in the game's Presets window and the other way round.
 *
 * Format 1, as nurgling2 writes it (nurgling.planning.PlannerPreset): {format, kind: "layer"|"folder", name,
 * description?, size: {w, h} in tiles, layers: [{name, ghosts: [{res, sdt? (base64), x, y, angle}]}]}. x and y
 * are world units from the box's top-left tile corner, so moving or quarter-turning the stamp keeps every
 * object on the sub-tile snap it was planned with. This is a separate implementation on purpose: the two
 * projects share files, not code. Files are named, written and deleted the way the game does it.
 */
public class Blueprints {
    public static final int FORMAT = 1;
    public static final String DIR = "planner_presets";
    public static final double TILE = 11;

    public enum Kind {LAYER, FOLDER}

    public static class Ghost {
        public final String res;
        /** State data the object is drawn with (which parts, which variant); may be empty. */
        public final byte[] sdt;
        public double x, y, angle;

        public Ghost(String res, byte[] sdt, double x, double y, double angle) {
            this.res = res;
            this.sdt = (sdt == null) ? new byte[0] : sdt;
            this.x = x;
            this.y = y;
            this.angle = angle;
        }

        Ghost copy() {
            return(new Ghost(res, sdt, x, y, angle));
        }
    }

    public static class Layer {
        public String name;
        public final List<Ghost> ghosts = new ArrayList<>();

        public Layer(String name) {
            this.name = name;
        }
    }

    public static class Preset {
        public String name = "Blueprint", description = "";
        public Kind kind = Kind.LAYER;
        public int w = 1, h = 1;
        public final List<Layer> layers = new ArrayList<>();
        /** File name in the folder, once saved or loaded. */
        public String file = null;
        /** The JSON as read, kept so that renaming rewrites only what changed (fields a newer game adds survive). */
        JSONObject raw = null;
        /** A file of another format: listed, never placed or rewritten. */
        public boolean foreign = false;

        public int count() {
            int n = 0;
            for(Layer l : layers)
                n += l.ghosts.size();
            return(n);
        }

        public List<Ghost> ghosts() {
            List<Ghost> ret = new ArrayList<>();
            for(Layer l : layers)
                ret.addAll(l.ghosts);
            return(ret);
        }

        /**
         * A copy turned turns quarter turns clockwise, then mirrored left to right if asked. Same arithmetic as
         * the game's PlannerPreset.transformed, so a stamp lands identically in both. Only positions and
         * facings mirror; there are no mirrored models.
         */
        public Preset transformed(int turns, boolean mirror) {
            Preset p = new Preset();
            p.name = name;
            p.description = description;
            p.kind = kind;
            p.file = file;
            int t = Math.floorMod(turns, 4);
            p.w = ((t & 1) == 0) ? w : h;
            p.h = ((t & 1) == 0) ? h : w;
            for(Layer src : layers) {
                Layer l = new Layer(src.name);
                for(Ghost sg : src.ghosts) {
                    Ghost g = sg.copy();
                    double cw = w * TILE, ch = h * TILE;
                    for(int i = 0; i < t; i++) {
                        double nx = ch - g.y;
                        g.y = g.x;
                        g.x = nx;
                        g.angle += Math.PI / 2;
                        double tmp = cw;
                        cw = ch;
                        ch = tmp;
                    }
                    if(mirror) {
                        g.x = cw - g.x;
                        g.angle = Math.PI - g.angle;
                    }
                    g.angle %= 2 * Math.PI;
                    if(g.angle < 0)
                        g.angle += 2 * Math.PI;
                    l.ghosts.add(g);
                }
                p.layers.add(l);
            }
            return(p);
        }

        /**
         * Shifts the objects (given in world coordinates) so the box starts at (0,0) on a tile corner, and sizes
         * the box from their footprints (the game's rule: see {@link #footprint}).
         */
        public void normalize() {
            double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE, maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
            for(Ghost g : ghosts()) {
                double[] b = footprint(g);
                minx = Math.min(minx, b[0]);
                miny = Math.min(miny, b[1]);
                maxx = Math.max(maxx, b[2]);
                maxy = Math.max(maxy, b[3]);
            }
            double ox = Math.floor(minx / TILE) * TILE, oy = Math.floor(miny / TILE) * TILE;
            for(Ghost g : ghosts()) {
                g.x -= ox;
                g.y -= oy;
            }
            w = Math.max(1, (int)Math.ceil((maxx - ox) / TILE - 1e-9));
            h = Math.max(1, (int)Math.ceil((maxy - oy) / TILE - 1e-9));
        }

        public JSONObject json() {
            JSONObject o = new JSONObject();
            o.put("format", FORMAT);
            o.put("kind", (kind == Kind.FOLDER) ? "folder" : "layer");
            o.put("name", name);
            if(!description.isEmpty())
                o.put("description", description);
            JSONObject size = new JSONObject();
            size.put("w", w);
            size.put("h", h);
            o.put("size", size);
            JSONArray jl = new JSONArray();
            for(Layer l : layers) {
                JSONObject lo = new JSONObject();
                lo.put("name", l.name);
                JSONArray gs = new JSONArray();
                for(Ghost g : l.ghosts) {
                    JSONObject go = new JSONObject();
                    go.put("res", g.res);
                    if(g.sdt.length > 0)
                        go.put("sdt", Base64.getEncoder().encodeToString(g.sdt));
                    go.put("x", g.x);
                    go.put("y", g.y);
                    go.put("angle", g.angle);
                    gs.put(go);
                }
                lo.put("ghosts", gs);
                jl.put(lo);
            }
            o.put("layers", jl);
            return(o);
        }
    }

    /**
     * World bounds {minx, miny, maxx, maxy} of a ghost, as the game measures them: nurgling's hand-made hitbox
     * for the object turned by its angle, else the tile around it.
     */
    public static double[] footprint(Ghost g) {
        nurgling.NHitBox hb = nurgling.NHitBox.findCustom(g.res);
        if(hb == null) {
            double hs = TILE / 2;
            return(new double[] {g.x - hs, g.y - hs, g.x + hs, g.y + hs});
        }
        double cos = Math.cos(g.angle), sin = Math.sin(g.angle);
        double[][] cs = {{hb.begin.x, hb.begin.y}, {hb.end.x, hb.begin.y}, {hb.end.x, hb.end.y}, {hb.begin.x, hb.end.y}};
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for(double[] c : cs) {
            double x = g.x + c[0] * cos - c[1] * sin, y = g.y + c[0] * sin + c[1] * cos;
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.max(b[2], x);
            b[3] = Math.max(b[3], y);
        }
        return(b);
    }

    /* ---------- reading ---------- */

    /** Throws JSONException or IllegalArgumentException when the JSON isn't a usable preset. */
    public static Preset parse(JSONObject o) {
        Preset p = new Preset();
        p.raw = o;
        p.name = o.optString("name", "Blueprint");
        p.description = o.optString("description", "");
        if(o.optInt("format", -1) != FORMAT) {
            p.foreign = true;
            return(p);
        }
        p.kind = "folder".equals(o.optString("kind", "layer")) ? Kind.FOLDER : Kind.LAYER;
        JSONObject size = o.getJSONObject("size");
        p.w = size.getInt("w");
        p.h = size.getInt("h");
        if(p.w <= 0 || p.h <= 0)
            throw(new IllegalArgumentException("bad size"));
        JSONArray jl = o.getJSONArray("layers");
        for(int i = 0; i < jl.length(); i++) {
            JSONObject lo = jl.getJSONObject(i);
            Layer l = new Layer(lo.optString("name", "Layer"));
            JSONArray gs = lo.getJSONArray("ghosts");
            for(int j = 0; j < gs.length(); j++) {
                JSONObject go = gs.getJSONObject(j);
                String enc = go.optString("sdt", "");
                l.ghosts.add(new Ghost(go.getString("res"), enc.isEmpty() ? null : Base64.getDecoder().decode(enc),
                                       go.getDouble("x"), go.getDouble("y"), go.optDouble("angle", 0)));
            }
            if(!l.ghosts.isEmpty())
                p.layers.add(l);
        }
        if(p.layers.isEmpty())
            throw(new IllegalArgumentException("no objects"));
        return(p);
    }

    public static Preset load(Path f) throws IOException {
        try {
            return(parse(new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))));
        } catch(JSONException | IllegalArgumentException e) {
            throw(new IOException("not a blueprint: " + f.getFileName() + " (" + e.getMessage() + ")", e));
        }
    }

    /* ---------- the folder ---------- */

    /** The game's folder: %APPDATA%\Haven and Hearth\planner_presets. */
    public static Path dir() {
        return(ArchitectEnv.gameRoot.resolve(DIR));
    }

    /** Every preset in the folder, A-Z; files that don't parse are skipped. */
    public static List<Preset> all() {
        return(all(dir()));
    }

    public static List<Preset> all(Path dir) {
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
                    System.out.println("[blueprints] skipping " + f + ": " + e.getMessage());
                }
            }
        } catch(IOException e) {
            System.out.println("[blueprints] cannot list " + dir + ": " + e.getMessage());
        }
        ret.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        return(ret);
    }

    /** Saves a new preset under a fresh file name made from its name. */
    public static void saveNew(Path dir, Preset p) throws IOException {
        Files.createDirectories(dir);
        p.file = PresetFiles.freename(dir, p.name);
        p.raw = null;
        write(dir, p);
    }

    /**
     * Rewrites a preset in its own file. A preset read from disk keeps its original JSON with only the name and
     * description replaced, so anything a newer game version stores is not lost.
     */
    public static void write(Path dir, Preset p) throws IOException {
        if(p.file == null)
            throw(new IOException("blueprint has no file: " + p.name));
        if(p.foreign)
            throw(new IOException("made by a different version of the game: " + p.name));
        JSONObject o = (p.raw != null) ? PresetFiles.renamed(p.raw, p.name, p.description) : p.json();
        PresetFiles.atomic(dir.resolve(p.file), o.toString(2));
        p.raw = o;
    }

    public static void delete(Path dir, Preset p) throws IOException {
        PresetFiles.delete(dir, p.file);
    }

    public static void export(Preset p, Path target) throws IOException {
        PresetFiles.atomic(target, ((p.raw != null) ? p.raw : p.json()).toString(2));
    }

    /** Copies a preset file from elsewhere into the folder; throws if it isn't one. */
    public static Preset importFile(Path dir, Path source) throws IOException {
        Preset p = load(source);
        if(p.foreign)
            throw(new IOException("made by a different version of the game"));
        JSONObject raw = p.raw;
        saveNew(dir, p);
        if(raw != null) {
            /* keep the file as it was, under its new name */
            p.raw = raw;
            write(dir, p);
        }
        return(p);
    }

}
