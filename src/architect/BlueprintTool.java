package architect;

import architect.edit.*;
import architect.io.Blueprints;
import haven.*;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Blueprints in the view. Stamping: the preset's objects follow the cursor a whole tile at a time (the cursor
 * holds the middle of the box), the wheel or R turns, F mirrors, a click places, Esc or a right click cancels;
 * the same controls as the game. Region: drag a rectangle and its objects are saved.
 */
public class BlueprintTool {
    private final PlannerView mv;
    private final Plan plan;
    private Blueprints.Preset base = null, cur = null;
    private int turns = 0;
    private boolean mirror = false;
    private Coord centre = null;
    private MCache.Overlay box = null;
    private boolean boxbad = false;
    /* Region: armed until the drag, then the tiles dragged over. */
    private boolean region = false;
    private Coord rstart = null, rcur = null;
    private MCache.Overlay rbox = null;
    private BiConsumer<Coord2d, Coord2d> regiondone = null;
    /** Told about a finished stamp: the preset, how many objects went down. */
    public BiConsumer<Blueprints.Preset, Integer> placed = (p, n) -> {};

    static final MCache.OverlayInfo badinfo = info(new Color(230, 50, 40, 110));

    static MCache.OverlayInfo info(Color c) {
        return(new MCache.OverlayInfo() {
            final Material m = new Material(new haven.render.BaseColor(c), haven.render.States.maskdepth);
            final Material e = new Material(new haven.render.BaseColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 255)), haven.render.States.maskdepth);
            public Collection<String> tags() {return(Collections.singletonList("show"));}
            public Material mat() {return(m);}
            public Material omat() {return(e);}
        });
    }

    public BlueprintTool(PlannerView mv) {
        this.mv = mv;
        this.plan = mv.plan;
    }

    /* ---------- stamping ---------- */

    public boolean stamping() {
        return(base != null);
    }

    public String stampname() {
        return((base == null) ? null : base.name);
    }

    public void stamp(Blueprints.Preset p) {
        cancel();
        cancelregion();
        base = p;
        cur = p;
        turns = 0;
        mirror = false;
    }

    @SuppressWarnings("deprecation")
    public void cancel() {
        if(box != null)
            box.destroy();
        box = null;
        base = cur = null;
        centre = null;
        mv.blueprintpreview(Collections.emptyList());
    }

    private Coord origin() {
        return(centre.sub(cur.w / 2, cur.h / 2));
    }

    @SuppressWarnings("deprecation")
    private void refresh() {
        if(cur == null || centre == null)
            return;
        List<PlanObject> all = new ArrayList<>();
        for(List<PlanObject> l : BlueprintPlan.objects(cur, origin()))
            all.addAll(l);
        boolean bad = false;
        for(PlanObject o : all)
            if(!mv.inbounds(o.rc))
                bad = true;
        mv.blueprintpreview(all);
        Area a = Area.sized(origin(), Coord.of(cur.w, cur.h));
        if(box != null && boxbad == bad) {
            box.update(a);
        } else {
            if(box != null)
                box.destroy();
            box = mv.glob.map.new Overlay(a, bad ? badinfo : AreaTool.draftinfo);
            boxbad = bad;
        }
    }

    private double nextretry = 0;

    /** Ghosts whose models were still loading are filled in as they arrive. */
    public void tick() {
        if(cur != null && centre != null && mv.previewcount() < cur.count() && Utils.rtime() > nextretry) {
            nextretry = Utils.rtime() + 0.5;
            refresh();
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
                if(!t.equals(centre)) {
                    centre = t;
                    refresh();
                }
            }
        }.run();
    }

    /** Dev: holds the stamp centred on a tile, as if the cursor were there. */
    public void at(Coord tile) {
        centre = tile;
        refresh();
    }

    public void turn(int d) {
        turns += d;
        cur = base.transformed(turns, mirror);
        refresh();
    }

    public void flip() {
        mirror = !mirror;
        cur = base.transformed(turns, mirror);
        refresh();
    }

    /** Places the stamp where it is. A layer preset goes into the active layer's folder. */
    public void place() {
        if(cur == null || centre == null)
            return;
        if(boxbad) {
            mv.toast("Part of the blueprint is outside the world");
            return;
        }
        Layers.Node act = plan.layers.get(plan.layers.active());
        String folder = (act == null) ? null : (act.folder ? act.id : act.parent);
        Blueprints.Preset p = cur;
        BlueprintPlan.place(plan, p, origin(), folder);
        cancel();
        placed.accept(p, p.count());
    }

    /* ---------- region ---------- */

    public boolean regionarmed() {
        return(region);
    }

    public boolean regiondragging() {
        return(rstart != null);
    }

    /** Arms "save region": the next drag on the ground picks the rectangle, then done gets its world corners. */
    public void armregion(BiConsumer<Coord2d, Coord2d> done) {
        cancel();
        region = true;
        regiondone = done;
    }

    @SuppressWarnings("deprecation")
    public void cancelregion() {
        if(rbox != null)
            rbox.destroy();
        rbox = null;
        region = false;
        rstart = rcur = null;
        regiondone = null;
    }

    private static Area rect(Coord a, Coord b) {
        return(new Area(Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y)), Coord.of(Math.max(a.x, b.x) + 1, Math.max(a.y, b.y) + 1)));
    }

    public void regionpress(Coord c) {
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                rstart = rcur = mc.floor(MCache.tilesz);
            }
        }.run();
    }

    @SuppressWarnings("deprecation")
    public void regiondrag(Coord c) {
        if(rstart == null)
            return;
        mv.new Maptest(c) {
            protected void hit(Coord pc, Coord2d mc) {
                if(rstart == null)
                    return;
                rcur = mc.floor(MCache.tilesz);
                if(rbox == null)
                    rbox = mv.glob.map.new Overlay(rect(rstart, rcur), AreaTool.draftinfo);
                else
                    rbox.update(rect(rstart, rcur));
            }
        }.run();
    }

    public void regionrelease() {
        if(rstart == null)
            return;
        Area a = rect(rstart, rcur);
        BiConsumer<Coord2d, Coord2d> done = regiondone;
        cancelregion();
        if(done != null)
            done.accept(Coord2d.of(a.ul.x * Blueprints.TILE, a.ul.y * Blueprints.TILE),
                        Coord2d.of(a.br.x * Blueprints.TILE, a.br.y * Blueprints.TILE));
    }

    /* ---------- keys ---------- */

    public boolean key(Widget.KeyDownEvent ev) {
        if(base != null) {
            switch(ev.code) {
            case KeyEvent.VK_R:
                turn(((ev.mods & KeyMatch.S) != 0) ? -1 : 1);
                return(true);
            case KeyEvent.VK_F:
                flip();
                return(true);
            case KeyEvent.VK_ESCAPE:
                cancel();
                return(true);
            }
        }
        if(region && ev.code == KeyEvent.VK_ESCAPE) {
            cancelregion();
            return(true);
        }
        return(false);
    }
}
