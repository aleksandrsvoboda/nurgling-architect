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

    /* Everything the tests make on disk goes here and is deleted at the end. */
    static final List<Path> temps = new ArrayList<>();

    static Path tmpdir(String prefix) throws java.io.IOException {
        Path p = Files.createTempDirectory(prefix);
        temps.add(p);
        return(p);
    }

    static Path tmpfile(String prefix, String suffix) throws java.io.IOException {
        Path p = Files.createTempFile(prefix, suffix);
        temps.add(p);
        return(p);
    }

    static void cleanup() {
        for(Path p : temps) {
            try(java.util.stream.Stream<Path> s = Files.walk(p)) {
                s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch(java.io.IOException e) {
                System.out.println("could not remove " + p);
            }
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
        Path tmp = tmpfile("plan", PlanFile.EXT);
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
        Path tmp = tmpfile("terrain", TerrainStore.EXT);
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

    /** Blueprint presets: the game's own file reads and writes back, turns match the game, stamps round-trip. */
    static void blueprints() throws Exception {
        Path game = ArchitectEnv.root.resolve("test/architect/data/game-preset.json");
        Blueprints.Preset p = Blueprints.load(game);
        check(!p.foreign && p.kind == Blueprints.Kind.LAYER && p.w == 16 && p.h == 15 && p.count() == 3, "game preset reads");
        Blueprints.Ghost caul = p.ghosts().stream().filter(g -> g.res.equals("gfx/terobjs/cauldron")).findFirst().orElse(null);
        check(caul != null && caul.sdt.length == 2 && caul.sdt[1] == 8 && caul.x == 159.5 && Math.abs(caul.angle - Math.PI / 2) < 1e-12, "game ghost fields");
        Blueprints.Preset again = Blueprints.parse(p.json());
        check(again.json().similar(p.json()), "written back unchanged");

        Path dir = tmpdir("bp");
        Blueprints.Preset imp = Blueprints.importFile(dir, game);
        check(imp.file.equals("Layer_01.json") && Files.exists(dir.resolve("Layer_01.json")), "import names the file like the game");
        org.json.JSONObject withextra = new org.json.JSONObject(new String(Files.readAllBytes(dir.resolve(imp.file)), "UTF-8"));
        withextra.put("futurefield", 7);
        Files.write(dir.resolve(imp.file), withextra.toString().getBytes("UTF-8"));
        Blueprints.Preset re = Blueprints.load(dir.resolve(imp.file));
        re.file = imp.file;
        re.name = "Renamed";
        Blueprints.write(dir, re);
        org.json.JSONObject after = new org.json.JSONObject(new String(Files.readAllBytes(dir.resolve(imp.file)), "UTF-8"));
        check(after.getString("name").equals("Renamed") && after.optInt("futurefield") == 7, "rename keeps unknown fields");
        check(Files.exists(dir.resolve(imp.file + ".bak")), "rewrite keeps a .bak like the game");
        Blueprints.Preset second = Blueprints.importFile(dir, game);
        check(second.file.equals("Layer_01-2.json"), "taken names get -2 like the game");
        Files.write(dir.resolve("newer.json"), "{\"format\":2,\"name\":\"Future\"}".getBytes("UTF-8"));
        Blueprints.Preset fut = Blueprints.all(dir).stream().filter(x -> x.name.equals("Future")).findFirst().orElse(null);
        boolean refused = false;
        try {
            Blueprints.write(dir, fut);
        } catch(java.io.IOException e) {
            refused = true;
        }
        check(fut != null && fut.foreign && refused, "newer formats listed, never rewritten");
        check(Blueprints.slug("  Mill yard / east!! ").equals("Mill_yard_east"), "slug like the game");

        /* turning: one quarter turn of a 2x1 box, as the game's PlannerPreset.transformed does it */
        Blueprints.Preset t = new Blueprints.Preset();
        t.w = 2; t.h = 1;
        Blueprints.Layer tl = new Blueprints.Layer("L");
        tl.ghosts.add(new Blueprints.Ghost("gfx/terobjs/cupboard", null, 16.5, 5.5, 0));
        t.layers.add(tl);
        Blueprints.Ghost tg = t.transformed(1, false).ghosts().get(0);
        Blueprints.Preset t1 = t.transformed(1, false);
        check(t1.w == 1 && t1.h == 2 && tg.x == 5.5 && tg.y == 16.5 && Math.abs(tg.angle - Math.PI / 2) < 1e-12, "quarter turn");
        Blueprints.Ghost mg = t.transformed(0, true).ghosts().get(0);
        check(mg.x == 22 - 16.5 && Math.abs(mg.angle - Math.PI) < 1e-12, "mirror");
        Blueprints.Ghost full = t.transformed(4, false).ghosts().get(0);
        check(full.x == 16.5 && full.y == 5.5, "four turns are none");

        /* capture a folder, stamp it elsewhere: same layout, new folder, one undo step */
        Plan plan = new Plan(session().glob);
        Layers.Node f = plan.layers.addFolder("Yard");
        Layers.Node la = plan.layers.addLayer("Pens", f.id), lb = plan.layers.addLayer("Trough", f.id);
        plan.add(new PlanObject("o1", "gfx/terobjs/cupboard", null, Coord2d.of(5 * 11 + 3, 7 * 11 + 5.5), 0, la.id));
        plan.add(new PlanObject("o2", "gfx/terobjs/cauldron", new byte[] {0, 8}, Coord2d.of(9 * 11 + 5.5, 8 * 11 + 2), 1, lb.id));
        Blueprints.Preset cap = BlueprintPlan.capturenode(plan, "Yard", f.id);
        check(cap.kind == Blueprints.Kind.FOLDER && cap.layers.size() == 2 && cap.count() == 2, "folder captured");
        Blueprints.Ghost c2 = cap.layers.get(1).ghosts.get(0);
        check(c2.sdt.length == 2 && c2.sdt[1] == 8, "variant kept");
        Blueprints.Preset loaded = Blueprints.parse(cap.json());
        int before = plan.size(), nodes = plan.layers.all().size();
        String made = BlueprintPlan.place(plan, loaded, Coord.of(40, 40), null);
        check(plan.size() == before + 2 && plan.layers.all().size() == nodes + 3 && plan.layers.get(made).folder, "stamp makes a folder of layers");
        PlanObject n1 = plan.objects().stream().filter(o -> !o.id.equals("o1") && o.res.equals("gfx/terobjs/cupboard")).findFirst().orElse(null);
        PlanObject n2 = plan.objects().stream().filter(o -> !o.id.equals("o2") && o.res.equals("gfx/terobjs/cauldron")).findFirst().orElse(null);
        Coord2d was = plan.get("o2").rc.sub(plan.get("o1").rc);
        boolean same = n1 != null && n2 != null && n2.rc.sub(n1.rc).equals(was)
            && (n1.rc.x - plan.get("o1").rc.x) % 11 == 0 && (n1.rc.y - plan.get("o1").rc.y) % 11 == 0 && n2.a == 1;
        check(same, "stamp keeps the sub-tile layout");
        plan.undo();
        check(plan.size() == before && plan.layers.get(made) == null, "one undo removes the stamp and its folder");
        plan.redo();
        check(plan.size() == before + 2 && plan.layers.get(made) != null, "redo brings it back");
        Blueprints.Preset reg = BlueprintPlan.captureregion(plan, "R", Coord2d.of(0, 0), Coord2d.of(9 * 11, 20 * 11));
        check(reg.count() == 1 && reg.kind == Blueprints.Kind.LAYER, "region takes what is inside");
    }

    public static void main(String[] args) throws Exception {
        /* Same isolated setup as the app, in a throwaway home: engine code reads nurgling config. */
        System.setProperty("architect.home", tmpdir("architect-test").toString());
        ArchitectEnv.init();
        gridRoundTrip();
        undoRedo();
        planFile();
        overlap();
        terrain();
        walls();
        catalogue();
        blueprints();
        System.out.println(passes + " passed, " + fails + " failed");
        cleanup();
        System.exit(fails == 0 ? 0 : 1);
    }
}
