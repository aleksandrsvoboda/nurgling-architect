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

        public Entry(String res, String name, String category, String pagina) {
            this.res = res;
            this.name = name;
            this.category = category;
            this.pagina = pagina;
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
        this.build = build;
        this.objects = wallnames(objects);
        this.tiles = tiles;
    }

    /** Wall and fence pieces get proper names ("Palisade Gate", not "Palisadegate"), and every piece is
     *  listed even when the indexer never saw it (the brick wall's sections and gates). */
    private static List<Entry> wallnames(List<Entry> objects) {
        Map<String, String> names = architect.edit.Walls.names();
        List<Entry> ret = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for(Entry e : objects) {
            String n = names.get(e.res);
            ret.add((n == null) ? e : new Entry(e.res, n, e.category, e.pagina));
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
            a.put(o);
        }
        return(a);
    }

    private static List<Entry> fromjson(JSONArray a) {
        List<Entry> ret = new ArrayList<>();
        for(int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            ret.add(new Entry(o.optString("r", null), o.getString("n"), o.getString("c"), o.optString("p", null)));
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
