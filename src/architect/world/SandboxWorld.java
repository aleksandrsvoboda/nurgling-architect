package architect.world;

import haven.*;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;

/** A flat world of w x h grids, all one tileset. Grid ids are synthetic (negative) so they never match a real grid. */
public class SandboxWorld implements WorldSource {
    public final int w, h;
    public final String tileset;

    public SandboxWorld(int w, int h, String tileset) {
        this.w = w;
        this.h = h;
        this.tileset = tileset;
    }

    public GridData grid(Coord gc) {
        if(gc.x < 0 || gc.y < 0 || gc.x >= w || gc.y >= h)
            return(null);
        return(GridData.flat(-1L - (gc.x + (long)gc.y * w), tileset, 0));
    }

    public Coord2d spawn() {
        return(Coord2d.of(cmaps.mul(w, h).div(2)).mul(tilesz).add(tilesz.div(2)));
    }

    public Area bounds() {
        return(Area.sized(Coord.z, Coord.of(w, h)));
    }

    public String describe() {
        return(String.format("Sandbox %dx%d grids (%s)", w, h, tileset));
    }
}
