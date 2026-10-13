package architect;

import architect.res.*;

import java.nio.file.*;

/** Command-line checks for development: java -cp build/architect.jar architect.DevTools index */
public class DevTools {
    public static void main(String[] args) throws Exception {
        ArchitectEnv.init();
        if(args.length > 0 && args[0].equals("index")) {
            long t0 = System.nanoTime();
            Path home = ArchitectEnv.home;
            if(args.length > 1 && args[1].equals("--fresh"))
                Files.deleteIfExists(home.resolve("catalogue.json"));
            Catalogue cat = new CatalogueIndexer(ArchitectEnv.gameData, ArchitectEnv.resJar(), ArchitectEnv.pack, home, System.out::println).load();
            System.out.printf("build entries: %d (%d with a model), objects: %d, %.1f s%n",
                              cat.build.size(), cat.build.stream().filter(Catalogue.Entry::placeable).count(),
                              cat.objects.size(), (System.nanoTime() - t0) / 1e9);
            for(Catalogue.Entry e : cat.search(cat.build, args.length > 2 ? args[2] : "cupboard"))
                System.out.println("  build: " + e.category + " / " + e);
        }
        if(args.length > 0 && args[0].equals("gen")) {
            long seed = (args.length > 1) ? Long.parseLong(args[1]) : 42;
            for(architect.gen.WorldGen.Style st : architect.gen.WorldGen.Style.values()) {
                architect.gen.WorldGen g = new architect.gen.WorldGen(seed, st, 10);
                int res = 400;
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(res, res, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.util.Map<String, java.awt.Color> cols = new java.util.HashMap<>();
                java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
                double step = 1000.0 / res, min = 1e9, max = -1e9;
                double[] prev = new double[res];
                for(int y = 0; y < res; y++)
                    for(int x = 0; x < res; x++) {
                        Object[] smp = g.sample(x * step, y * step);
                        String t = (String)smp[0];
                        double h = (Double)smp[1];
                        min = Math.min(min, h); max = Math.max(max, h);
                        counts.merge(t, 1, Integer::sum);
                        java.awt.Color c = cols.computeIfAbsent(t, n -> {
                            try {
                                java.awt.image.BufferedImage i = haven.Resource.remote().loadwait(n).layer(haven.Resource.imgc).img;
                                return(new java.awt.Color(i.getRGB(i.getWidth() / 2, i.getHeight() / 2)));
                            } catch(RuntimeException e) {
                                return(java.awt.Color.MAGENTA);
                            }
                        });
                        double up = (y > 0) ? prev[x] : h;
                        double f = Math.max(0.6, Math.min(1.4, 1 + (up - h) * 0.03));
                        img.setRGB(x, y, new java.awt.Color((int)Math.min(255, c.getRed() * f), (int)Math.min(255, c.getGreen() * f), (int)Math.min(255, c.getBlue() * f)).getRGB());
                        prev[x] = h;
                    }
                java.nio.file.Path out = java.nio.file.Paths.get("build", "gen-" + st.name().toLowerCase() + ".png");
                javax.imageio.ImageIO.write(img, "png", out.toFile());
                System.out.printf("%s: heights %.0f..%.0f (%.1f m), trees %d, tiles %s%n", st, min, max, (max - min) / 11, g.trees().size(), counts);
            }
        }
        if(args.length > 0 && args[0].equals("pack"))
            pack();
        if(args.length > 0 && args[0].equals("verify"))
            verify();
        System.exit(0);
    }

    /** What the planner draws: ground, objects (and the inventory art some of them reuse), effects, build menu, code libraries. */
    static final String[] PACKED = {"gfx/tiles/", "gfx/terobjs/", "gfx/invobjs/", "gfx/fx/", "paginae/bld/", "lib/", "ui/provinces"};
    static final long JARMAX = 85L << 20;

    /** Builds pack/*.jar from this machine's game cache (read-only). */
    static void pack() throws Exception {
        java.nio.file.Path data = ArchitectEnv.gameData, out = ArchitectEnv.root.resolve("pack");
        java.util.TreeMap<String, java.nio.file.Path> found = new java.util.TreeMap<>();
        try(java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(data)) {
            for(java.nio.file.Path p : (Iterable<java.nio.file.Path>)s::iterator) {
                try(java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(p), 512))) {
                    if(in.readByte() != 1 || !in.readUTF().equals(ArchitectEnv.RES_CACHE_ID.toString()))
                        continue;
                    String n = in.readUTF();
                    if(!n.startsWith("res/"))
                        continue;
                    n = n.substring(4);
                    for(String pre : PACKED)
                        if(n.startsWith(pre))
                            found.put(n, p);
                } catch(java.io.IOException e) {
                    /* not a cache entry */
                }
            }
        }
        /* Startup opened the current pack; Windows will not delete open files. */
        for(java.util.jar.JarFile j : ArchitectEnv.pack.jars)
            j.close();
        java.nio.file.Files.createDirectories(out);
        try(java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(out)) {
            for(java.nio.file.Path old : (Iterable<java.nio.file.Path>)s::iterator)
                if(old.toString().endsWith(".jar"))
                    java.nio.file.Files.delete(old);
        }
        int jarno = 0, count = 0;
        long size = 0, total = 0;
        java.util.jar.JarOutputStream jar = null;
        for(java.util.Map.Entry<String, java.nio.file.Path> e : found.entrySet()) {
            byte[] res = ArchitectEnv.resCache.read("res/" + e.getKey());
            if(res == null)
                continue;
            if(jar == null || size + res.length > JARMAX) {
                if(jar != null)
                    jar.close();
                jar = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(out.resolve(String.format("architect-pack-%d.jar", ++jarno))));
                jar.setLevel(9);
                size = 0;
            }
            java.util.jar.JarEntry je = new java.util.jar.JarEntry(PackSource.PREFIX + e.getKey() + ".res");
            je.setTime(0);
            jar.putNextEntry(je);
            jar.write(res);
            jar.closeEntry();
            size += res.length;
            total += res.length;
            count++;
        }
        if(jar != null)
            jar.close();
        System.out.printf("packed %d resources (%.1f MB raw) into %d jars in %s%n", count, total / 1048576.0, jarno, out);
    }

    /** Loads every catalogue object and tile from the pack alone (run with -Darchitect.gamedata=<empty dir>). */
    static void verify() throws Exception {
        Catalogue cat = new CatalogueIndexer(null, ArchitectEnv.resJar(), ArchitectEnv.pack, java.nio.file.Files.createTempDirectory("verify"), s -> {}).load();
        java.util.List<String> names = new java.util.ArrayList<>();
        for(Catalogue.Entry e : cat.objects) names.add(e.res);
        for(Catalogue.Entry e : cat.tiles) names.add(e.res);
        int ok = 0;
        java.util.Map<String, Integer> missing = new java.util.TreeMap<>();
        for(String n : names) {
            try {
                haven.Resource r = haven.Resource.remote().loadwait(n);
                /* Touch what the renderer needs: dependent materials and code libraries resolve lazily. */
                for(haven.Resource.CodeEntry ce : r.layers(haven.Resource.CodeEntry.class))
                    haven.Loading.waitfor(ce::loader);
                ok++;
            } catch(RuntimeException e) {
                String why = String.valueOf(e.getMessage());
                missing.merge(why.length() > 90 ? why.substring(0, 90) : why, 1, Integer::sum);
            }
        }
        System.out.printf("loaded %d of %d (objects %d, tiles %d)%n", ok, names.size(), cat.objects.size(), cat.tiles.size());
        missing.forEach((k, v) -> System.out.println("  " + v + "x " + k));
    }
}
