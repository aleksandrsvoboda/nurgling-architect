package architect.io;

import architect.gen.WorldGen;
import architect.world.*;
import org.json.*;

/** Saves which world a plan is drawn on (a generated world or the flat sandbox), and recreates it. */
public class WorldRef {
    public static JSONObject of(WorldSource w) {
        JSONObject o = new JSONObject();
        if(w instanceof WorldGen) {
            WorldGen g = (WorldGen)w;
            o.put("kind", "generated");
            o.put("seed", g.seed);
            o.put("style", g.style.name());
            o.put("size", g.size);
        } else if(w instanceof SandboxWorld) {
            SandboxWorld s = (SandboxWorld)w;
            o.put("kind", "sandbox");
            o.put("w", s.w);
            o.put("h", s.h);
            o.put("tile", s.tileset);
        }
        return(o);
    }

    /** The world described by o, or null for a kind this version no longer supports. */
    public static WorldSource open(JSONObject o) {
        switch(o.optString("kind")) {
        case "generated":
            return(new WorldGen(o.getLong("seed"), WorldGen.Style.valueOf(o.getString("style")), o.getInt("size")));
        case "sandbox":
            return(new SandboxWorld(o.getInt("w"), o.getInt("h"), o.optString("tile", "gfx/tiles/grass")));
        }
        return(null);
    }
}
