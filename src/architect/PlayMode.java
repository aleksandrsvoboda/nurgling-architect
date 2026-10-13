package architect;

import architect.edit.*;
import haven.*;

import java.awt.event.KeyEvent;
import java.util.*;

/**
 * Play mode: a character you walk around your plan with, seen through the game's own camera.
 *
 * The character is a real engine Composite assembled from the bundled character resources (body, hair,
 * clothes) and animated with the game's own poses: idle, walking, running, wading in shallow water and
 * swimming in deep water. Click to walk to a spot (as in the game), or steer with WASD; Shift walks instead
 * of running. Planned objects block the way by their real footprints. Esc leaves play mode.
 */
public class PlayMode {
    /** World units per second. One tile is 11 units. */
    public static final double RUN = 36, WALK = 18, SWIM = 12;
    /** Half-size of the character's square footprint, in world units. */
    static final double BODY = 2.5;
    private final PlannerView mv;
    private final Plan plan;
    public boolean on = false;
    private Gob gob = null;
    private Composite comp = null;
    private Coord2d pos, target = null;
    private double angle = 0;
    private String pose = null;
    private MapView.Camera prevcam = null;
    private Coord2d prevcc = null;
    private final Set<Integer> held = new HashSet<>();
    private boolean walk = false;
    public boolean female = false;

    public PlayMode(PlannerView mv) {
        this.mv = mv;
        this.plan = mv.plan;
    }

    /* ---------- the character ---------- */

    private static Indir<Resource> r(String name) {
        return(Resource.remote().load(name));
    }

    /**
     * The character's look, as the game describes it to the client: mesh resources, each with the textures laid
     * on it. Dev override for trying outfits: -Darchitect.outfit=mesh:tex+tex,mesh:tex (gfx/borka/ is implied).
     */
    private List<Composited.MD> outfit() {
        /* Hair colours (e.g. ravenblackhair) need the game's own recolouring and break the shader on their own,
         * so hair keeps its default colour. */
        String spec = System.getProperty("architect.outfit", female ? "female:female+linenpants+hempshirt,hair:hair"
                                                                    : "male:male+linenpants+hempshirt,hair:hair,beard:beard");
        List<Composited.MD> mods = new ArrayList<>();
        for(String part : spec.split(",")) {
            String[] mt = part.split(":");
            List<Indir<Resource>> tex = new ArrayList<>();
            if(mt.length > 1)
                for(String t : mt[1].split("\\+"))
                    tex.add(r("gfx/borka/" + t));
            mods.add(new Composited.MD(r("gfx/borka/" + mt[0]), ResData.wrap(tex)));
        }
        return(mods);
    }

    private void setpose(String name) {
        if(name.equals(pose) || comp == null)
            return;
        pose = name;
        comp.chposes(Collections.singletonList(new ResData(r(name), Message.nil)), true);
    }

    /* ---------- entering and leaving ---------- */

    /** Starts play mode with the character standing at at. */
    public void start(Coord2d at) {
        if(on)
            return;
        on = true;
        pos = at;
        target = null;
        pose = null;
        prevcam = mv.camera;
        prevcc = mv.cc;
        mv.camera = mv.new SOrthoCam();
        mv.cc = at;
        Glob glob = mv.glob;
        glob.loader.defer(() -> {
            Gob g = new Gob(glob, pos);
            Composite c = new Composite(g, r("gfx/borka/body"));
            c.chmod(outfit());
            g.setattr(c);
            g.move(pos, angle);
            synchronized(PlayMode.this) {
                if(!on)
                    return;
                gob = g;
                comp = c;
            }
            glob.oc.add(g);
            setpose("gfx/borka/idle");
        }, null);
    }

    public void stop() {
        if(!on)
            return;
        on = false;
        Gob g;
        synchronized(this) {
            g = gob;
            gob = null;
            comp = null;
        }
        if(g != null)
            mv.glob.oc.remove(g);
        if(prevcam != null)
            mv.camera = prevcam;
        if(prevcc != null)
            mv.cc = pos;
        held.clear();
    }

    /* ---------- moving ---------- */

    private String tileat(Coord2d p) {
        try {
            MCache map = mv.glob.map;
            return(map.tilesetname(map.gettile(p.floor(MCache.tilesz))));
        } catch(Loading l) {
            return(null);
        }
    }

    /** Water under the character: 0 dry, 1 shallow (wading), 2 deep (swimming). */
    private int water(Coord2d p) {
        String t = tileat(p);
        if(t == null)
            return(0);
        if(t.endsWith("/deep") || t.contains("/odeep") || t.endsWith("/seabed"))
            return(2);
        if(t.endsWith("/water") || t.contains("swampwater") || t.contains("owater"))
            return(1);
        return(0);
    }

    /** Whether the character could stand at p: inside the world and clear of every planned footprint. */
    private boolean free(Coord2d p) {
        if(!mv.inbounds(p))
            return(false);
        Coord2d[][] me = {{p.add(-BODY, -BODY), p.add(BODY, -BODY), p.add(BODY, BODY), p.add(-BODY, BODY)}};
        for(PlanObject o : plan.objects()) {
            if(o.rc.dist(p) > 80 || !plan.layers.shown(o.layer))
                continue;
            Footprint fp = Footprint.of(o.res);
            if(fp != null && fp.exact && Collisions.overlapTest(me, fp.world(o.rc, o.a)))
                return(false);
        }
        return(true);
    }

    public void walkto(Coord2d p) {
        target = p;
    }

    public void tick(double dt) {
        if(!on)
            return;
        dt = Math.min(dt, 0.1);
        Coord2d dir = keydir();
        if(dir != null)
            target = null;
        else if(target != null) {
            Coord2d d = target.sub(pos);
            double len = Math.hypot(d.x, d.y);
            if(len < 1)
                target = null;
            else
                dir = d.div(len);
        }
        int wet = water(pos);
        if(dir != null) {
            double speed = (wet == 2) ? SWIM : (walk ? WALK : RUN);
            double step = speed * dt;
            if(target != null)
                step = Math.min(step, target.dist(pos));
            Coord2d np = pos.add(dir.mul(step));
            /* Blocked: slide along whichever axis is still free, like brushing past a wall. */
            if(!free(np)) {
                Coord2d nx = pos.add(dir.x * step, 0), ny = pos.add(0, dir.y * step);
                np = free(nx) ? nx : (free(ny) ? ny : null);
            }
            if(np == null) {
                target = null;
                setpose(wet == 2 ? "gfx/borka/swimman" : "gfx/borka/idle");
            } else {
                angle = Math.atan2(dir.y, dir.x);
                pos = np;
                setpose((wet == 2) ? "gfx/borka/swimman" : (wet == 1) ? "gfx/borka/wading" : walk ? "gfx/borka/walking" : "gfx/borka/running");
            }
        } else {
            setpose((wet == 2) ? "gfx/borka/swimman" : "gfx/borka/idle");
        }
        Gob g = gob;
        if(g != null)
            g.move(pos, angle);
        mv.cc = pos;
    }

    /* ---------- keys ---------- */

    private Coord2d keydir() {
        double f = 0, r = 0;
        if(held.contains(KeyEvent.VK_W)) f += 1;
        if(held.contains(KeyEvent.VK_S)) f -= 1;
        if(held.contains(KeyEvent.VK_D)) r += 1;
        if(held.contains(KeyEvent.VK_A)) r -= 1;
        if(f == 0 && r == 0)
            return(null);
        /* Both cameras look along (-cos a, sin a) on the ground (Camera.makepointed, y inverted). */
        double a = mv.camera.angle();
        Coord2d fw = Coord2d.of(-Math.cos(a), Math.sin(a));
        Coord2d rt = Coord2d.of(-fw.y, fw.x);
        Coord2d d = fw.mul(f).add(rt.mul(r));
        return(d.div(Math.hypot(d.x, d.y)));
    }

    public boolean keydown(Widget.KeyDownEvent ev) {
        walk = (ev.mods & KeyMatch.S) != 0;
        switch(ev.code) {
        case KeyEvent.VK_W: case KeyEvent.VK_A: case KeyEvent.VK_S: case KeyEvent.VK_D:
            held.add(ev.code);
            return(true);
        case KeyEvent.VK_ESCAPE:
            stop();
            return(true);
        }
        return(false);
    }

    public boolean keyup(Widget.KeyUpEvent ev) {
        walk = (ev.mods & KeyMatch.S) != 0;
        return(held.remove(ev.code));
    }

    public void lostfocus() {
        held.clear();
    }
}
