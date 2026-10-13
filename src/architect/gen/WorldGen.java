package architect.gen;

import architect.world.*;
import haven.*;

import java.util.*;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;

/**
 * Generates Haven-looking terrain from a seed: heights from fractal noise per style, ground types picked
 * by height, slope, moisture and a forest mask from the tilesets you actually have offline. The same seed
 * and style always give the same world. Heights are world units (1.1 = one survey unit = 0.1 m).
 */
public class WorldGen implements WorldSource {
    public enum Style {
        PLAINS("Plains", 22, -1.0, 0.35, 0.10),
        HILLS("Rolling hills", 150, -1.0, 0.55, 0.30),
        LAKESIDE("Lakeside", 70, -0.10, 0.40, 0.25),
        FOREST("Forest", 55, -1.0, 0.45, 0.65),
        VALLEY("Mountain valley", 420, -1.0, 0.5, 0.25);

        public final String label;
        /** Height swing in world units. */
        final double amp;
        /** Water where the height noise is below this (in [-1,1]); -1 = no lakes. */
        final double water;
        /** Hills per grid. */
        final double freq;
        /** Share of land under forest. */
        final double forest;

        Style(String label, double amp, double water, double freq, double forest) {
            this.label = label; this.amp = amp; this.water = water; this.freq = freq; this.forest = forest;
        }
    }

    public final long seed;
    public final Style style;
    public final int size;
    private final Noise hn, mn, fn, vn;
    private final Map<String, String> tiles = new HashMap<>();

    /** Tileset choices, first available wins; resolved once against what is loadable offline. */
    private static final String[][] PALETTE = {
        {"deep", "gfx/tiles/deep", "gfx/tiles/water"},
        {"water", "gfx/tiles/water"},
        {"beach", "gfx/tiles/beach", "gfx/tiles/dirt"},
        {"rock", "gfx/tiles/mountain", "gfx/tiles/highground", "gfx/tiles/dirt"},
        {"high", "gfx/tiles/highground", "gfx/tiles/heath", "gfx/tiles/grass"},
        {"swamp", "gfx/tiles/swamp", "gfx/tiles/bog", "gfx/tiles/fen", "gfx/tiles/grass"},
        {"bog", "gfx/tiles/bog", "gfx/tiles/fen", "gfx/tiles/swamp", "gfx/tiles/grass"},
        {"forest", "gfx/tiles/wald", "gfx/tiles/leaf", "gfx/tiles/grass"},
        {"forest2", "gfx/tiles/leaf", "gfx/tiles/beechgrove", "gfx/tiles/wald", "gfx/tiles/grass"},
        {"forest3", "gfx/tiles/blackwood", "gfx/tiles/dryweald", "gfx/tiles/wald", "gfx/tiles/grass"},
        {"grass", "gfx/tiles/grass"},
        {"grass2", "gfx/tiles/greensward", "gfx/tiles/grass"},
        {"heath", "gfx/tiles/heath", "gfx/tiles/grass"},
        {"meadow", "gfx/tiles/flowermeadow", "gfx/tiles/grass"},
        {"steppe", "gfx/tiles/bullgrass", "gfx/tiles/hardsteppe", "gfx/tiles/heath", "gfx/tiles/grass"},
    };

    public WorldGen(long seed, Style style, int size) {
        this.seed = seed;
        this.style = style;
        this.size = size;
        this.hn = new Noise(seed);
        this.mn = new Noise(seed * 31 + 7);
        this.fn = new Noise(seed * 131 + 3);
        this.vn = new Noise(seed * 7 + 11);
        for(String[] p : PALETTE) {
            String pick = null;
            for(int i = 1; i < p.length && pick == null; i++)
                if(available(p[i]))
                    pick = p[i];
            tiles.put(p[0], (pick != null) ? pick : "gfx/tiles/grass");
        }
    }

    private static final Map<String, Boolean> avail = new HashMap<>();
    static boolean available(String tileset) {
        synchronized(avail) {
            return(avail.computeIfAbsent(tileset, n -> {
                try {
                    return(Resource.remote().loadwait(n).layer(Tileset.class) != null);
                } catch(Resource.LoadException | Resource.BadResourceException e) {
                    return(false);
                }
            }));
        }
    }

    /* ---------- the fields ---------- */

    /** Raw height noise in about [-1, 1] at tile coordinate (x, y). */
    private double hnoise(double x, double y) {
        double f = style.freq / cmaps.x;
        double n = hn.fbm(x * f, y * f, 6);
        if(style == Style.VALLEY) {
            /* Ridged mountains rising away from a gentle valley floor through the middle. */
            double c = size * cmaps.x / 2.0;
            double d = Math.hypot(x - c, y - c) / c;
            double edge = Utils.clip((d - 0.50) / 0.50, 0, 1);
            edge = edge * edge * (3 - 2 * edge);
            double ridge = hn.ridged(x * f * 1.4 + 17, y * f * 1.4 + 5, 5);
            return(n * 0.08 + edge * (ridge * 1.2 + 0.1) - 0.15);
        }
        return(n);
    }

    private double height(double x, double y) {
        return(hnoise(x, y) * style.amp);
    }

    private double waterlevel() {
        return((style.water <= -1) ? Double.NEGATIVE_INFINITY : style.water * style.amp);
    }

    private double moisture(double x, double y) {
        return(mn.fbm(x / 380.0, y / 380.0, 3));
    }

    private boolean forested(double x, double y) {
        double t = fn.fbm(x / 220.0, y / 220.0, 4);
        /* fbm is roughly normal around 0; map the wanted share to a threshold. */
        return(t > 0.45 - style.forest * 0.9);
    }

    private String ground(double x, double y, double h, double slope, double wl) {
        if(h < wl - 30)
            return(tiles.get("deep"));
        if(h < wl)
            return(tiles.get("water"));
        if(h < wl + 4.5 && slope < 4)
            return(tiles.get("beach"));
        if(slope > 7.5)
            return(tiles.get("rock"));
        double m = moisture(x, y), v = vn.fbm(x / 140.0, y / 140.0, 3);
        if(style.amp > 100 && h > style.amp * 0.55)
            return(tiles.get((slope > 4.5) ? "rock" : "high"));
        if(m > 0.32 && slope < 2.5 && h < style.amp * 0.15)
            return(tiles.get((v > 0) ? "swamp" : "bog"));
        if(forested(x, y))
            return(tiles.get((v > 0.18) ? "forest2" : (v < -0.22) ? "forest3" : "forest"));
        if(v > 0.25)
            return(tiles.get("meadow"));
        if(m < -0.3)
            return(tiles.get("steppe"));
        if(v < -0.2)
            return(tiles.get("heath"));
        return(tiles.get((m > 0.1) ? "grass2" : "grass"));
    }

    /** Ground and height at a tile position, for previews: {tileset, height}. */
    public Object[] sample(double x, double y) {
        double a = height(x, y), b = height(x + 1, y), c = height(x, y + 1), d = height(x + 1, y + 1);
        double h = (a + b + c + d) / 4;
        double slope = Math.max(Math.max(a, b), Math.max(c, d)) - Math.min(Math.min(a, b), Math.min(c, d));
        return(new Object[] {ground(x + 0.5, y + 0.5, h, slope, waterlevel()), h});
    }

    /* ---------- WorldSource ---------- */

    public GridData grid(Coord gc) {
        if(gc.x < 0 || gc.y < 0 || gc.x >= size || gc.y >= size)
            return(null);
        int n = cmaps.x;
        double wl = waterlevel();
        /* Heights for this grid's vertices plus one more row/column, so tile slopes see their far corners. */
        double[] hz = new double[(n + 1) * (n + 1)];
        for(int y = 0; y <= n; y++)
            for(int x = 0; x <= n; x++)
                hz[x + y * (n + 1)] = height(gc.x * n + x, gc.y * n + y);
        List<String> sets = new ArrayList<>();
        int[] tl = new int[n * n];
        float[] z = new float[n * n];
        for(int y = 0; y < n; y++) {
            for(int x = 0; x < n; x++) {
                double a = hz[x + y * (n + 1)], b = hz[x + 1 + y * (n + 1)], c = hz[x + (y + 1) * (n + 1)], d = hz[x + 1 + (y + 1) * (n + 1)];
                double h = (a + b + c + d) / 4;
                double slope = Math.max(Math.max(a, b), Math.max(c, d)) - Math.min(Math.min(a, b), Math.min(c, d));
                String t = ground(gc.x * n + x + 0.5, gc.y * n + y + 0.5, h, slope, wl);
                int i = sets.indexOf(t);
                if(i < 0) {
                    sets.add(t);
                    i = sets.size() - 1;
                }
                tl[x + y * n] = i;
                /* Water surfaces are flat at the water level. */
                z[x + y * n] = (float)((a < wl) ? wl : a);
            }
        }
        int[] prio = new int[sets.size()];
        Arrays.fill(prio, -1);
        long id = -(seed * 1000003L + gc.x * 4099L + gc.y) - 1;
        return(new GridData(id, sets.toArray(new String[0]), prio, tl, z));
    }

    public Coord2d spawn() {
        return(Coord2d.of(cmaps.mul(size, size).div(2)).mul(tilesz).add(tilesz.div(2)));
    }

    public Area bounds() {
        return(Area.sized(Coord.z, Coord.of(size, size)));
    }

    public String describe() {
        return(String.format("Generated %s, seed %d, %dx%d grids", style.label, seed, size, size));
    }

    /* ---------- trees ---------- */

    public static final String[] TREES = {
        "gfx/terobjs/trees/oak", "gfx/terobjs/trees/birch", "gfx/terobjs/trees/spruce", "gfx/terobjs/trees/pine",
        "gfx/terobjs/trees/beech", "gfx/terobjs/trees/maple", "gfx/terobjs/trees/alder", "gfx/terobjs/trees/larch",
        "gfx/terobjs/trees/linden", "gfx/terobjs/trees/aspen", "gfx/terobjs/trees/fir", "gfx/terobjs/trees/elm",
    };
    public static final int MAXTREES = 6000;

    /** Tree positions for the forests: a jittered grid, the kind of tree varying by area. {res, x, y, angle}. */
    public List<Object[]> trees() {
        List<String> kinds = new ArrayList<>();
        for(String t : TREES) {
            try {
                Resource.remote().loadwait(t);
                kinds.add(t);
            } catch(Resource.LoadException | Resource.BadResourceException e) {
                /* not in this cache */
            }
        }
        List<Object[]> ret = new ArrayList<>();
        if(kinds.isEmpty())
            return(ret);
        Random r = new Random(seed ^ 0x77ee5L);
        double wl = waterlevel();
        int step = 3, n = size * cmaps.x;
        for(int y = 1; y < n - 1 && ret.size() < MAXTREES; y += step) {
            for(int x = 1; x < n - 1 && ret.size() < MAXTREES; x += step) {
                double tx = x + r.nextDouble() * step, ty = y + r.nextDouble() * step;
                if(!forested(tx, ty) || r.nextDouble() > 0.55)
                    continue;
                double h = height(tx, ty);
                if(h < wl + 4.5)
                    continue;
                double slope = Math.abs(height(tx + 1, ty) - h) + Math.abs(height(tx, ty + 1) - h);
                if(slope > 7)
                    continue;
                double v = vn.fbm(tx / 60.0, ty / 60.0, 2);
                int k = (int)Math.floor((v + 1) / 2 * kinds.size() * 0.999);
                k = Utils.clip(k + (r.nextDouble() < 0.2 ? r.nextInt(2) : 0), 0, kinds.size() - 1);
                ret.add(new Object[] {kinds.get(k), tx * tilesz.x, ty * tilesz.y, r.nextDouble() * Math.PI * 2});
            }
        }
        return(ret);
    }
}
