package architect;

import architect.edit.*;
import haven.*;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.function.Predicate;

/**
 * The game's Zone Measure Tool on the plan. "Select area" arms one drag on the ground, which leaves a zone:
 * its tiles tinted in the fill colour with an edge outline, and "W x H" with the planned objects on its tiles
 * at its centre. "Clear selection" arms one click that removes the zone under it; "Clear all" removes every
 * zone. Esc or a right click disarms. Zones are not part of the plan: closing the tool clears them, as in the game.
 */
public class MeasureTool {
    private final PlannerView mv;
    private final Plan plan;

    public enum Mode {NONE, SELECT, CLEAR}
    private Mode armed = Mode.NONE;
    private boolean dragging = false;
    /* The button is down: hit tests answer a frame later, maybe after the release. */
    private boolean held = false;
    private Coord startt, curt;
    private MCache.Overlay draft = null;
    public Color fill = new Color(255, 200, 0, 64), edge = new Color(255, 200, 0, 255);
    /** Shown in the panel: what the tool did last. */
    public String status = "Ready";

    public static class Zone {
        public final Area tiles;
        final MCache.Overlay ol;
        /** Planned objects on the zone's tiles at the last count, by resource, largest first. */
        public List<Map.Entry<String, Integer>> counts = Collections.emptyList();
        public int total = 0;

        Zone(Area tiles, MCache.Overlay ol) {
            this.tiles = tiles;
            this.ol = ol;
        }
    }
    private final List<Zone> zones = new ArrayList<>();
    /* Counts follow the plan: recounted when it or its layers change. */
    private int countseq = -1, countlseq = -1;

    private final Text.Foundry labelfnd = new Text.Foundry(Text.sans, 16).aa(true);
    private final Text.Foundry countfnd = new Text.Foundry(Text.sans, 12).aa(true);
    private final Map<String, Tex> labels = new HashMap<>();
    static final MCache.OverlayInfo draftinfo = info(new Color(255, 200, 0, 64), new Color(255, 200, 0, 255));

    public MeasureTool(PlannerView mv) {
        this.mv = mv;
        this.plan = mv.plan;
    }

    static MCache.OverlayInfo info(Color fill, Color edge) {
        return(new MCache.OverlayInfo() {
            final Material m = new Material(new haven.render.BaseColor(fill), haven.render.States.maskdepth);
            final Material e = new Material(new haven.render.BaseColor(edge), haven.render.States.maskdepth);
            public Collection<String> tags() {return(Collections.singletonList("show"));}
            public Material mat() {return(m);}
            public Material omat() {return(e);}
        });
    }

    /** The tiles from corner tile a to corner tile b, both included. */
    public static Area rect(Coord a, Coord b) {
        return(new Area(Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y)), Coord.of(Math.max(a.x, b.x) + 1, Math.max(a.y, b.y) + 1)));
    }

    /**
     * The objects standing on the tiles (half-open, so an object on the line between two zones counts in one),
     * on shown layers, by resource with counts, largest first.
     */
    public static List<Map.Entry<String, Integer>> count(Collection<PlanObject> objs, Area tiles, Predicate<String> shown) {
        double t = MCache.tilesz.x;
        double x0 = tiles.ul.x * t, y0 = tiles.ul.y * t, x1 = tiles.br.x * t, y1 = tiles.br.y * t;
        Map<String, Integer> counts = new HashMap<>();
        for(PlanObject o : objs) {
            if(o.rc.x >= x0 && o.rc.x < x1 && o.rc.y >= y0 && o.rc.y < y1 && shown.test(o.layer))
                counts.merge(o.res, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> ret = new ArrayList<>(counts.entrySet());
        ret.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return(ret);
    }

    /* ---------- arming ---------- */

    public Mode armed() {
        return(armed);
    }

    public void armselect() {
        mv.measuring();
        armed = Mode.SELECT;
        status = "Drag on the ground to measure...";
    }

    public void armclear() {
        if(zones.isEmpty()) {
            status = "No zones to clear";
            return;
        }
        mv.measuring();
        armed = Mode.CLEAR;
        status = "Click a zone to clear...";
    }

    @SuppressWarnings("deprecation")
    public void disarm() {
        if(armed == Mode.NONE)
            return;
        if(draft != null) {
            draft.destroy();
            draft = null;
        }
        status = (armed == Mode.SELECT) ? "Selection cancelled" : "Ready";
        armed = Mode.NONE;
        dragging = held = false;
        startt = curt = null;
    }

    /* ---------- mouse ---------- */

    public void press(Coord c) {
        Mode m = armed;
        held = true;
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(armed != m)
                    return;
                Coord t = mc.floor(MCache.tilesz);
                if(m == Mode.SELECT) {
                    startt = curt = t;
                    dragging = true;
                    draft = mv.glob.map.new Overlay(rect(startt, curt), draftinfo);
                    if(!held)
                        release();
                } else {
                    clearat(t);
                }
            }
        }.run();
    }

    /** Whether the button is down on a measure (the next release ends it). */
    public boolean dragging() {
        return(held || dragging);
    }

    @SuppressWarnings("deprecation")
    public void drag(Coord c) {
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(!dragging)
                    return;
                Coord t = mc.floor(MCache.tilesz);
                if(!t.equals(curt)) {
                    curt = t;
                    if(draft != null)
                        draft.update(rect(startt, curt));
                }
            }
        }.run();
    }

    @SuppressWarnings("deprecation")
    public void release() {
        held = false;
        if(!dragging)
            return;
        Area r = rect(startt, curt);
        if(draft != null) {
            draft.destroy();
            draft = null;
        }
        dragging = false;
        startt = curt = null;
        armed = Mode.NONE;
        add(r);
    }

    /** A new zone on these tiles, in the current colours. */
    public void add(Area r) {
        zones.add(new Zone(r, mv.glob.map.new Overlay(r, info(fill, edge))));
        countseq = -1;
        status = "Zone: " + r.sz().x + " x " + r.sz().y + " tiles";
    }

    /** Removes the newest zone on tile t. */
    @SuppressWarnings("deprecation")
    private void clearat(Coord t) {
        armed = Mode.NONE;
        for(int i = zones.size() - 1; i >= 0; i--) {
            if(zones.get(i).tiles.contains(t)) {
                zones.remove(i).ol.destroy();
                status = "Zone cleared. " + zones.size() + " remaining";
                return;
            }
        }
        status = "No zone at that location";
    }

    @SuppressWarnings("deprecation")
    public void clearall() {
        disarm();
        for(Zone z : zones)
            z.ol.destroy();
        zones.clear();
        status = "All zones cleared";
    }

    /** The most recent zone, whose objects the panel lists, or null. */
    public Zone latest() {
        return(zones.isEmpty() ? null : zones.get(zones.size() - 1));
    }

    public boolean key(Widget.KeyDownEvent ev) {
        if(armed != Mode.NONE && ev.code == KeyEvent.VK_ESCAPE) {
            disarm();
            return(true);
        }
        return(false);
    }

    /* ---------- drawing ---------- */

    public void tick() {
        if(zones.isEmpty() || (countseq == plan.seq && countlseq == plan.layers.seq))
            return;
        countseq = plan.seq;
        countlseq = plan.layers.seq;
        Collection<PlanObject> objs = plan.objects();
        for(Zone z : zones) {
            z.counts = count(objs, z.tiles, plan.layers::shown);
            int n = 0;
            for(Map.Entry<String, Integer> e : z.counts)
                n += e.getValue();
            z.total = n;
        }
    }

    private Tex text(String s, Color c, Text.Foundry f) {
        if(labels.size() > 300)
            labels.clear();
        return(labels.computeIfAbsent(s + "\u0001" + c.getRGB() + "\u0001" + f.hashCode(), k -> f.render(s, c).tex()));
    }

    /** "W x H" (and the objects on it) at each zone's centre, the drag's size while drawing one. */
    public void draw(GOut g) {
        for(Zone z : zones)
            label(g, z.tiles, z.total + ((z.total == 1) ? " object" : " objects"));
        if(dragging && startt != null)
            label(g, rect(startt, curt), null);
    }

    private void label(GOut g, Area tiles, String sub) {
        Coord2d mc = Coord2d.of(tiles.ul.add(tiles.br)).div(2).mul(MCache.tilesz);
        Coord3f s;
        try {
            s = mv.screenxf(Coord3f.of((float)mc.x, (float)mc.y, (float)mv.glob.map.getcz(mc) + 5));
        } catch(Loading l) {
            return;
        }
        Coord p = Coord.of(Math.round(s.x), Math.round(s.y));
        if(!p.isect(Coord.z, mv.sz))
            return;
        Tex l = text(tiles.sz().x + "x" + tiles.sz().y, Color.WHITE, labelfnd);
        Tex c = (sub == null) ? null : text(sub, new Color(220, 220, 220), countfnd);
        Coord sz = (c == null) ? l.sz() : Coord.of(Math.max(l.sz().x, c.sz().x), l.sz().y + c.sz().y);
        g.chcolor(0, 0, 0, 200);
        g.frect2(p.sub(sz.div(2)).sub(3, 2), p.add(sz.div(2)).add(3, 2));
        g.chcolor();
        int top = p.y - sz.y / 2;
        g.aimage(l, Coord.of(p.x, top), 0.5, 0);
        if(c != null)
            g.aimage(c, Coord.of(p.x, top + l.sz().y), 0.5, 0);
    }
}
