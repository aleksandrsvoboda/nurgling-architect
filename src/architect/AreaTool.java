package architect;

import architect.edit.*;
import haven.*;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;

/**
 * Area mode: drag on the ground to draw a tile-aligned area, click an area to select it (the area panel then
 * edits it), arrows move the selected area, Delete removes it. Names are drawn over their areas.
 */
public class AreaTool {
    public static final Color[] PALETTE = {
        new Color(194, 194, 65, 56), new Color(65, 160, 194, 56), new Color(194, 90, 65, 56), new Color(90, 194, 90, 56),
        new Color(160, 90, 194, 56), new Color(233, 156, 84, 56), new Color(200, 200, 200, 56), new Color(60, 90, 200, 56),
    };
    private final PlannerView mv;
    private final Plan plan;
    public boolean on = false;
    /** Selected area id, or null. */
    public String selected = null;
    private Coord startt = null, curt = null;
    private boolean dragged = false;
    private MCache.Overlay draft = null;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12).aa(true);
    private final Map<String, Tex> labels = new HashMap<>();
    /* Stamping a preset: its entries, current turn/mirror, and the ghost overlays following the cursor. */
    private org.json.JSONObject stamp = null;
    private int stampturn = 0;
    private boolean stampmirror = false;
    private Coord stampat = null;
    private final List<MCache.Overlay> stampols = new ArrayList<>();

    static final MCache.OverlayInfo draftinfo = new MCache.OverlayInfo() {
        final Material m = new Material(new haven.render.BaseColor(233, 156, 84, 90), haven.render.States.maskdepth);
        final Material e = new Material(new haven.render.BaseColor(233, 156, 84, 255), haven.render.States.maskdepth);
        public Collection<String> tags() {return(Collections.singletonList("show"));}
        public Material mat() {return(m);}
        public Material omat() {return(e);}
    };

    public AreaTool(PlannerView mv) {
        this.mv = mv;
        this.plan = mv.plan;
    }

    public Plan planof() {
        return(plan);
    }

    public PlanArea selarea() {
        return((selected == null) ? null : plan.area(selected));
    }

    private static Area rect(Coord a, Coord b) {
        return(new Area(Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y)), Coord.of(Math.max(a.x, b.x) + 1, Math.max(a.y, b.y) + 1)));
    }

    public boolean stamping() {
        return(stamp != null);
    }

    /** Starts stamping an area preset: it follows the cursor, R turns, F mirrors, a click places it. */
    public void stamp(org.json.JSONObject preset) {
        cancelstamp();
        on = true;
        stamp = preset;
        stampturn = 0;
        stampmirror = false;
    }

    @SuppressWarnings("deprecation")
    public void cancelstamp() {
        for(MCache.Overlay o : stampols)
            o.destroy();
        stampols.clear();
        stamp = null;
        stampat = null;
    }

    /** The preset's entries placed with the (turned, mirrored) bounding box centred on tile at. */
    private List<PlanArea> stamped(Coord at) {
        org.json.JSONObject sz = stamp.optJSONObject("size");
        int W = (sz != null) ? sz.getInt("w") : 1, H = (sz != null) ? sz.getInt("h") : 1;
        org.json.JSONArray es = stamp.getJSONArray("areas");
        boolean swap = (stampturn & 1) != 0;
        Coord ul = at.sub(swap ? H / 2 : W / 2, swap ? W / 2 : H / 2);
        List<PlanArea> ret = new ArrayList<>();
        for(int i = 0; i < es.length(); i++) {
            PlanArea a = PlanArea.frompreset(es.getJSONObject(i), Coord.z, null);
            int x = a.tiles.ul.x, y = a.tiles.ul.y, w = a.tiles.sz().x, h = a.tiles.sz().y, cw = W, ch = H;
            if(stampmirror)
                x = cw - (x + w);
            for(int t = 0; t < stampturn; t++) {
                /* 90 degrees clockwise inside a cw x ch box */
                int nx = ch - (y + h), ny = x;
                x = nx; y = ny;
                int tw = w; w = h; h = tw;
                int tc = cw; cw = ch; ch = tc;
            }
            ret.add(a.copy(Area.sized(ul.add(x, y), Coord.of(w, h))));
        }
        return(ret);
    }

    @SuppressWarnings("deprecation")
    public void hover(Coord c) {
        if(stamp == null)
            return;
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(stamp == null)
                    return;
                stampat = mc.floor(MCache.tilesz);
                List<PlanArea> as = stamped(stampat);
                while(stampols.size() > as.size())
                    stampols.remove(stampols.size() - 1).destroy();
                for(int i = 0; i < as.size(); i++) {
                    if(i < stampols.size())
                        stampols.get(i).update(as.get(i).tiles);
                    else
                        stampols.add(mv.glob.map.new Overlay(as.get(i).tiles, draftinfo));
                }
            }
        }.run();
    }

    private void placestamp() {
        if(stampat == null)
            return;
        List<PlanArea> as = stamped(stampat);
        plan.begin("Stamp " + stamp.optString("name", "preset"));
        try {
            for(PlanArea a : as)
                plan.putarea(a);
        } finally {
            plan.commit();
        }
        cancelstamp();
    }

    public void press(Coord c) {
        if(stamp != null) {
            placestamp();
            return;
        }
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                startt = curt = mc.floor(MCache.tilesz);
                dragged = false;
            }
        }.run();
    }

    @SuppressWarnings("deprecation")
    public void drag(Coord c) {
        if(startt == null)
            return;
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(startt == null)
                    return;
                curt = mc.floor(MCache.tilesz);
                if(!curt.equals(startt))
                    dragged = true;
                if(dragged) {
                    if(draft == null)
                        draft = mv.glob.map.new Overlay(rect(startt, curt), draftinfo);
                    else
                        draft.update(rect(startt, curt));
                }
            }
        }.run();
    }

    @SuppressWarnings("deprecation")
    public void release() {
        if(startt == null)
            return;
        if(draft != null) {
            draft.destroy();
            draft = null;
        }
        if(dragged) {
            int n = plan.areas().size() + 1;
            PlanArea a = PlanArea.simple("Area " + n, PALETTE[(n - 1) % PALETTE.length], rect(startt, curt), null);
            plan.putarea(a);
            selected = a.id;
        } else {
            PlanArea a = plan.areaat(startt);
            selected = (a != null && !plan.layers.locked(a.layer)) ? a.id : null;
        }
        startt = curt = null;
    }

    public boolean dragging() {
        return(startt != null);
    }

    public boolean key(Widget.KeyDownEvent ev) {
        if(stamp != null) {
            switch(ev.code) {
            case KeyEvent.VK_R:
                stampturn = (stampturn + (((ev.mods & KeyMatch.S) != 0) ? 3 : 1)) % 4;
                hover(mv.ui.mc.sub(mv.rootpos()));
                return(true);
            case KeyEvent.VK_F:
                stampmirror = !stampmirror;
                hover(mv.ui.mc.sub(mv.rootpos()));
                return(true);
            case KeyEvent.VK_ESCAPE:
                cancelstamp();
                return(true);
            }
        }
        PlanArea a = selarea();
        if(a == null)
            return(false);
        switch(ev.code) {
        case KeyEvent.VK_DELETE: case KeyEvent.VK_BACK_SPACE:
            plan.removearea(a.id);
            selected = null;
            return(true);
        case KeyEvent.VK_ESCAPE:
            selected = null;
            return(true);
        case KeyEvent.VK_LEFT:  move(a, -1, 0); return(true);
        case KeyEvent.VK_RIGHT: move(a, 1, 0); return(true);
        case KeyEvent.VK_UP:    move(a, 0, -1); return(true);
        case KeyEvent.VK_DOWN:  move(a, 0, 1); return(true);
        }
        return(false);
    }

    private void move(PlanArea a, int dx, int dy) {
        plan.putarea(a.with(a.name, a.color, new Area(a.tiles.ul.add(dx, dy), a.tiles.br.add(dx, dy)), a.layer));
    }

    /** Area names at their centres, and a frame around the selected one. */
    public void draw(GOut g) {
        if(labels.size() > 300)
            labels.clear();
        for(PlanArea a : plan.areas()) {
            if(!plan.layers.shown(a.layer))
                continue;
            Coord2d mc = Coord2d.of(a.tiles.ul.add(a.tiles.br)).div(2).mul(MCache.tilesz);
            Coord3f s;
            try {
                s = mv.screenxf(Coord3f.of((float)mc.x, (float)mc.y, (float)mv.glob.map.getcz(mc)));
            } catch(Loading l) {
                continue;
            }
            boolean sel = a.id.equals(selected);
            Tex t = labels.computeIfAbsent(a.name + "\u0001" + sel, k -> Text.renderstroked(a.name, sel ? nurgling.NStyle.border : Color.WHITE, Color.BLACK, fnd).tex());
            Coord p = Coord.of(Math.round(s.x), Math.round(s.y));
            if(p.isect(Coord.z, mv.sz))
                g.image(t, p.sub(t.sz().div(2)));
        }
    }
}
