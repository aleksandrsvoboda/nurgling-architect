package architect.res;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;

/**
 * Everything placeable that is available offline.
 *
 * Two views over the same objects: the build menu (paginae/bld, named and grouped as in the game, mapped to
 * the object each blueprint places) and "all objects" (every gfx/terobjs resource with a model, grouped by path).
 */
public class Catalogue {
    public static class Entry {
        /** The object's resource, e.g. "gfx/terobjs/cupboard"; null when no model is available offline. */
        public final String res;
        public final String name;
        public final String category;
        /** Menu resource whose icon represents this entry (build menu only), or null. */
        public final String pagina;
        /** State data the object is placed with (which variant: clay or metal cauldron), or empty. */
        public final byte[] sdt;

        public Entry(String res, String name, String category, String pagina, byte[] sdt) {
            this.res = res;
            this.name = name;
            this.category = category;
            this.pagina = pagina;
            this.sdt = (sdt == null) ? new byte[0] : sdt;
        }

        public Entry(String res, String name, String category, String pagina) {
            this(res, name, category, pagina, null);
        }

        public boolean placeable() {
            return(res != null);
        }

        boolean matches(String q) {
            return(q.isEmpty() || name.toLowerCase().contains(q) || category.toLowerCase().contains(q)
                   || (res != null && res.toLowerCase().contains(q)));
        }

        public String toString() {
            return(name + " [" + res + "]");
        }
    }

    public final List<Entry> build;
    public final List<Entry> objects;
    /** Ground tilesets for painting: paving first, then natural ground. pagina = the tileset (its icon). */
    public final List<Entry> tiles;

    public Catalogue(List<Entry> build, List<Entry> objects, List<Entry> tiles) {
        this.build = buildmodels(build);
        this.objects = wallnames(objects);
        this.tiles = tiles;
    }

    /**
     * Build-menu entries whose object is named differently from the entry (paginae/bld/primtent places
     * gfx/terobjs/arch/primitivetent), found on the game's resource server; ":mask" picks the variant of a shared
     * model (the clay and metal cauldron are one model with different parts). Entries left out are not objects
     * (Tinker's tools are items) or have no model the server will hand out.
     */
    static final String[][] MODELS = {
        {"vstand", "gfx/terobjs/barterstand"}, {"symbelfire", "gfx/terobjs/bonfire"}, {"fireplace", "gfx/terobjs/pow:3"},
        {"primtent", "gfx/terobjs/arch/primitivetent"}, {"watermill", "gfx/terobjs/arch/waterwheelhouse"},
        {"minevator", "gfx/terobjs/minevator-top"}, {"stoneendstone", "gfx/terobjs/road/milestone-stone-e"},
        {"woodendstone", "gfx/terobjs/road/milestone-wood-e"}, {"ancaltar", "gfx/terobjs/ancestralshrine"},
        {"archtgt", "gfx/terobjs/archerytarget"}, {"finestudydesk", "gfx/terobjs/studydesk-big"},
        {"sturdybed", "gfx/terobjs/furn/bed-sturdy"}, {"clotharmchair", "gfx/terobjs/furn/armchair-cloth"},
        {"clothchair", "gfx/terobjs/furn/chair-cloth"}, {"rusticchair", "gfx/terobjs/furn/chair-rustic"},
        {"gardenbench", "gfx/terobjs/furn/gardenbench"}, {"rockingchair", "gfx/terobjs/furn/chair-rocking"},
        {"clothfootstool", "gfx/terobjs/furn/footstool-cloth"}, {"mannequin", "gfx/terobjs/mannequin-stand"},
        {"woodensign", "gfx/terobjs/woodensign"}, {"eleganttable", "gfx/terobjs/furn/table-elegant"},
        {"rustictable", "gfx/terobjs/furn/table-rustic"}, {"stonetable", "gfx/terobjs/furn/table-stone"},
        {"ctub", "gfx/terobjs/curdingtub"}, {"atower", "gfx/terobjs/archerytower"},
        {"wball", "gfx/terobjs/vehicle/wreckingball"}, {"press", "gfx/terobjs/winepress"},
        {"vilgate", "gfx/terobjs/villageidol"}, {"claim", "gfx/terobjs/pclaim"},
        {"rscaffold", "gfx/terobjs/embankmentscaffold"},
        {"ccauldron", "gfx/terobjs/cauldron:400"}, {"mcauldron", "gfx/terobjs/cauldron:800"},
        {"woodenplow", "gfx/terobjs/vehicle/plow:3"}, {"metalplow", "gfx/terobjs/vehicle/plow:5"},
    };

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for(byte x : b)
            sb.append(String.format("%02x", x & 0xff));
        return(sb.toString());
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for(int i = 0; i < b.length; i++)
            b[i] = (byte)Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return(b);
    }

    /** A part mask as the plain model sprite reads it (little-endian, Sprite.decnum). */
    static byte[] mask(long m) {
        return(new byte[] {(byte)m, (byte)(m >> 8), (byte)(m >> 16), (byte)(m >> 24)});
    }

    private static List<Entry> buildmodels(List<Entry> build) {
        Map<String, String> m = new HashMap<>();
        for(String[] e : MODELS)
            m.put("paginae/bld/" + e[0], e[1]);
        List<Entry> ret = new ArrayList<>();
        for(Entry e : build) {
            String r = (e.pagina == null) ? null : m.get(e.pagina);
            if(r == null || (e.res != null && e.sdt.length > 0)) {
                ret.add(e);
                continue;
            }
            int c = r.indexOf(':');
            ret.add(new Entry((c < 0) ? r : r.substring(0, c), e.name, e.category, e.pagina,
                              (c < 0) ? null : mask(Long.parseLong(r.substring(c + 1), 16))));
        }
        return(ret);
    }

    /**
     * Resources under gfx/terobjs that are not objects you could place: pieces the game lays over or beside
     * other objects (wall extension markers, drying-frame contents, the bare shapes rocks are built from),
     * construction sites, map-marker icons. Found by drawing every object (DevTools render audit): these either
     * need data only the server has or draw nothing on their own.
     */
    static boolean internal(String res) {
        String n = res.startsWith("gfx/terobjs/") ? res.substring(12) : res;
        return(n.startsWith("mm/") || (n.startsWith("dframe-")) || n.matches("bumlings/bumling\\d+")
               || Arrays.asList("arch/bounds", "arch/cpext", "arch/hwall", "arch/wallext", "barrel-opt", "barterarea",
                                "consobj", "consobj-pv", "dng/powersplit", "log", "moundbed-grown", "producesack",
                                "road/routeindicator", "trees/fallenfruit").contains(n));
    }

    /** Wall and fence pieces get proper names ("Palisade Gate", not "Palisadegate"), and every piece is
     *  listed even when the indexer never saw it (the brick wall's sections and gates). */
    private static List<Entry> wallnames(List<Entry> objects) {
        Map<String, String> names = architect.edit.Walls.names();
        List<Entry> ret = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for(Entry e : objects) {
            if(internal(e.res))
                continue;
            String n = names.get(e.res);
            ret.add((n == null) ? e : new Entry(e.res, n, e.category, e.pagina, e.sdt));
            seen.add(e.res);
        }
        for(Map.Entry<String, String> n : names.entrySet())
            if(!seen.contains(n.getKey()))
                ret.add(new Entry(n.getKey(), n.getValue(), "arch", null));
        return(ret);
    }

    public List<Entry> search(List<Entry> from, String query) {
        String q = query.trim().toLowerCase();
        return(from.stream().filter(e -> e.matches(q)).collect(Collectors.toList()));
    }

    /** Display name for an object resource: its build-menu name if it has one, else a name made from its path. */
    public String nameof(String res) {
        for(Entry e : build)
            if(res.equals(e.res))
                return(e.name);
        for(Entry e : objects)
            if(res.equals(e.res))
                return(e.name);
        return(prettify(res));
    }

    /* ---------- the shipped catalogue (names only, no art) ---------- */

    public static final int FORMAT = 1;
    /** Built by {@code DevTools catalogue}; packaged in the planner jar. */
    public static final String RESOURCE = "/architect/res/catalogue.json";

    private static JSONArray tojson(List<Entry> es) {
        JSONArray a = new JSONArray();
        for(Entry e : es) {
            JSONObject o = new JSONObject();
            if(e.res != null) o.put("r", e.res);
            o.put("n", e.name);
            o.put("c", e.category);
            if(e.pagina != null) o.put("p", e.pagina);
            if(e.sdt.length > 0) o.put("s", hex(e.sdt));
            a.put(o);
        }
        return(a);
    }

    private static List<Entry> fromjson(JSONArray a) {
        List<Entry> ret = new ArrayList<>();
        for(int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String sdt = o.optString("s", null);
            ret.add(new Entry(o.optString("r", null), o.getString("n"), o.getString("c"), o.optString("p", null),
                              (sdt == null) ? null : unhex(sdt)));
        }
        return(ret);
    }

    public String save() {
        JSONObject o = new JSONObject();
        o.put("format", FORMAT);
        o.put("build", tojson(build));
        o.put("objects", tojson(objects));
        o.put("tiles", tojson(tiles));
        return(o.toString(0));
    }

    public static Catalogue parse(String json) {
        JSONObject o = new JSONObject(json);
        if(o.optInt("format") != FORMAT)
            throw(new JSONException("unsupported catalogue format " + o.optInt("format")));
        return(new Catalogue(fromjson(o.optJSONArray("build")), fromjson(o.optJSONArray("objects")), fromjson(o.optJSONArray("tiles"))));
    }

    /** The catalogue shipped inside the planner jar. */
    public static Catalogue shipped() throws IOException {
        try(InputStream in = Catalogue.class.getResourceAsStream(RESOURCE)) {
            if(in == null)
                throw(new FileNotFoundException("the planner was built without its catalogue (" + RESOURCE + ")"));
            return(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    /** Every resource the catalogue refers to: objects, menu icons and ground tilesets. */
    public Set<String> resources() {
        Set<String> ret = new LinkedHashSet<>();
        for(List<Entry> l : Arrays.asList(build, objects, tiles))
            for(Entry e : l) {
                if(e.res != null) ret.add(e.res);
                if(e.pagina != null) ret.add(e.pagina);
            }
        return(ret);
    }

    static String prettify(String res) {
        String base = res.substring(res.lastIndexOf('/') + 1).replace('-', ' ').replace('_', ' ');
        return(base.isEmpty() ? res : Character.toUpperCase(base.charAt(0)) + base.substring(1));
    }
}
