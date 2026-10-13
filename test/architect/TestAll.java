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

    public static void main(String[] args) throws Exception {
        /* Same isolated setup as the app, in a throwaway home: engine code reads nurgling config. */
        System.setProperty("architect.home", Files.createTempDirectory("architect-test").toString());
        ArchitectEnv.init();
        gridRoundTrip();
        undoRedo();
        planFile();
        overlap();
        terrain();
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
