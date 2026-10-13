package architect.edit;

import haven.*;

import java.util.*;

/**
 * Walls and fences as the game builds them: cornerposts, one-tile sections between posts, and gates, which the
 * game only builds between two posts (two tiles apart for a gate, three for a big gate). Every piece sits on
 * tile centres and runs along its own y axis (models and obstacles: 1 tile across, 1/1/2/3 tiles long), so a
 * wall goes straight or diagonally between posts: any of the eight directions.
 */
public class Walls {
    public enum Piece {
        POST(1, "Wall"), SECTION(1, "Section"), GATE(2, "Gate"), BIGGATE(3, "Big gate");

        /** Tiles the piece covers along the wall. */
        public final int len;
        public final String label;

        Piece(int len, String label) {
            this.len = len;
            this.label = label;
        }

        public boolean gate() {return(this == GATE || this == BIGGATE);}
    }

    /** One kind of wall: its pieces' resources. */
    public static class Kit {
        public final String name;
        private final Map<Piece, String> res = new EnumMap<>(Piece.class);

        Kit(String name, String post, String section, String gate, String biggate) {
            this.name = name;
            res.put(Piece.POST, post);
            res.put(Piece.SECTION, section);
            res.put(Piece.GATE, gate);
            res.put(Piece.BIGGATE, biggate);
        }

        public String res(Piece p) {return(res.get(p));}

        public Piece piece(String r) {
            for(Map.Entry<Piece, String> e : res.entrySet())
                if(e.getValue().equals(r))
                    return(e.getKey());
            return(null);
        }
    }

    private static final String A = "gfx/terobjs/arch/";
    public static final List<Kit> KITS = Arrays.asList(
        new Kit("Palisade", A + "palisadecp", A + "palisadeseg", A + "palisadegate", A + "palisadebiggate"),
        new Kit("Brickwall", A + "brickwallcp", A + "brickwallseg", A + "brickwallgate", A + "brickbiggate"),
        new Kit("Roundpole Fence", A + "polecp", A + "poleseg", A + "polegate", A + "polebiggate"),
        new Kit("Dry Stone Fence", A + "drystonewallcp", A + "drystonewallseg", A + "drystonewallgate", A + "drystonewallbiggate"));

    /** The kit a resource belongs to, or null if it is no wall piece. */
    public static Kit kit(String res) {
        if(res != null)
            for(Kit k : KITS)
                if(k.piece(res) != null)
                    return(k);
        return(null);
    }

    /** Display names for the pieces, which the game only names by their post. */
    public static Map<String, String> names() {
        Map<String, String> ret = new LinkedHashMap<>();
        for(Kit k : KITS) {
            ret.put(k.res(Piece.POST), k.name);
            ret.put(k.res(Piece.SECTION), k.name + " Section");
            ret.put(k.res(Piece.GATE), k.name + " Gate");
            ret.put(k.res(Piece.BIGGATE), k.name + " Big Gate");
        }
        return(ret);
    }

    /* ---------- geometry ---------- */

    /** A tile, in world units (MCache.tilesz, kept literal so the catalogue can name pieces without the engine). */
    public static final double T = 11;
    /** The eight directions, as tile steps. */
    static final Coord[] DIRS = {Coord.of(1, 0), Coord.of(1, 1), Coord.of(0, 1), Coord.of(-1, 1),
                                 Coord.of(-1, 0), Coord.of(-1, -1), Coord.of(0, -1), Coord.of(1, -1)};

    public static Coord tile(Coord2d rc) {
        return(Coord.of((int)Math.floor(rc.x / T), (int)Math.floor(rc.y / T)));
    }

    public static Coord2d centre(Coord tc) {
        return(Coord2d.of((tc.x + 0.5) * T, (tc.y + 0.5) * T));
    }

    /** The angle that lays a piece along dir (pieces run along their y axis: (0,1) turned by a is (-sin a, cos a)). */
    public static double angle(Coord dir) {
        return(Math.atan2(-dir.x, dir.y));
    }

    /** The direction a piece turned by a runs in, as a tile step. */
    static Coord dir(double a) {
        return(Coord.of((int)Math.round(-Math.sin(a) * 1.4142), (int)Math.round(Math.cos(a) * 1.4142)));
    }

    /** The nearest of the eight directions to world vector d. */
    public static Coord dir8(Coord2d d) {
        int i = (int)Math.floorMod(Math.round(Math.atan2(d.y, d.x) / (Math.PI / 4)), 8L);
        return(DIRS[i]);
    }

    static boolean parallel(Coord a, Coord b) {
        return(a.equals(b) || a.equals(b.inv()));
    }

    /** Tiles from a toward world point to along dir, rounded to whole tiles. */
    static int steps(Coord a, Coord dir, Coord2d to) {
        Coord2d d = to.sub(centre(a)).div(T);
        double n = (dir.x != 0 && dir.y != 0) ? (Math.abs(d.x) + Math.abs(d.y)) / 2 : Math.abs(dir.x != 0 ? d.x : d.y);
        return((int)Math.round(n));
    }

    /** The tiles a planned piece covers. */
    static List<Coord> tiles(Piece p, Coord2d rc, double a) {
        List<Coord> ret = new ArrayList<>();
        Coord d = dir(a);
        for(int i = 0; i < p.len; i++) {
            double o = i - (p.len - 1) / 2.0;
            ret.add(tile(rc.add(d.x * o * T, d.y * o * T)));
        }
        return(ret);
    }

    /* ---------- laying out ---------- */

    /** What placing a stretch of wall does to the plan; empty when nothing would change. */
    public static class Layout {
        public final List<PlanObject> add = new ArrayList<>();
        public final List<PlanObject> remove = new ArrayList<>();

        public boolean isEmpty() {return(add.isEmpty() && remove.isEmpty());}
    }

    /** A piece of the plan's walls of one kit, by the tiles it covers. */
    private static class Placed {
        final PlanObject o;
        final Piece p;

        Placed(PlanObject o, Piece p) {
            this.o = o;
            this.p = p;
        }
    }

    private static Map<Coord, Placed> occupied(Plan plan, Kit kit) {
        Map<Coord, Placed> ret = new HashMap<>();
        for(PlanObject o : plan.objects()) {
            Piece p = kit.piece(o.res);
            if(p == null)
                continue;
            for(Coord t : tiles(p, o.rc, o.a))
                ret.put(t, new Placed(o, p));
        }
        return(ret);
    }

    private static PlanObject make(Kit kit, Piece p, Coord2d rc, double a) {
        return(new PlanObject(kit.res(p), null, rc, a));
    }

    /** Puts a post on tile t unless one is there; a section there becomes a post (a joint). False if a gate is in the way. */
    private static boolean post(Layout l, Kit kit, Map<Coord, Placed> occ, Coord t) {
        Placed e = occ.get(t);
        if(e == null) {
            l.add.add(make(kit, Piece.POST, centre(t), 0));
        } else if(e.p == Piece.SECTION) {
            l.remove.add(e.o);
            l.add.add(make(kit, Piece.POST, centre(t), 0));
        } else if(e.p.gate()) {
            return(false);
        }
        return(true);
    }

    /**
     * A wall from post tile a toward to: posts at both ends, sections between. Tiles already walled are left as
     * they are, except that a section where a post goes, or that the new wall crosses, becomes a post, so walls
     * join instead of stacking.
     */
    public static Layout run(Plan plan, Kit kit, Coord a, Coord2d to) {
        Layout l = new Layout();
        Map<Coord, Placed> occ = occupied(plan, kit);
        Coord2d d = to.sub(centre(a));
        if(Math.hypot(d.x, d.y) < T / 2) {
            post(l, kit, occ, a);
            return(l);
        }
        Coord dir = dir8(d);
        int n = steps(a, dir, to);
        double ang = angle(dir);
        for(int i = 0; i <= n; i++) {
            Coord t = a.add(dir.mul(i));
            Placed e = occ.get(t);
            if(i == 0 || i == n) {
                post(l, kit, occ, t);
            } else if(e == null) {
                l.add.add(make(kit, Piece.SECTION, centre(t), ang));
            } else if(e.p == Piece.SECTION && !parallel(dir(e.o.a), dir)) {
                /* crossing another wall: the crossing tile becomes a post both walls end on */
                post(l, kit, occ, t);
            }
        }
        return(l);
    }

    /**
     * A gate leading off post tile a toward to, as the game builds them: a second post 2 (big gate: 3) tiles
     * past the first, the gate between. Null if something other than sections is in the gate's way.
     */
    public static Layout gatefrom(Plan plan, Kit kit, Piece gate, Coord a, Coord2d to) {
        Coord2d d = to.sub(centre(a));
        if(Math.hypot(d.x, d.y) < T / 2)
            return(null);
        return(gate(plan, kit, gate, a, dir8(d)));
    }

    private static Layout gate(Plan plan, Kit kit, Piece gate, Coord a, Coord dir) {
        Layout l = new Layout();
        Map<Coord, Placed> occ = occupied(plan, kit);
        Set<PlanObject> gone = new HashSet<>();
        for(int i = 1; i <= gate.len; i++) {
            Placed e = occ.get(a.add(dir.mul(i)));
            if(e != null) {
                if(e.p != Piece.SECTION)
                    return(null);
                gone.add(e.o);
            }
        }
        if(!post(l, kit, occ, a) || !post(l, kit, occ, a.add(dir.mul(gate.len + 1))))
            return(null);
        l.remove.addAll(gone);
        Coord2d c = centre(a).add(centre(a.add(dir.mul(gate.len + 1)))).div(2);
        l.add.add(make(kit, gate, c, angle(dir)));
        return(l);
    }

    /**
     * A gate set into an existing wall at world point at: it takes the place of the sections there, centred on the
     * section under the cursor (a 2-tile gate leans toward the cursor), and the sections on either side become its
     * posts. Null when there is no straight run of sections there long enough.
     */
    public static Layout gateinto(Plan plan, Kit kit, Piece gate, Coord2d at) {
        Map<Coord, Placed> occ = occupied(plan, kit);
        Placed s = occ.get(tile(at));
        if(s == null || s.p != Piece.SECTION)
            return(null);
        Coord dir = dir(s.o.a), t = tile(at);
        Coord first;
        if(gate.len == 3) {
            first = t.sub(dir);
        } else {
            /* lean toward the half of the tile the cursor is in */
            Coord2d off = at.sub(centre(t));
            first = ((off.x * dir.x + off.y * dir.y) >= 0) ? t : t.sub(dir);
        }
        for(int i = 0; i < gate.len; i++) {
            Placed e = occ.get(first.add(dir.mul(i)));
            if(e == null || e.p != Piece.SECTION || !parallel(dir(e.o.a), dir))
                return(null);
        }
        for(Coord end : new Coord[] {first.sub(dir), first.add(dir.mul(gate.len))}) {
            Placed e = occ.get(end);
            if(e == null || e.p.gate())
                return(null);
        }
        return(gate(plan, kit, gate, first.sub(dir), dir));
    }

    /** A gate standing on its own at post tile a, pointing the way a piece turned by ang runs. */
    public static Layout gatealong(Plan plan, Kit kit, Piece gate, Coord a, double ang) {
        return(gate(plan, kit, gate, a, dir(ang)));
    }

    /** The gate in a layout, if it has one. */
    public static PlanObject gateof(Kit kit, Layout l) {
        for(PlanObject o : l.add) {
            Piece p = kit.piece(o.res);
            if(p != null && p.gate())
                return(o);
        }
        return(null);
    }

    /** Applies a layout to the plan as one undo step. */
    public static void apply(Plan plan, Layout l, String name) {
        if(l == null || l.isEmpty())
            return;
        plan.begin(name);
        try {
            for(PlanObject o : l.remove)
                plan.remove(o);
            for(PlanObject o : l.add)
                plan.add(o);
        } finally {
            plan.commit();
        }
    }
}
