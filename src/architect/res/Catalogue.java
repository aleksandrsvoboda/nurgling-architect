package architect.res;

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
        this.objects = objects;
        this.tiles = tiles;
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

    static String prettify(String res) {
        String base = res.substring(res.lastIndexOf('/') + 1).replace('-', ' ').replace('_', ' ');
        return(base.isEmpty() ? res : Character.toUpperCase(base.charAt(0)) + base.substring(1));
    }
}
