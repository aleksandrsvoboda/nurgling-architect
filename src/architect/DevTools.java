package architect;

import architect.res.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Maintainer commands: java -cp app/architect.jar architect.DevTools catalogue|prefetch|gen
 *
 *  catalogue [gamedata]  rebuild src/architect/res/catalogue.json (names only) from a game cache, asking the
 *                        game's resource server for the models of build-menu entries the cache never saw
 *  prefetch              download everything the catalogue uses into the planner's cache
 *  gen [seed]            render each world-generator style to build/gen-*.png
 */
public class DevTools {
    public static void main(String[] args) throws Exception {
        ArchitectEnv.init();
        String cmd = (args.length > 0) ? args[0] : "";
        if(cmd.equals("catalogue"))
            catalogue(args);
        else if(cmd.equals("prefetch"))
            prefetch();
        else if(cmd.equals("probe")) {
            byte[] b = new CatalogueIndexer(null, null, ArchitectEnv.RESURL, System.out::println).fetch(args[1]);
            ResFile f = (b == null) ? null : ResFile.parse(b);
            System.out.println(args[1] + ": " + ((b == null) ? "404" : b.length + " bytes, layers " + ((f == null) ? "unparseable" : f.names)));
            if(f != null && f.action() != null)
                System.out.println("  action: " + java.util.Arrays.toString(f.action().ad));
        } else if(cmd.equals("gen"))
            gen((args.length > 1) ? Long.parseLong(args[1]) : 42);
        else
            System.out.println("usage: DevTools catalogue [gamedata] | prefetch | gen [seed]");
        System.exit(0);
    }

    static void catalogue(String[] args) throws Exception {
        Path data = (args.length > 1) ? Paths.get(args[1]) : ArchitectEnv.gameRoot.resolve("data");
        long t0 = System.nanoTime();
        Catalogue cat = new CatalogueIndexer(data, ArchitectEnv.resJar(), ArchitectEnv.RESURL, System.out::println).make();
        Path out = ArchitectEnv.root.resolve("src/architect/res/catalogue.json");
        Files.write(out, cat.save().getBytes(StandardCharsets.UTF_8));
        System.out.printf("build menu %d (%d with a model), objects %d, tiles %d -> %s (%.0f KB, %.1f s)%n",
                          cat.build.size(), cat.build.stream().filter(Catalogue.Entry::placeable).count(),
                          cat.objects.size(), cat.tiles.size(), out, Files.size(out) / 1024.0, (System.nanoTime() - t0) / 1e9);
    }

    static void prefetch() throws Exception {
        Prefetch p = new Prefetch(Catalogue.shipped(), ArchitectEnv.cache, s -> {});
        int n = p.run();
        System.out.printf("%d resources available offline, %d failed%n", n, p.failed.get());
    }

    static void gen(long seed) throws Exception {
        for(architect.gen.WorldGen.Style st : architect.gen.WorldGen.Style.values()) {
            architect.gen.WorldGen g = new architect.gen.WorldGen(seed, st, 10);
            int res = 400;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(res, res, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.util.Map<String, java.awt.Color> cols = new java.util.HashMap<>();
            double step = 1000.0 / res;
            double[] prev = new double[res];
            for(int y = 0; y < res; y++)
                for(int x = 0; x < res; x++) {
                    Object[] smp = g.sample(x * step, y * step);
                    double h = (Double)smp[1];
                    java.awt.Color c = cols.computeIfAbsent((String)smp[0], n -> {
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
            Path out = ArchitectEnv.root.resolve("build").resolve("gen-" + st.name().toLowerCase() + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(img, "png", out.toFile());
            System.out.println(st + " -> " + out);
        }
    }
}
