package architect;

import architect.edit.*;
import architect.io.*;
import architect.world.*;
import haven.*;
import org.json.*;

import java.nio.file.*;
import java.util.*;

/**
 * Plain-main tests (ant test). Covers the logic that can be checked without a screen: the map-data encoding
 * the engine parses, the in-game Base planner export/import, plan files, undo/redo and overlap detection.
 */
public class TestAll {
    static int fails = 0, passes = 0;

    static void check(boolean ok, String what) {
        if(ok) {
            passes++;
        } else {
            fails++;
            System.out.println("FAIL: " + what);
        }
    }

    static Session session() {
        return(new Session(new LocalWorldTransport(new SandboxWorld(1, 1, "gfx/tiles/grass"), new GridEncoder(new GridEncoder.TileRegistry(null))),
                           new Session.User("test")));
    }

    /** A grid encoded by GridEncoder and parsed by the engine's MCache comes back identical. */
    static void gridRoundTrip() {
        Session sess = session();
        MCache map = sess.glob.map;
        int n = MCache.cmaps.x * MCache.cmaps.y;
        int[] tiles = new int[n];
        float[] z = new float[n];
        for(int i = 0; i < n; i++) {
            tiles[i] = (i % 7 == 0) ? 1 : 0;
            z[i] = (i % 100) * 0.5f - 3;
        }
        GridData g = new GridData(0x1234567890L, new String[] {"gfx/tiles/grass", "gfx/tiles/dirt"}, new int[] {-1, -1}, tiles, z);
        Coord gc = Coord.of(3, -2);
        map.request(gc);
        map.mapdata2(new GridEncoder(new GridEncoder.TileRegistry(null)).encode(gc, g));
        MCache.Grid mg;
        synchronized(map.grids) {
            mg = map.grids.get(gc);
        }
        check(mg != null, "grid filled");
        if(mg == null)
            return;
        check(mg.id == 0x1234567890L, "grid id");
        boolean zok = true, tok = true;
        for(int i = 0; i < n; i++) {
            if(Math.abs(mg.z[i] - z[i]) > 1e-3) zok = false;
            if(!map.tilesetname(mg.tiles[i]).equals(g.tilesets[tiles[i]])) tok = false;
        }
        check(zok, "heights survive encoding");
        check(tok, "tiles survive encoding");
    }

    static void undoRedo() {
        Plan plan = new Plan(session().glob);
        PlanObject o = new PlanObject("u1", "gfx/terobjs/cupboard", null, Coord2d.of(5, 5), 0, null);
        plan.add(o);
        plan.move(o, Coord2d.of(50, 5), 1);
        check(plan.get("u1").rc.equals(Coord2d.of(50, 5)), "moved");
        plan.undo();
        check(plan.get("u1").rc.equals(Coord2d.of(5, 5)), "undo move");
        plan.undo();
        check(plan.get("u1") == null, "undo add");
        plan.redo();
        plan.redo();
        check(plan.get("u1") != null && plan.get("u1").a == 1, "redo add+move");
        plan.begin("group");
        plan.remove(plan.get("u1"));
        plan.putarea(PlanArea.simple("Farm", java.awt.Color.RED, Area.sized(Coord.of(1, 1), Coord.of(4, 3)), null));
        plan.commit();
        check(plan.size() == 0 && plan.areas().size() == 1, "grouped edit applied");
        plan.undo();
        check(plan.size() == 1 && plan.areas().isEmpty(), "grouped edit undone in one step");
    }

    static void planFile() throws Exception {
        Plan plan = new Plan(session().glob);
        Layers.Node l2 = plan.layers.addLayer("Second", null);
        l2.locked = true;
        plan.add(new PlanObject("p1", "gfx/terobjs/cupboard", new byte[] {9}, Coord2d.of(12.5, 33.25), 0.75, l2.id));
        plan.putarea(PlanArea.simple("Farm", new java.awt.Color(1, 2, 3, 56), Area.sized(Coord.of(-4, 6), Coord.of(5, 2)), null));
        Path tmp = Files.createTempFile("plan", PlanFile.EXT);
        PlanFile.save(tmp, "T", new SandboxWorld(10, 10, "gfx/tiles/grass"), plan, Coord2d.of(100, 200));
        PlanFile.Data d = PlanFile.load(tmp);
        check(d.name.equals("T") && d.camera.equals(Coord2d.of(100, 200)), "plan header");
        check(d.layers.size() == 2 && d.layers.get(1).locked && d.layers.get(1).name.equals("Second"), "layers saved");
        PlanObject o = d.objects.get(0);
        check(o.id.equals("p1") && o.rc.equals(Coord2d.of(12.5, 33.25)) && o.a == 0.75 && o.layer.equals(l2.id) && o.sdt[0] == 9, "object saved");
        PlanArea a = d.areas.get(0);
        check(a.name.equals("Farm") && a.tiles.equals(Area.sized(Coord.of(-4, 6), Coord.of(5, 2))) && a.color.getAlpha() == 56, "area saved");
        check(WorldRef.open(d.world) instanceof SandboxWorld, "world reference");
        Files.deleteIfExists(tmp);
        Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName() + ".bak"));
    }

    static void overlap() {
        Coord2d[][] a = {{Coord2d.of(0, 0), Coord2d.of(10, 0), Coord2d.of(10, 10), Coord2d.of(0, 10)}};
        Coord2d[][] b = {{Coord2d.of(5, 5), Coord2d.of(15, 5), Coord2d.of(15, 15), Coord2d.of(5, 15)}};
        Coord2d[][] c = {{Coord2d.of(10, 0), Coord2d.of(20, 0), Coord2d.of(20, 10), Coord2d.of(10, 10)}};
        check(Collisions.overlapTest(a, b), "overlapping boxes collide");
        check(!Collisions.overlapTest(a, c), "flush boxes do not collide");
    }

    static void terrain() throws Exception {
        TerrainStore t = new TerrainStore(new SandboxWorld(2, 2, "gfx/tiles/grass"));
        t.snapshot();
        Coord v = Coord.of(150, 99);   /* grid (1,0), last row */
        check(t.vz(v) == 0, "flat start");
        t.setvz(v, 2 * TerrainStore.UNIT);
        t.setvz(Coord.of(3, 3), -3 * TerrainStore.UNIT);
        long[] soil = t.soil();
        check(soil[0] == 3 && soil[1] == 2, "soil counted in survey units: " + Arrays.toString(soil));
        t.settile(Coord.of(10, 10), "gfx/tiles/paving/graybrick");
        check("gfx/tiles/paving/graybrick".equals(t.tile(Coord.of(10, 10))) && "gfx/tiles/grass".equals(t.tile(Coord.of(11, 10))), "paint one tile");
        GridData g = t.grid(Coord.of(0, 0));
        check(g.tilesets[g.tiles[10 + 10 * 100]].equals("gfx/tiles/paving/graybrick"), "painted tile served");
        Path tmp = Files.createTempFile("terrain", TerrainStore.EXT);
        t.write(tmp);
        TerrainStore r = new TerrainStore(new SandboxWorld(2, 2, "gfx/tiles/grass"));
        r.read(tmp);
        check(Math.abs(r.vz(v) - 2 * TerrainStore.UNIT) < 1e-6 && Arrays.equals(r.soil(), soil), "terrain file round trip");
        check("gfx/tiles/paving/graybrick".equals(r.tile(Coord.of(10, 10))), "paving survives the file");
        Files.deleteIfExists(tmp);
    }

    static long count(Plan plan, Walls.Kit k, Walls.Piece p) {
        return(plan.objects().stream().filter(o -> o.res.equals(k.res(p))).count());
    }

    /** Walls lay like the game's: posts at the ends, a section per tile between, any of eight directions. */
    static void walls() {
        Plan plan = new Plan(session().glob);
        Walls.Kit k = Walls.kit("gfx/terobjs/arch/palisadecp");
        check(k != null && k.name.equals("Palisade") && Walls.kit("gfx/terobjs/arch/brickbiggate") != null, "kits found");
        Coord a = Coord.of(2, 2);
        /* east, 5 tiles: posts on (2,2) and (7,2), sections between */
        Walls.apply(plan, Walls.run(plan, k, a, Walls.centre(Coord.of(7, 2)).add(2, 3)), "Wall");
        check(count(plan, k, Walls.Piece.POST) == 2 && count(plan, k, Walls.Piece.SECTION) == 4, "straight run: 2 posts, 4 sections");
        boolean along = plan.objects().stream().filter(o -> o.res.equals(k.res(Walls.Piece.SECTION)))
            .allMatch(o -> Math.abs(Math.cos(o.a)) < 1e-9 && o.rc.y == Walls.centre(a).y);
        check(along, "sections run east on the row's tile centres");
        /* diagonal from (7,2) to (10,5): 1 new post, 2 sections turned 45 degrees */
        Walls.apply(plan, Walls.run(plan, k, Coord.of(7, 2), Walls.centre(Coord.of(10, 5))), "Wall");
        check(count(plan, k, Walls.Piece.POST) == 3 && count(plan, k, Walls.Piece.SECTION) == 6, "diagonal run reuses the corner post");
        check(plan.objects().stream().anyMatch(o -> o.rc.equals(Walls.centre(Coord.of(8, 3))) && Math.abs(Math.abs(o.a) - Math.PI / 4) < 1e-9), "diagonal section at 45 degrees");
        /* a wall crossing the first one makes a joint post instead of stacking */
        Walls.apply(plan, Walls.run(plan, k, Coord.of(4, 0), Walls.centre(Coord.of(4, 4))), "Wall");
        check(plan.objects().stream().filter(o -> Walls.tile(o.rc).equals(Coord.of(4, 2))).count() == 1
              && plan.objects().stream().anyMatch(o -> Walls.tile(o.rc).equals(Coord.of(4, 2)) && o.res.equals(k.res(Walls.Piece.POST))), "crossing becomes one post");
        /* a gate dropped onto the sections at (6,2): sections 5,6 go, posts at 4 (there) and 7 (there) */
        Walls.Layout g = Walls.gateinto(plan, k, Walls.Piece.GATE, Walls.centre(Coord.of(6, 2)).add(-2, 0));
        check(g != null, "gate fits into the wall");
        if(g != null) {
            Walls.apply(plan, g, "Gate");
            PlanObject gate = plan.objects().stream().filter(o -> o.res.equals(k.res(Walls.Piece.GATE))).findFirst().orElse(null);
            check(gate != null && gate.rc.equals(Coord2d.of(6 * 11, 2.5 * 11)), "gate centred between tiles 5 and 6");
            check(plan.objects().stream().noneMatch(o -> o.res.equals(k.res(Walls.Piece.SECTION)) && (Walls.tile(o.rc).equals(Coord.of(5, 2)) || Walls.tile(o.rc).equals(Coord.of(6, 2)))), "gate replaced its sections");
            plan.undo();
            check(count(plan, k, Walls.Piece.GATE) == 0 && count(plan, k, Walls.Piece.SECTION) == 7, "gate undone in one step");
        }
        check(Walls.gateinto(plan, k, Walls.Piece.BIGGATE, Walls.centre(Coord.of(3, 2))) == null, "big gate needs three sections");
        Walls.Layout lead = Walls.gatefrom(plan, k, Walls.Piece.BIGGATE, Coord.of(2, 2), Walls.centre(Coord.of(2, 8)));
        check(lead != null && lead.add.stream().anyMatch(o -> o.res.equals(k.res(Walls.Piece.POST)) && Walls.tile(o.rc).equals(Coord.of(2, 6))), "big gate off a post: second post 4 tiles on");
    }

    /** Build entries reach their real models; internal pieces stay out of the object list. */
    static void catalogue() throws Exception {
        architect.res.Catalogue c = architect.res.Catalogue.shipped();
        architect.res.Catalogue.Entry tent = c.build.stream().filter(e -> "paginae/bld/primtent".equals(e.pagina)).findFirst().orElse(null);
        check(tent != null && "gfx/terobjs/arch/primitivetent".equals(tent.res), "primitive tent resolves");
        architect.res.Catalogue.Entry cc = c.build.stream().filter(e -> "paginae/bld/ccauldron".equals(e.pagina)).findFirst().orElse(null);
        check(cc != null && "gfx/terobjs/cauldron".equals(cc.res) && cc.sdt.length == 4 && cc.sdt[1] == 0x04, "clay cauldron is the cauldron's clay variant");
        check(c.objects.stream().noneMatch(e -> e.res.equals("gfx/terobjs/arch/cpext") || e.res.startsWith("gfx/terobjs/mm/") || e.res.equals("gfx/terobjs/bumlings/bumling0")), "internal pieces hidden");
        check(c.objects.stream().anyMatch(e -> e.res.equals("gfx/terobjs/dframe")) || c.objects.stream().noneMatch(e -> e.res.startsWith("gfx/terobjs/dframe")), "drying frame itself kept");
        long grey = c.build.stream().filter(e -> !e.placeable()).count();
        check(grey == 2, "only Tinker's tools stay unplaceable (" + grey + ")");
    }

    public static void main(String[] args) throws Exception {
        /* Same isolated setup as the app, in a throwaway home: engine code reads nurgling config. */
        System.setProperty("architect.home", Files.createTempDirectory("architect-test").toString());
        ArchitectEnv.init();
        gridRoundTrip();
        undoRedo();
        planFile();
        overlap();
        terrain();
        walls();
        catalogue();
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
