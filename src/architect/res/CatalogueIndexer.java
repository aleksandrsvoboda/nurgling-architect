package architect.res;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.jar.*;
import java.util.stream.*;

/**
 * Builds the {@link Catalogue} from what is on disk: the game cache (read-only) and the bundled hafen-res.jar.
 *
 * The cache is addressed by hashed names, so finding "every object" means reading every file's header once
 * (~1 minute for ~60k files). The result is saved next to the planner settings and reused until the cache
 * directory changes.
 */
public class CatalogueIndexer {
    public static final int FORMAT = 2;
    public static final String RENDER_CID = "http://game.havenandhearth.com/render/";
    static final String OBJ = "gfx/terobjs/", BLD = "paginae/bld/", TILES = "gfx/tiles/";
    /** Blueprints whose object is not named after them. */
    static final Map<String, String> RENAMES = new HashMap<>();
    static {
        RENAMES.put("woodensign", "textsign");
    }

    /** What the scan keeps per resource. */
    static class Raw {
        final String name;
        boolean model, tileset;
        String tooltip;
        String parent, title, bp;

        Raw(String name) {
            this.name = name;
        }
    }

    private final Path gameData, resJar, indexFile;
    private final Consumer<String> progress;

    public CatalogueIndexer(Path gameData, Path resJar, Path home, Consumer<String> progress) {
        this.gameData = gameData;
        this.resJar = resJar;
        this.indexFile = home.resolve("catalogue.json");
        this.progress = progress;
    }

    private String fingerprint() throws IOException {
        long dm = Files.isDirectory(gameData) ? Files.getLastModifiedTime(gameData).toMillis() : 0;
        long js = (resJar != null && Files.exists(resJar)) ? Files.size(resJar) : 0;
        return(FORMAT + ":" + dm + ":" + js);
    }

    public Catalogue load() throws IOException {
        String fp = fingerprint();
        Map<String, Raw> raw = null;
        if(Files.exists(indexFile)) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8));
                if(fp.equals(o.optString("fingerprint")))
                    raw = fromjson(o);
            } catch(JSONException e) {
                raw = null;
            }
        }
        if(raw == null) {
            raw = scan();
            JSONObject o = tojson(raw);
            o.put("fingerprint", fp);
            Path tmp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
            Files.write(tmp, o.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return(build(raw));
    }

    /* ---------- scanning ---------- */

    private static boolean wanted(String name) {
        return(name.startsWith(OBJ) || name.startsWith(BLD) || name.startsWith(TILES));
    }

    private static Raw read(String name, byte[] res) {
        ResFile f = ResFile.parse(res);
        if(f == null)
            return(null);
        Raw r = new Raw(name);
        r.model = !name.endsWith("-tex") && (f.has("mesh") || f.has("rlink") || f.has("codeentry"));
        r.tooltip = f.tooltip();
        r.tileset = f.has("tileset2");
        ResFile.Action a = f.action();
        if(a != null) {
            r.parent = a.parent;
            r.title = a.name;
            r.bp = a.bp();
        }
        return(r);
    }

    Map<String, Raw> scan() throws IOException {
        Map<String, Raw> ret = new ConcurrentHashMap<>();
        if(resJar != null && Files.exists(resJar)) {
            progress.accept("Reading bundled resources...");
            try(JarFile jar = new JarFile(resJar.toFile())) {
                for(JarEntry e : Collections.list(jar.entries())) {
                    String n = e.getName();
                    if(!n.startsWith("res-preload/") || !n.endsWith(".res"))
                        continue;
                    String name = n.substring("res-preload/".length(), n.length() - 4);
                    if(!wanted(name))
                        continue;
                    try(InputStream in = jar.getInputStream(e)) {
                        Raw r = read(name, in.readAllBytes());
                        if(r != null)
                            ret.put(name, r);
                    }
                }
            }
        }
        if(Files.isDirectory(gameData)) {
            List<Path> files;
            try(Stream<Path> s = Files.list(gameData)) {
                files = s.collect(Collectors.toList());
            }
            AtomicInteger done = new AtomicInteger();
            int total = files.size();
            files.parallelStream().forEach(p -> {
                int n = done.incrementAndGet();
                if(n % 2000 == 0)
                    progress.accept(String.format("Indexing your game cache... %d%%", n * 100 / total));
                try {
                    scanfile(p, ret);
                } catch(IOException e) {
                    /* Unreadable or mid-write by the running game: skip it, as a cache miss. */
                }
            });
        }
        progress.accept("Indexed " + ret.size() + " resources");
        return(ret);
    }

    private static void scanfile(Path p, Map<String, Raw> into) throws IOException {
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(p), 1024))) {
            if(in.readByte() != 1)
                return;
            String cid = in.readUTF();
            String name = in.readUTF();
            if(!cid.equals(RENDER_CID) || !name.startsWith("res/"))
                return;
            name = name.substring(4);
            if(!wanted(name))
                return;
            Raw r = read(name, in.readAllBytes());
            if(r != null)
                into.merge(name, r, (a, b) -> a);   /* the bundled copy wins, as in the resource pool */
        } catch(EOFException | UTFDataFormatException e) {
            /* not a cache entry we understand */
        }
    }

    /* ---------- building the catalogue ---------- */

    static Catalogue build(Map<String, Raw> raw) {
        Map<String, List<String>> bybase = new HashMap<>();
        List<Catalogue.Entry> objects = new ArrayList<>();
        Map<String, String> objname = new HashMap<>();
        for(Raw r : raw.values()) {
            if(r.name.startsWith(OBJ) && r.model) {
                String base = r.name.substring(r.name.lastIndexOf('/') + 1);
                bybase.computeIfAbsent(base, k -> new ArrayList<>()).add(r.name);
            }
        }
        List<Catalogue.Entry> build = new ArrayList<>();
        for(Raw r : raw.values()) {
            if(!r.name.startsWith(BLD) || r.bp == null)
                continue;
            String res = objectFor(r.bp, bybase);
            String name = (r.title != null && !r.title.isEmpty()) ? r.title : Catalogue.prettify(r.bp);
            build.add(new Catalogue.Entry(res, name, menupath(r, raw), r.name));
            if(res != null)
                objname.putIfAbsent(res, name);
        }
        for(Raw r : raw.values()) {
            if(!r.name.startsWith(OBJ) || !r.model)
                continue;
            String rest = r.name.substring(OBJ.length());
            String cat = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : "misc";
            String name = (r.tooltip != null && !r.tooltip.isEmpty()) ? r.tooltip
                : objname.getOrDefault(r.name, Catalogue.prettify(r.name));
            objects.add(new Catalogue.Entry(r.name, name, cat, null));
        }
        List<Catalogue.Entry> tiles = new ArrayList<>();
        for(Raw r : raw.values()) {
            if(!r.name.startsWith(TILES) || !r.tileset)
                continue;
            String rest = r.name.substring(TILES.length());
            boolean paving = rest.startsWith("paving/");
            String name = Catalogue.prettify(r.name);
            tiles.add(new Catalogue.Entry(r.name, name, paving ? "Paving" : "Ground", r.name));
        }
        tiles.sort(Comparator.comparing((Catalogue.Entry e) -> !e.category.equals("Paving")).thenComparing(e -> e.name));
        Comparator<Catalogue.Entry> order = Comparator.comparing((Catalogue.Entry e) -> e.category).thenComparing(e -> e.name);
        build.sort(order);
        objects.sort(order);
        return(new Catalogue(build, objects, tiles));
    }

    /** The object a blueprint places: a gfx/terobjs resource named like the blueprint, the shallowest if several. */
    static String objectFor(String bp, Map<String, List<String>> bybase) {
        List<String> c = bybase.get(RENAMES.getOrDefault(bp, bp));
        if(c == null || c.isEmpty())
            return(null);
        return(c.stream().min(Comparator.comparingInt((String s) -> s.split("/").length).thenComparing(s -> s)).get());
    }

    /** "Buildings › Walls" from the chain of parent menu entries, excluding the root build menu itself. */
    static String menupath(Raw r, Map<String, Raw> raw) {
        LinkedList<String> path = new LinkedList<>();
        String p = r.parent;
        for(int guard = 0; p != null && guard < 8; guard++) {
            Raw pr = raw.get(p);
            if(pr == null || pr.title == null || !p.startsWith(BLD))
                break;
            path.addFirst(pr.title);
            p = pr.parent;
        }
        return(path.isEmpty() ? "Build" : String.join(" › ", path));
    }

    /* ---------- persistence ---------- */

    static JSONObject tojson(Map<String, Raw> raw) {
        JSONArray a = new JSONArray();
        for(Raw r : raw.values()) {
            JSONObject o = new JSONObject();
            o.put("n", r.name);
            if(r.model) o.put("m", 1);
            if(r.tileset) o.put("ts", 1);
            if(r.tooltip != null) o.put("t", r.tooltip);
            if(r.parent != null) o.put("p", r.parent);
            if(r.title != null) o.put("a", r.title);
            if(r.bp != null) o.put("b", r.bp);
            a.put(o);
        }
        JSONObject ret = new JSONObject();
        ret.put("format", FORMAT);
        ret.put("res", a);
        return(ret);
    }

    static Map<String, Raw> fromjson(JSONObject o) {
        if(o.optInt("format") != FORMAT)
            return(null);
        Map<String, Raw> ret = new HashMap<>();
        JSONArray a = o.getJSONArray("res");
        for(int i = 0; i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            Raw r = new Raw(e.getString("n"));
            r.model = e.optInt("m") == 1;
            r.tileset = e.optInt("ts") == 1;
            r.tooltip = e.optString("t", null);
            r.parent = e.optString("p", null);
            r.title = e.optString("a", null);
            r.bp = e.optString("b", null);
            ret.put(r.name, r);
        }
        return(ret);
    }
}
