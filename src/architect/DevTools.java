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
            Catalogue cat = new CatalogueIndexer(ArchitectEnv.gameData, ArchitectEnv.resJar(), home, System.out::println).load();
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
        System.exit(0);
    }
}
