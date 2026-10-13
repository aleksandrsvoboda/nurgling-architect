package architect.edit;

import haven.*;
import org.json.*;

import java.awt.Color;
import java.util.*;

/**
 * A planned nurgling area: a tile rectangle with the fields of an area preset entry (name, folder path,
 * colour, hidden flag, specialisations, Take/Put rules), so it exports as an area preset unchanged.
 * Immutable: edits replace it (which is what undo records).
 */
public class PlanArea {
    public final String id;
    public final String name, path;
    public final Color color;
    /** Tiles covered, in the planner's world tile coordinates; br exclusive. */
    public final Area tiles;
    public final boolean hide;
    /** Raw preset JSON arrays, kept verbatim ("[]" when empty). */
    public final String spec, in, out;
    public final String fill;
    public final String layer;

    public PlanArea(String id, String name, String path, Color color, Area tiles, boolean hide,
                    String spec, String in, String out, String fill, String layer) {
        this.id = id; this.name = name; this.path = path; this.color = color; this.tiles = tiles; this.hide = hide;
        this.spec = spec; this.in = in; this.out = out; this.fill = fill; this.layer = layer;
    }

    public static PlanArea simple(String name, Color color, Area tiles, String layer) {
        return(new PlanArea(UUID.randomUUID().toString(), name, "", color, tiles, false, "[]", "[]", "[]", "DEFAULT", layer));
    }

    public PlanArea with(String name, Color color, Area tiles, String layer) {
        return(new PlanArea(id, name, path, color, tiles, hide, spec, in, out, fill, layer));
    }

    public PlanArea copy(Area tiles) {
        return(new PlanArea(UUID.randomUUID().toString(), name, path, color, tiles, hide, spec, in, out, fill, layer));
    }

    /** An area-preset "areas" entry, positioned relative to origin (tiles). */
    public JSONObject preset(Coord origin) {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("path", path);
        o.put("x", tiles.ul.x - origin.x);
        o.put("y", tiles.ul.y - origin.y);
        o.put("w", tiles.sz().x);
        o.put("h", tiles.sz().y);
        JSONObject c = new JSONObject();
        c.put("r", color.getRed()); c.put("g", color.getGreen()); c.put("b", color.getBlue()); c.put("a", color.getAlpha());
        o.put("color", c);
        o.put("hide", hide);
        o.put("spec", new JSONArray(spec));
        o.put("in", new JSONArray(in));
        o.put("out", new JSONArray(out));
        o.put("pile_fill_direction", fill);
        return(o);
    }

    /** Reads an area-preset entry placed with its top-left at origin (tiles). */
    public static PlanArea frompreset(JSONObject a, Coord origin, String layer) {
        JSONObject c = a.optJSONObject("color");
        Color col = (c != null) ? new Color(c.getInt("r"), c.getInt("g"), c.getInt("b"), c.optInt("a", 56)) : new Color(194, 194, 65, 56);
        Coord ul = origin.add(a.getInt("x"), a.getInt("y"));
        return(new PlanArea(UUID.randomUUID().toString(), a.getString("name"), a.optString("path", ""), col,
                            Area.sized(ul, Coord.of(a.getInt("w"), a.getInt("h"))), a.optBoolean("hide", false),
                            arr(a, "spec"), arr(a, "in"), arr(a, "out"), a.optString("pile_fill_direction", "DEFAULT"), layer));
    }

    private static String arr(JSONObject a, String k) {
        JSONArray v = a.optJSONArray(k);
        return((v == null) ? "[]" : v.toString());
    }

    public JSONObject save() {
        JSONObject o = preset(Coord.z);
        o.put("id", id);
        o.put("layer", layer);
        return(o);
    }

    public static PlanArea load(JSONObject o) {
        PlanArea a = frompreset(o, Coord.z, o.optString("layer", null));
        return(new PlanArea(o.getString("id"), a.name, a.path, a.color, a.tiles, a.hide, a.spec, a.in, a.out, a.fill, a.layer));
    }

    public boolean same(PlanArea b) {
        return(b != null && id.equals(b.id) && name.equals(b.name) && path.equals(b.path) && color.equals(b.color)
               && tiles.equals(b.tiles) && hide == b.hide && spec.equals(b.spec) && in.equals(b.in) && out.equals(b.out)
               && fill.equals(b.fill) && Objects.equals(layer, b.layer));
    }
}
