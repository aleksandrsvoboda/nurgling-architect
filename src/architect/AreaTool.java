package architect;

import architect.edit.*;
import architect.io.AreaPresets;
import haven.*;
import nurgling.areas.AreaPaths;
import org.json.JSONArray;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;

/**
 * Area mode on the map. Drag on empty ground draws a new tile-aligned area; click an area to select it
 * (Shift adds or removes); drag a selected area to move the selection; drag an area's edge or corner (its
 * outer quarter tile) to resize it. Delete removes, arrows nudge, Ctrl+C/V/D copy, paste at the cursor and
 * duplicate, Ctrl+Shift+C/V copy and paste the settings (specialisations, Take/Put, fill). Areas can't pass
 * {@link AreaPresets#MAXSIDE} tiles a side, as in the game. Stamping a preset: it follows the cursor, the wheel
 * or R turns, F mirrors, a click places it in a folder named after it.
 */
public class AreaTool {
    public static final Color[] PALETTE = {
        new Color(194, 194, 65, 56), new Color(65, 160, 194, 56), new Color(194, 90, 65, 56), new Color(90, 194, 90, 56),
        new Color(160, 90, 194, 56), new Color(233, 156, 84, 56), new Color(200, 200, 200, 56), new Color(60, 90, 200, 56),
    };
    /** How close (world units) to an area's border a press resizes instead of moving. */
    static final double EDGE = MCache.tilesz.x / 4;
    private final PlannerView mv;
    private final Plan plan;
    public boolean on = false;
    /** Selected area ids, in selection order. */
    private final LinkedHashSet<String> sel = new LinkedHashSet<>();
    /** The folder new areas and stamps go in (the Areas window's open folder). */
    public String folder = AreaPaths.ROOT;
    /** Told whenever the selection changes. */
    public Runnable selchanged = () -> {};

    private enum Mode {NONE, PENDING, DRAW, MOVE, RESIZE}
    private Mode mode = Mode.NONE;
    private Coord startt, curt;
    private Coord2d startmc;
    private int pressmods;
    private PlanArea pressed;
    private int edges;
    private final Map<String, Area> starts = new HashMap<>();
    private MCache.Overlay draft = null;
    private final Map<String, MCache.Overlay> selols = new HashMap<>();
    private final Map<String, Area> selshown = new HashMap<>();

    /* Clipboards: whole areas (relative to the first), and one area's settings. */
    private final List<PlanArea> clip = new ArrayList<>();
    private PlanArea.Edit setclip = null;

    /* Stamping a preset. */
    private AreaPresets.Preset base = null, cur = null;
    private int turns = 0;
    private boolean mirror = false;
    private Coord stampat = null;
    private final List<MCache.Overlay> stampols = new ArrayList<>();

    private final Text.Foundry fnd = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12).aa(true);
    private final Map<String, Tex> labels = new HashMap<>();

    static final MCache.OverlayInfo draftinfo = BlueprintTool.info(new Color(233, 156, 84, 90));
    static final MCache.OverlayInfo selinfo = BlueprintTool.info(new Color(255, 255, 255, 40));

    public AreaTool(PlannerView mv) {
        this.mv = mv;
        this.plan = mv.plan;
    }

    public Plan planof() {
        return(plan);
    }

    /* ---------- selection ---------- */

    /** The selected areas that still exist, in selection order. */
    public List<PlanArea> selected() {
        List<PlanArea> ret = new ArrayList<>();
        for(String id : new ArrayList<>(sel)) {
            PlanArea a = plan.area(id);
            if(a != null)
                ret.add(a);
        }
        return(ret);
    }

    /** The first selected area, or null. */
    public PlanArea selarea() {
        List<PlanArea> s = selected();
        return(s.isEmpty() ? null : s.get(0));
    }

    public boolean isselected(String id) {
        return(sel.contains(id));
    }

    public void select(Collection<String> ids, boolean add) {
        if(!add)
            sel.clear();
        sel.addAll(ids);
        selchanged.run();
    }

    public void toggle(String id) {
        if(!sel.remove(id))
            sel.add(id);
        selchanged.run();
    }

    public void clearsel() {
        if(!sel.isEmpty()) {
            sel.clear();
            selchanged.run();
        }
    }

    /** The visible, unlocked area on a tile, newest on top. */
    private PlanArea hit(Coord tc) {
        PlanArea ret = null;
        for(PlanArea a : plan.areas())
            if(plan.layers.shown(a.layer) && !plan.layers.locked(a.layer) && a.tiles.contains(tc))
                ret = a;
        return(ret);
    }

    /** Which edges of a a press at world point mc grabs: 1 left, 2 right, 4 top, 8 bottom. */
    private static int edgesat(PlanArea a, Coord2d mc) {
        double t = MCache.tilesz.x;
        double x0 = a.tiles.ul.x * t, y0 = a.tiles.ul.y * t, x1 = a.tiles.br.x * t, y1 = a.tiles.br.y * t;
        int e = 0;
        if(mc.x - x0 < EDGE) e |= 1;
        else if(x1 - mc.x < EDGE) e |= 2;
        if(mc.y - y0 < EDGE) e |= 4;
        else if(y1 - mc.y < EDGE) e |= 8;
        return(e);
    }

    private static Area rect(Coord a, Coord b) {
        return(new Area(Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y)), Coord.of(Math.max(a.x, b.x) + 1, Math.max(a.y, b.y) + 1)));
    }

    /** a limited to MAXSIDE tiles a side, keeping the corner at fixed. */
    private static Area capped(Area a, Coord fixed) {
        int w = Math.min(a.sz().x, AreaPresets.MAXSIDE), h = Math.min(a.sz().y, AreaPresets.MAXSIDE);
        int x = (a.ul.x == fixed.x) ? a.ul.x : a.br.x - w, y = (a.ul.y == fixed.y) ? a.ul.y : a.br.y - h;
        return(Area.sized(Coord.of(x, y), Coord.of(w, h)));
    }

    /* ---------- mouse ---------- */

    public void press(Coord c, int mods) {
        if(base != null) {
            placestamp();
            return;
        }
        mode = Mode.PENDING;
        pressmods = mods;
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(mode != Mode.PENDING)
                    return;
                startmc = mc;
                startt = curt = mc.floor(MCache.tilesz);
                pressed = AreaTool.this.hit(startt);
                edges = 0;
                if(pressed != null && (pressmods & UI.MOD_SHIFT) == 0 && sel.size() <= 1)
                    edges = edgesat(pressed, mc);
            }

            protected void nohit(Coord pc) {
                mode = Mode.NONE;
            }
        }.run();
    }

    public void drag(Coord c) {
        if(mode == Mode.NONE || startt == null)
            return;
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(mode == Mode.NONE || startt == null)
                    return;
                curt = mc.floor(MCache.tilesz);
                if(mode == Mode.PENDING) {
                    if(curt.equals(startt) && mc.dist(startmc) < MCache.tilesz.x / 3)
                        return;
                    if(pressed == null) {
                        mode = Mode.DRAW;
                    } else {
                        if(!sel.contains(pressed.id))
                            select(Collections.singleton(pressed.id), (pressmods & UI.MOD_SHIFT) != 0);
                        starts.clear();
                        for(PlanArea a : selected())
                            starts.put(a.id, a.tiles);
                        mode = (edges != 0) ? Mode.RESIZE : Mode.MOVE;
                        plan.begin((mode == Mode.RESIZE) ? "Resize area" : "Move areas");
                    }
                }
                switch(mode) {
                case DRAW:
                    Area r = capped(rect(startt, curt), startt);
                    if(draft == null)
                        draft = mv.glob.map.new Overlay(r, draftinfo);
                    else
                        draft.update(r);
                    break;
                case MOVE: {
                    Coord d = curt.sub(startt);
                    for(Map.Entry<String, Area> e : starts.entrySet()) {
                        PlanArea a = plan.area(e.getKey());
                        if(a != null)
                            plan.putarea(a.with(a.name, a.color, new Area(e.getValue().ul.add(d), e.getValue().br.add(d)), a.layer));
                    }
                    break;
                }
                case RESIZE: {
                    PlanArea a = plan.area(pressed.id);
                    Area o = starts.get(pressed.id);
                    if(a == null || o == null)
                        break;
                    int x0 = o.ul.x, y0 = o.ul.y, x1 = o.br.x, y1 = o.br.y;
                    if((edges & 1) != 0) x0 = Math.min(curt.x, x1 - 1);
                    if((edges & 2) != 0) x1 = Math.max(curt.x + 1, x0 + 1);
                    if((edges & 4) != 0) y0 = Math.min(curt.y, y1 - 1);
                    if((edges & 8) != 0) y1 = Math.max(curt.y + 1, y0 + 1);
                    if(x1 - x0 > AreaPresets.MAXSIDE) { if((edges & 1) != 0) x0 = x1 - AreaPresets.MAXSIDE; else x1 = x0 + AreaPresets.MAXSIDE; }
                    if(y1 - y0 > AreaPresets.MAXSIDE) { if((edges & 4) != 0) y0 = y1 - AreaPresets.MAXSIDE; else y1 = y0 + AreaPresets.MAXSIDE; }
                    plan.putarea(a.with(a.name, a.color, new Area(Coord.of(x0, y0), Coord.of(x1, y1)), a.layer));
                    break;
                }
                default:
                }
            }
        }.run();
    }

    @SuppressWarnings("deprecation")
    public void release() {
        Mode m = mode;
        mode = Mode.NONE;
        if(draft != null) {
            draft.destroy();
            draft = null;
        }
        switch(m) {
        case DRAW: {
            Area r = capped(rect(startt, curt), startt);
            int n = plan.areas().size() + 1;
            PlanArea a = PlanArea.simple("Area " + n, PALETTE[(n - 1) % PALETTE.length], r, null);
            PlanArea.Edit e = a.edit();
            e.path = folder;
            a = a.with(e);
            plan.putarea(a);
            select(Collections.singleton(a.id), false);
            if(rect(startt, curt).sz().x > AreaPresets.MAXSIDE || rect(startt, curt).sz().y > AreaPresets.MAXSIDE)
                mv.toast("Areas are at most " + AreaPresets.MAXSIDE + " tiles a side, as in the game");
            break;
        }
        case MOVE: case RESIZE:
            plan.commit();
            break;
        case PENDING:
            /* a click */
            if(pressed == null) {
                if((pressmods & UI.MOD_SHIFT) == 0)
                    clearsel();
            } else if((pressmods & UI.MOD_SHIFT) != 0) {
                toggle(pressed.id);
            } else {
                select(Collections.singleton(pressed.id), false);
            }
            break;
        default:
        }
        startt = curt = null;
        pressed = null;
    }

    public boolean dragging() {
        return(mode != Mode.NONE);
    }

    /* ---------- editing ---------- */

    public void delete() {
        List<PlanArea> s = selected();
        if(s.isEmpty())
            return;
        plan.begin("Delete areas");
        try {
            for(PlanArea a : s)
                plan.removearea(a.id);
        } finally {
            plan.commit();
        }
        clearsel();
    }

    private void nudge(int dx, int dy) {
        List<PlanArea> s = selected();
        if(s.isEmpty())
            return;
        plan.begin("Nudge areas");
        try {
            for(PlanArea a : s)
                plan.putarea(a.with(a.name, a.color, new Area(a.tiles.ul.add(dx, dy), a.tiles.br.add(dx, dy)), a.layer));
        } finally {
            plan.commit();
        }
    }

    public boolean copy() {
        List<PlanArea> s = selected();
        if(s.isEmpty())
            return(false);
        clip.clear();
        clip.addAll(s);
        return(true);
    }

    /** Pastes the copied areas with the first one's corner on tile at; the copies get selected. */
    public void paste(Coord at) {
        if(clip.isEmpty())
            return;
        Coord base = clip.get(0).tiles.ul;
        List<String> made = new ArrayList<>();
        plan.begin("Paste areas");
        try {
            for(PlanArea a : clip) {
                Coord d = a.tiles.ul.sub(base);
                PlanArea c = a.copy(Area.sized(at.add(d), a.tiles.sz()));
                plan.putarea(c);
                made.add(c.id);
            }
        } finally {
            plan.commit();
        }
        select(made, false);
    }

    private void pasteatcursor() {
        if(clip.isEmpty())
            return;
        mv.new Maptest(mv.ui.mc.sub(mv.rootpos())) {
            protected void hit(Coord pc, Coord2d mc) {
                paste(mc.floor(MCache.tilesz));
            }
        }.run();
    }

    /** Copies the first selected area's settings (specialisations, Take/Put, fill). */
    public boolean copysettings() {
        PlanArea a = selarea();
        if(a == null)
            return(false);
        setclip = a.edit();
        mv.toast("Copied the settings of " + a.name);
        return(true);
    }

    public boolean hassettings() {
        return(setclip != null);
    }

    /** Gives every selected area the copied settings, as one undo step. */
    public void pastesettings() {
        List<PlanArea> s = selected();
        if(setclip == null || s.isEmpty())
            return;
        plan.begin("Paste area settings");
        try {
            for(PlanArea a : s) {
                PlanArea.Edit e = a.edit();
                e.spec = new JSONArray(setclip.spec.toString());
                e.in = new JSONArray(setclip.in.toString());
                e.out = new JSONArray(setclip.out.toString());
                e.fill = setclip.fill;
                plan.putarea(a.with(e));
            }
        } finally {
            plan.commit();
        }
        mv.toast("Settings pasted onto " + s.size() + " area" + ((s.size() == 1) ? "" : "s"));
    }

    /* ---------- stamping ---------- */

    public boolean stamping() {
        return(base != null);
    }

    public String stampname() {
        return((base == null) ? null : base.name);
    }

    public void stamp(AreaPresets.Preset p) {
        cancelstamp();
        on = true;
        base = cur = p;
        turns = 0;
        mirror = false;
        hover(mv.ui.mc.sub(mv.rootpos()));
    }

    @SuppressWarnings("deprecation")
    public void cancelstamp() {
        for(MCache.Overlay o : stampols)
            o.destroy();
        stampols.clear();
        base = cur = null;
        stampat = null;
    }

    private Coord stampul() {
        return(stampat.sub(cur.w / 2, cur.h / 2));
    }

    @SuppressWarnings("deprecation")
    private void refreshstamp() {
        if(cur == null || stampat == null)
            return;
        List<PlanArea> as = AreaPresets.areas(cur, stampul(), "", null);
        while(stampols.size() > as.size())
            stampols.remove(stampols.size() - 1).destroy();
        for(int i = 0; i < as.size(); i++) {
            if(i < stampols.size())
                stampols.get(i).update(as.get(i).tiles);
            else
                stampols.add(mv.glob.map.new Overlay(as.get(i).tiles, draftinfo));
        }
    }

    public void hover(Coord c) {
        if(base == null)
            return;
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(base == null)
                    return;
                Coord t = mc.floor(MCache.tilesz);
                if(!t.equals(stampat)) {
                    stampat = t;
                    refreshstamp();
                }
            }
        }.run();
    }

    /** Dev: holds the stamp centred on a tile and places it. */
    public void placeat(Coord tile) {
        stampat = tile;
        refreshstamp();
        placestamp();
    }

    public void turnstamp(int d) {
        if(base == null)
            return;
        turns += d;
        cur = base.transformed(turns, mirror);
        refreshstamp();
    }

    private void placestamp() {
        if(cur == null || stampat == null)
            return;
        String f = AreaPresets.place(plan, cur, stampul(), folder, null);
        mv.toast("Placed " + cur.name + " in folder " + f);
        cancelstamp();
    }

    /* ---------- keys ---------- */

    public boolean key(Widget.KeyDownEvent ev) {
        boolean ctrl = (ev.mods & KeyMatch.C) != 0, shift = (ev.mods & KeyMatch.S) != 0;
        if(base != null) {
            switch(ev.code) {
            case KeyEvent.VK_R:
                turnstamp(shift ? -1 : 1);
                return(true);
            case KeyEvent.VK_F:
                mirror = !mirror;
                cur = base.transformed(turns, mirror);
                refreshstamp();
                return(true);
            case KeyEvent.VK_ESCAPE:
                cancelstamp();
                return(true);
            }
        }
        if(ctrl) {
            switch(ev.code) {
            case KeyEvent.VK_C:
                return(shift ? copysettings() : copy());
            case KeyEvent.VK_V:
                if(shift)
                    pastesettings();
                else
                    pasteatcursor();
                return(true);
            case KeyEvent.VK_D: {
                PlanArea a = selarea();
                if(a != null && copy())
                    paste(a.tiles.ul.add(1, 1));
                return(true);
            }
            case KeyEvent.VK_A: {
                List<String> all = new ArrayList<>();
                for(PlanArea a : plan.areas())
                    if(plan.layers.shown(a.layer) && !plan.layers.locked(a.layer))
                        all.add(a.id);
                select(all, false);
                return(true);
            }
            }
            return(false);
        }
        if(sel.isEmpty())
            return(false);
        switch(ev.code) {
        case KeyEvent.VK_DELETE: case KeyEvent.VK_BACK_SPACE:
            delete();
            return(true);
        case KeyEvent.VK_ESCAPE:
            clearsel();
            return(true);
        case KeyEvent.VK_LEFT:  nudge(-1, 0); return(true);
        case KeyEvent.VK_RIGHT: nudge(1, 0); return(true);
        case KeyEvent.VK_UP:    nudge(0, -1); return(true);
        case KeyEvent.VK_DOWN:  nudge(0, 1); return(true);
        }
        return(false);
    }

    /* ---------- drawing ---------- */

    /** Outlines on the selected areas, following their changes. */
    @SuppressWarnings("deprecation")
    public void tick() {
        Set<String> want = new HashSet<>();
        if(on) {
            for(PlanArea a : selected())
                if(plan.layers.shown(a.layer))
                    want.add(a.id);
        }
        for(Iterator<Map.Entry<String, MCache.Overlay>> i = selols.entrySet().iterator(); i.hasNext();) {
            Map.Entry<String, MCache.Overlay> e = i.next();
            if(!want.contains(e.getKey())) {
                e.getValue().destroy();
                selshown.remove(e.getKey());
                i.remove();
            }
        }
        for(String id : want) {
            PlanArea a = plan.area(id);
            MCache.Overlay o = selols.get(id);
            if(o == null) {
                selols.put(id, mv.glob.map.new Overlay(a.tiles, selinfo));
                selshown.put(id, a.tiles);
            } else if(!a.tiles.equals(selshown.get(id))) {
                o.update(a.tiles);
                selshown.put(id, a.tiles);
            }
        }
    }

    private final Map<String, Tex> specicons = new HashMap<>();

    /** The icon of an area's first specialisation, or null. */
    private Tex specicon(PlanArea a) {
        JSONArray s = a.specs();
        if(s.length() == 0)
            return(null);
        String n = s.getJSONObject(0).optString("name", "");
        if(specicons.containsKey(n))
            return(specicons.get(n));
        nurgling.widgets.Specialisation.SpecialisationItem it = nurgling.widgets.Specialisation.findSpecialisation(n);
        Tex t = (it == null || it.image == null) ? null : new TexI(it.image);
        specicons.put(n, t);
        return(t);
    }

    /** Area names (with their main specialisation's icon) at their centres; selected ones in orange. */
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
            boolean on = sel.contains(a.id);
            Tex t = labels.computeIfAbsent(a.name + "\u0001" + on, k -> Text.renderstroked(a.name, on ? nurgling.NStyle.border : Color.WHITE, Color.BLACK, fnd).tex());
            Coord p = Coord.of(Math.round(s.x), Math.round(s.y));
            if(!p.isect(Coord.z, mv.sz))
                continue;
            Tex ic = specicon(a);
            int isz = UI.scale(18);
            if(ic != null) {
                int w = isz + UI.scale(3) + t.sz().x;
                Coord ul = p.sub(w / 2, isz / 2);
                g.image(ic, ul, Coord.of(isz, isz));
                g.image(t, ul.add(isz + UI.scale(3), (isz - t.sz().y) / 2));
            } else {
                g.image(t, p.sub(t.sz().div(2)));
            }
        }
    }
}
