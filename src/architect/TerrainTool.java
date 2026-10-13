package architect;

import architect.edit.Plan;
import architect.world.*;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;

/**
 * Terrain mode: reshaping and repaving the plan's own copy of the ground (purely visual, nothing exported).
 *
 * Dig/fill: click or drag fills one unit (0.1 m) per step under the brush, Shift digs. Level: drag a rectangle;
 * on release it is levelled to the height where the drag began (Ctrl: to the average), like the in-game survey,
 * with the soil to dig/fill shown while dragging. Smooth: drag to even out bumps. Paint: drag to lay the chosen
 * ground or paving; Alt+click picks up the tile under the cursor. [ and ] change the brush size.
 * Each stroke is one undo step.
 */
public class TerrainTool {
    public enum Mode {
        RAISE("Dig / fill"), LEVEL("Level"), SMOOTH("Smooth"), PAINT("Paint ground");
        public final String label;
        Mode(String label) {this.label = label;}
    }

    public static final double STEP = 0.07;   /* seconds between brush applications while dragging */
    private final PlannerView mv;
    private final TerrainStore store;
    private final LocalWorldTransport transport;
    private final Plan plan;
    public boolean on = false;
    public Mode mode = Mode.RAISE;
    public int radius = 2;
    public String paint = "gfx/tiles/paving/graybrick";

    private boolean down = false;
    private int mods;
    private Coord2d hovermc = null;
    private double lastapply = 0;
    private Coord levelfrom = null, levelto = null;
    private final Map<Coord, TerrainStore.TGrid> before = new HashMap<>();
    private final Set<Coord> refresh = new HashSet<>();
    private MCache.Overlay draft = null;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12).aa(true);
    private Tex leveltex = null;
    private String leveltext = null;

    public TerrainTool(PlannerView mv, TerrainStore store, LocalWorldTransport transport) {
        this.mv = mv;
        this.store = store;
        this.transport = transport;
        this.plan = mv.plan;
    }

    private static Coord vertex(Coord2d mc) {
        return(mc.div(MCache.tilesz).round());
    }

    private static Coord tile(Coord2d mc) {
        return(mc.floor(MCache.tilesz));
    }

    /* ---------- stroke bookkeeping ---------- */

    private void touch(Coord gc) {
        if(gc == null)
            return;
        if(!before.containsKey(gc))
            before.put(gc, store.save(gc));
        refresh.add(gc);
    }

    private void endstroke() {
        if(before.isEmpty())
            return;
        Map<Coord, TerrainStore.TGrid> b = new HashMap<>(before), a = new HashMap<>();
        for(Coord gc : b.keySet())
            a.put(gc, store.save(gc));
        before.clear();
        String name = mode.label;
        plan.custom(name, () -> restore(b), () -> restore(a));
    }

    private void restore(Map<Coord, TerrainStore.TGrid> states) {
        for(Map.Entry<Coord, TerrainStore.TGrid> e : states.entrySet())
            store.restore(e.getKey(), e.getValue());
        transport.refresh(states.keySet());
    }

    /** Sends edited grids to the view, at most a few times a second. Call every tick. */
    private double lastflush = 0;
    public void tick() {
        if(!refresh.isEmpty() && Utils.rtime() - lastflush > 0.1) {
            lastflush = Utils.rtime();
            transport.refresh(new ArrayList<>(refresh));
            refresh.clear();
        }
    }

    /* ---------- brushes ---------- */

    private List<Coord> vertices(Coord centre) {
        List<Coord> ret = new ArrayList<>();
        int r = Math.max(radius, 0);
        for(int y = -r; y <= r; y++)
            for(int x = -r; x <= r; x++)
                if(x * x + y * y <= r * r + r)
                    ret.add(centre.add(x, y));
        return(ret);
    }

    private void apply(Coord2d mc) {
        switch(mode) {
        case RAISE: {
            float d = ((mods & UI.MOD_SHIFT) != 0) ? -TerrainStore.UNIT : TerrainStore.UNIT;
            for(Coord v : vertices(vertex(mc))) {
                float z = store.vz(v);
                if(!Float.isNaN(z))
                    set(v, z + d);
            }
            break;
        }
        case SMOOTH: {
            List<Coord> vs = vertices(vertex(mc));
            Map<Coord, Float> nz = new HashMap<>();
            for(Coord v : vs) {
                float sum = 0;
                int n = 0;
                for(int y = -1; y <= 1; y++)
                    for(int x = -1; x <= 1; x++) {
                        float z = store.vz(v.add(x, y));
                        if(!Float.isNaN(z)) {sum += z; n++;}
                    }
                if(n > 0 && !Float.isNaN(store.vz(v)))
                    nz.put(v, sum / n);
            }
            for(Map.Entry<Coord, Float> e : nz.entrySet())
                set(e.getKey(), e.getValue());
            break;
        }
        case PAINT: {
            Coord t0 = tile(mc);
            int r = Math.max(radius - 1, 0);
            for(int y = -r; y <= r; y++)
                for(int x = -r; x <= r; x++)
                    if(x * x + y * y <= r * r + r) {
                        Coord t = t0.add(x, y);
                        String cur = store.tile(t);
                        if(cur != null && !cur.equals(paint)) {
                            touch(t.div(MCache.cmaps));
                            store.settile(t, paint);
                        }
                    }
            break;
        }
        default:
        }
    }

    private void set(Coord v, float z) {
        touch(v.div(MCache.cmaps));
        store.setvz(v, z);
    }

    /** Level target and soil for the rectangle between two tiles: {target, dig, fill}. */
    private float[] level(Coord a, Coord b, boolean average) {
        Coord ul = Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y)), br = Coord.of(Math.max(a.x, b.x) + 1, Math.max(a.y, b.y) + 1);
        float target = store.vz(vertex(Coord2d.of(a).mul(MCache.tilesz)));
        if(average) {
            double s = 0;
            int n = 0;
            for(int y = ul.y; y <= br.y; y++)
                for(int x = ul.x; x <= br.x; x++) {
                    float z = store.vz(Coord.of(x, y));
                    if(!Float.isNaN(z)) {s += z; n++;}
                }
            if(n > 0)
                target = (float)(s / n);
        }
        target = Math.round(target / TerrainStore.UNIT) * TerrainStore.UNIT;
        long dig = 0, fill = 0;
        for(int y = ul.y; y <= br.y; y++)
            for(int x = ul.x; x <= br.x; x++) {
                float z = store.vz(Coord.of(x, y));
                if(Float.isNaN(z))
                    continue;
                int d = Math.round((target - z) / TerrainStore.UNIT);
                if(d > 0) fill += d; else dig -= d;
            }
        return(new float[] {target, dig, fill});
    }

    private void applylevel(Coord a, Coord b, boolean average) {
        float target = level(a, b, average)[0];
        Coord ul = Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y)), br = Coord.of(Math.max(a.x, b.x) + 1, Math.max(a.y, b.y) + 1);
        for(int y = ul.y; y <= br.y; y++)
            for(int x = ul.x; x <= br.x; x++)
                if(!Float.isNaN(store.vz(Coord.of(x, y))))
                    set(Coord.of(x, y), target);
    }

    /* ---------- input ---------- */

    private void at(Coord c, java.util.function.Consumer<Coord2d> f) {
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                f.accept(mc);
            }
        }.run();
    }

    public void press(Coord c, int mods) {
        this.mods = mods;
        down = true;
        at(c, mc -> {
            if(!down)
                return;
            hovermc = mc;
            if((mods & UI.MOD_META) != 0 && mode == Mode.PAINT) {
                String t = store.tile(tile(mc));
                if(t != null)
                    paint = t;
                down = false;
                return;
            }
            if(mode == Mode.LEVEL) {
                levelfrom = levelto = tile(mc);
            } else {
                apply(mc);
                lastapply = Utils.rtime();
            }
        });
    }

    @SuppressWarnings("deprecation")
    public void move(Coord c, int mods) {
        this.mods = mods;
        at(c, mc -> {
            hovermc = mc;
            if(!down)
                return;
            if(mode == Mode.LEVEL) {
                if(levelfrom == null)
                    return;
                levelto = tile(mc);
                Area a = new Area(Coord.of(Math.min(levelfrom.x, levelto.x), Math.min(levelfrom.y, levelto.y)),
                                  Coord.of(Math.max(levelfrom.x, levelto.x) + 1, Math.max(levelfrom.y, levelto.y) + 1));
                if(draft == null)
                    draft = mv.glob.map.new Overlay(a, AreaTool.draftinfo);
                else
                    draft.update(a);
            } else if(Utils.rtime() - lastapply >= STEP) {
                apply(mc);
                lastapply = Utils.rtime();
            }
        });
    }

    @SuppressWarnings("deprecation")
    public void release() {
        down = false;
        if(mode == Mode.LEVEL && levelfrom != null && levelto != null)
            applylevel(levelfrom, levelto, (mods & UI.MOD_CTRL) != 0);
        if(draft != null) {
            draft.destroy();
            draft = null;
        }
        levelfrom = levelto = null;
        endstroke();
    }

    public boolean dragging() {
        return(down);
    }

    public boolean key(Widget.KeyDownEvent ev) {
        switch(ev.code) {
        case KeyEvent.VK_OPEN_BRACKET:
            radius = Math.max(0, radius - 1);
            return(true);
        case KeyEvent.VK_CLOSE_BRACKET:
            radius = Math.min(25, radius + 1);
            return(true);
        case KeyEvent.VK_ESCAPE:
            on = false;
            return(true);
        }
        return(false);
    }

    /* ---------- drawing ---------- */

    public void draw(GOut g) {
        if(!on || hovermc == null)
            return;
        if(mode == Mode.LEVEL && levelfrom != null && levelto != null) {
            float[] l = level(levelfrom, levelto, (mods & UI.MOD_CTRL) != 0);
            String t = String.format("Level to %.1f m   ·   dig %d   ·   fill %d", l[0] / TerrainStore.UNIT / 10, (int)l[1], (int)l[2]);
            if(!t.equals(leveltext)) {
                leveltext = t;
                leveltex = Text.renderstroked(t, NStyle.border, Color.BLACK, fnd).tex();
            }
            Coord p = mv.ui.mc.sub(mv.rootpos());
            g.image(leveltex, p.add(UI.scale(16), UI.scale(8)));
            return;
        }
        /* Brush outline on the ground. */
        double r = Math.max(radius, 0.5) * MCache.tilesz.x;
        Coord prev = null;
        g.chcolor(NStyle.border);
        for(int i = 0; i <= 32; i++) {
            double a = Math.PI * 2 * i / 32;
            Coord2d p = hovermc.add(Math.cos(a) * r, Math.sin(a) * r);
            Coord s;
            try {
                Coord3f sc = mv.screenxf(Coord3f.of((float)p.x, (float)p.y, (float)mv.glob.map.getcz(p)));
                s = Coord.of(Math.round(sc.x), Math.round(sc.y));
            } catch(Loading l) {
                prev = null;
                continue;
            }
            if(prev != null)
                g.line(prev, s, 2);
            prev = s;
        }
        g.chcolor();
    }
}
