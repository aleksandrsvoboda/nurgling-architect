package architect.res;

import architect.io.AreaPresets;
import nurgling.conf.CropRegistry;
import nurgling.tools.*;
import nurgling.widgets.Specialisation;
import org.json.*;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * What an area can be set to, read from the game's own classes in the engine (not copied, so it is exactly
 * what that client version offers): the specialisations with their names and icons and the bots that use
 * them, their subtype choices, crop fields' harvest stages, and the items Take/Put rules can name - the
 * client's item vocabulary (VSpec), written exactly as the game writes them.
 */
public class AreaRules {
    public static class Spec {
        public final String name, pretty;
        public final BufferedImage icon;

        Spec(String name, String pretty, BufferedImage icon) {
            this.name = name;
            this.pretty = pretty;
            this.icon = icon;
        }
    }

    public static class Item {
        public final String name, res;
        public final List<String> categories = new ArrayList<>();

        Item(String name, String res) {
            this.name = name;
            this.res = res;
        }

        /** A Take/Put entry as the game stores one: {name, static}. */
        public JSONObject entry() {
            JSONObject o = new JSONObject();
            if(res != null)
                o.put("static", res);
            o.put("name", name);
            return(o);
        }
    }

    private static List<Spec> specs = null;
    private static List<Item> items = null;

    /** Every specialisation, in the game's order. */
    public static synchronized List<Spec> specs() {
        if(specs == null) {
            List<Spec> l = new ArrayList<>();
            for(Specialisation.SpecName n : Specialisation.SpecName.values()) {
                Specialisation.SpecialisationItem it = Specialisation.findSpecialisation(n.toString());
                if(it != null) {
                    l.add(new Spec(it.name, it.prettyName, it.image));
                } else {
                    FuelZones.Zone z = FuelZones.of(n);
                    if(z != null)
                        l.add(new Spec(n.toString(), z.prettyName, haven.Resource.loadsimg(FuelZones.ICON)));
                }
            }
            specs = l;
        }
        return(specs);
    }

    public static Spec spec(String name) {
        for(Spec s : specs())
            if(s.name.equals(name))
                return(s);
        return(null);
    }

    /** The display name of a specialisation, the raw name for one this engine doesn't know. */
    public static String pretty(String name) {
        Spec s = spec(name);
        return((s != null) ? s.pretty : name);
    }

    /** Subtype choices (crop, fuel material, barrel contents...), empty when it has none. */
    public static List<String> subtypes(String spec) {
        List<String> l = SpecialisationData.data.get(spec);
        return((l == null) ? Collections.emptyList() : l);
    }

    /** Harvest stages a crop field of this subtype can wait for; empty when there is no choice (the game's rule). */
    public static List<Integer> stages(String subtype) {
        NAlias plant = CropRegistry.getFieldCrop(subtype);
        if(plant == null)
            return(Collections.emptyList());
        List<Integer> s = CropRegistry.getHarvestStages(plant);
        return((s.size() > 1) ? s : Collections.emptyList());
    }

    public static String describestage(String subtype, int stage) {
        NAlias plant = CropRegistry.getFieldCrop(subtype);
        return((plant == null) ? "" : CropRegistry.describeStage(plant, stage));
    }

    /** The bots that use a specialisation, as one line; null while the game's scan is still running. */
    public static String bots(String spec) {
        SpecialisationUsage.request();
        List<String> b = SpecialisationUsage.botsFor(spec);
        if(b == null)
            return(null);
        if(b.isEmpty())
            return("No bot uses it");
        return("Used by " + String.join(", ", b));
    }

    /**
     * Items for Take/Put: the client's own vocabulary (VSpec, grouped in categories), plus every item the
     * built-in area presets name. A-Z by name.
     */
    public static synchronized List<Item> items() {
        if(items == null) {
            Map<String, Item> byname = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for(Map.Entry<String, ArrayList<JSONObject>> c : VSpec.categories.entrySet()) {
                for(JSONObject o : c.getValue()) {
                    String n = o.optString("name", null);
                    if(n == null)
                        continue;
                    Item it = byname.computeIfAbsent(n, k -> new Item(n, o.optString("static", null)));
                    if(!it.categories.contains(c.getKey()))
                        it.categories.add(c.getKey());
                }
            }
            for(AreaPresets.Preset p : AreaPresets.builtins()) {
                for(JSONObject a : p.areas) {
                    for(String k : new String[] {"in", "out"}) {
                        JSONArray arr = a.optJSONArray(k);
                        for(int i = 0; arr != null && i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            String n = (o == null) ? null : o.optString("name", null);
                            if(n != null)
                                byname.computeIfAbsent(n, x -> new Item(n, o.optString("static", null)));
                        }
                    }
                }
            }
            for(Item it : byname.values())
                Collections.sort(it.categories);
            items = new ArrayList<>(byname.values());
        }
        return(items);
    }
}
