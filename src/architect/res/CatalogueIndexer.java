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
 * Maintainer tool behind {@code DevTools catalogue}: builds the shipped {@link Catalogue} (names only) from a
 * game cache (read-only) and the bundled hafen-res.jar, then asks the game's resource server for the models of
 * build-menu entries the cache never saw.
 *
 * The cache is addressed by hashed names, so finding "every object" means reading every file's header once.
 * The server cannot list its contents, so an unseen blueprint's model is found by trying its name in each
 * object folder the cache knows (stonetower -> gfx/terobjs/arch/stonetower).
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

    private final Path gameData, resJar;
    private final java.net.URI resurl;
    private final Consumer<String> progress;

    public CatalogueIndexer(Path gameData, Path resJar, java.net.URI resurl, Consumer<String> progress) {
        this.gameData = gameData;
        this.resJar = resJar;
        this.resurl = resurl;
        this.progress = progress;
    }

    public Catalogue make() throws IOException {
        Map<String, Raw> raw = scan();
        if(resurl != null)
            probe(raw);
        return(build(raw));
    }

    /* ---------- the server ---------- */

    /** A resource from the game's server, or null when it has no such resource. Retries dropped connections. */
    public byte[] fetch(String name) throws IOException {
        IOException last = null;
        for(int attempt = 0; attempt < 5; attempt++) {
            try {
                return(fetch1(name));
            } catch(IOException e) {
                /* The server resets connections under load; a reset is not an answer. */
                last = e;
                try {
                    Thread.sleep(500L << attempt);
                } catch(InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw(new InterruptedIOException("interrupted"));
                }
            }
        }
        throw(last);
    }

    private byte[] fetch1(String name) throws IOException {
        /* haven.Http carries the game client's own TLS trust for the resource server. */
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)haven.Http.open(resurl.resolve(name + ".res").toURL());
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if(code == 404)
            return(null);
        if(code != 200)
            throw(new IOException(name + ": HTTP " + code));
        try(InputStream in = c.getInputStream()) {
            return(in.readAllBytes());
        }
    }

    /** Finds the models of build-menu entries the cache never saw, trying every known object folder. */
    void probe(Map<String, Raw> raw) throws IOException {
        Map<String, List<String>> bybase = bases(raw);
        Map<String, Integer> dirs = new HashMap<>();
        for(String n : raw.keySet())
            if(n.startsWith(OBJ))
                dirs.merge(n.substring(0, n.lastIndexOf('/') + 1), 1, Integer::sum);
        List<String> order = dirs.keySet().stream().sorted(Comparator.comparingInt((String d) -> -dirs.get(d))).collect(Collectors.toList());
        List<Raw> missing = raw.values().stream().filter(r -> r.name.startsWith(BLD) && r.bp != null && objectFor(r.bp, bybase) == null).collect(Collectors.toList());
        AtomicInteger done = new AtomicInteger(), found = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> jobs = new ArrayList<>();
        for(Raw r : missing) {
            jobs.add(pool.submit(() -> {
                String base = RENAMES.getOrDefault(r.bp, r.bp);
                for(String dir : order) {
                    try {
                        byte[] res = fetch(dir + base);
                        if(res == null)
                            continue;
                        Raw got = read(dir + base, res);
                        if(got != null && got.model) {
                            synchronized(raw) {
                                raw.put(got.name, got);
                            }
                            found.incrementAndGet();
                            break;
                        }
                    } catch(IOException e) {
                        progress.accept("  " + dir + base + ": " + e.getMessage());
                    }
                }
                progress.accept(String.format("Probing the server for unseen objects... %d/%d (found %d)", done.incrementAndGet(), missing.size(), found.get()));
                return(null);
            }));
        }
        for(Future<?> f : jobs) {
            try {
                f.get();
            } catch(InterruptedException | ExecutionException e) {
                throw(new IOException("probing failed", e));
            }
        }
        pool.shutdown();
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
        if(gameData != null && Files.isDirectory(gameData)) {
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

    static Map<String, List<String>> bases(Map<String, Raw> raw) {
        Map<String, List<String>> bybase = new HashMap<>();
        for(Raw r : raw.values()) {
            if(r.name.startsWith(OBJ) && r.model) {
                String base = r.name.substring(r.name.lastIndexOf('/') + 1);
                bybase.computeIfAbsent(base, k -> new ArrayList<>()).add(r.name);
            }
        }
        return(bybase);
    }

    static Catalogue build(Map<String, Raw> raw) {
        Map<String, List<String>> bybase = bases(raw);
        List<Catalogue.Entry> objects = new ArrayList<>();
        Map<String, String> objname = new HashMap<>();
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
}
