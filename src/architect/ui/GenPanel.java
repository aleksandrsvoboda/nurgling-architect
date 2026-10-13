package architect.ui;

import architect.gen.WorldGen;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Start screen's "Generate a new world" options: style, seed and forests, with a top-down preview that
 * redraws in the background whenever an option changes.
 */
public class GenPanel extends Widget {
    public WorldGen.Style style = WorldGen.Style.HILLS;
    public long seed = new Random().nextInt(1000000);
    public boolean trees = true;
    public int size = 10;
    private final Map<WorldGen.Style, Button> sbtn = new EnumMap<>(WorldGen.Style.class);
    private final TextEntry seedin;
    private final Button treesb;
    private volatile BufferedImage img = null;
    private volatile String key = null;
    private Tex tex = null;
    private String texkey = null;
    private Thread worker = null;
    private final Map<String, Color> tilecol = new HashMap<>();
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    private Tex seedlbl = null;
    private boolean lasttrees = false;

    public GenPanel(Coord sz) {
        super(sz);
        int x = 0, bw = UI.scale(118);
        for(WorldGen.Style s : WorldGen.Style.values()) {
            sbtn.put(s, add(new Button(bw, s.label, false, () -> style = s), x, 0));
            x += bw + UI.scale(4);
        }
        int y = UI.scale(32);
        seedin = add(new TextEntry(UI.scale(120), Long.toString(seed)) {
            protected void changed() {
                super.changed();
                try {
                    seed = Long.parseLong(text().trim());
                } catch(NumberFormatException e) {
                    seed = text().hashCode();
                }
            }
        }, UI.scale(44), y + UI.scale(3));
        add(new Button(UI.scale(90), "Random", false, () -> {
            seed = new Random().nextInt(1000000);
            seedin.settext(Long.toString(seed));
        }), UI.scale(172), y);
        treesb = add(new Button(UI.scale(150), "", false, () -> trees = !trees), UI.scale(272), y);
    }

    public WorldGen make() {
        return(new WorldGen(seed, style, size));
    }

    private Color color(String tileset) {
        return(tilecol.computeIfAbsent(tileset, n -> {
            try {
                Resource.Image ir = Resource.remote().loadwait(n).layer(Resource.imgc);
                if(ir == null)
                    return(Color.GRAY);
                BufferedImage i = ir.img;
                long r = 0, g = 0, b = 0, c = 0;
                for(int y = 0; y < i.getHeight(); y += 2)
                    for(int x = 0; x < i.getWidth(); x += 2) {
                        int p = i.getRGB(x, y);
                        r += (p >> 16) & 0xff; g += (p >> 8) & 0xff; b += p & 0xff; c++;
                    }
                return(new Color((int)(r / c), (int)(g / c), (int)(b / c)));
            } catch(Resource.LoadException | Resource.BadResourceException e) {
                return(Color.GRAY);
            }
        }));
    }

    /** Draws a preview of gen at res x res pixels, shaded by slope. */
    private BufferedImage render(WorldGen gen, int res) {
        BufferedImage out = new BufferedImage(res, res, BufferedImage.TYPE_INT_RGB);
        double tiles = gen.size * MCache.cmaps.x, step = tiles / res;
        double[] prevrow = new double[res];
        for(int py = 0; py < res; py++) {
            if(Thread.currentThread().isInterrupted())
                return(null);
            double prev = Double.NaN;
            for(int px = 0; px < res; px++) {
                Object[] s = gen.sample(px * step, py * step);
                double h = (Double)s[1];
                Color c = color((String)s[0]);
                double up = (py > 0) ? prevrow[px] : h, left = Double.isNaN(prev) ? h : prev;
                double f = Utils.clip(1 + ((up - h) + (left - h)) / Math.max(step, 1) * 0.08, 0.6, 1.4);
                out.setRGB(px, py, new Color((int)Utils.clip(c.getRed() * f, 0, 255), (int)Utils.clip(c.getGreen() * f, 0, 255),
                                             (int)Utils.clip(c.getBlue() * f, 0, 255)).getRGB());
                prevrow[px] = h;
                prev = h;
            }
        }
        return(out);
    }

    public void tick(double dt) {
        String k = style + "/" + seed + "/" + size;
        if(!k.equals(key)) {
            key = k;
            img = null;
            if(worker != null)
                worker.interrupt();
            WorldGen gen = make();
            worker = new HackThread(() -> {
                BufferedImage i = render(gen, 320);
                if(i != null && k.equals(key))
                    img = i;
            }, "Architect gen preview");
            worker.setDaemon(true);
            worker.start();
        }
        if(treesb.text == null || lasttrees != trees) {
            lasttrees = trees;
            treesb.change(trees ? "Forests: with trees" : "Forests: no trees");
        }
        super.tick(dt);
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.infoBg);
        g.frect(Coord.z, sz);
        g.chcolor();
        if(seedlbl == null)
            seedlbl = fnd.render("Seed", Color.WHITE).tex();
        g.image(seedlbl, Coord.of(0, UI.scale(36)));
        int top = UI.scale(68);
        Coord area = sz.sub(0, top);
        int side = Math.min(area.x, area.y);
        Coord ul = Coord.of((sz.x - side) / 2, top);
        BufferedImage i = img;
        if(i == null) {
            g.atext("Generating preview...", ul.add(side / 2, side / 2), 0.5, 0.5);
        } else {
            if(tex == null || !key.equals(texkey)) {
                if(tex != null)
                    tex.dispose();
                tex = new TexI(i);
                texkey = key;
            }
            g.image(tex, ul, Coord.of(side, side));
        }
        g.chcolor(NStyle.border);
        g.rect(ul, Coord.of(side, side));
        g.chcolor();
        super.draw(g);
        Button on = sbtn.get(style);
        g.chcolor(NStyle.border);
        g.rect(on.c, on.sz);
        g.chcolor();
    }
}
