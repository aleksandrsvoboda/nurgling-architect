package architect.world;

import static haven.MCache.cmaps;

/** One 100x100 grid of terrain, independent of any session: tileset names, tile indices, corner heights. */
public class GridData {
    public final long id;
    /** Tileset resource names, e.g. "gfx/tiles/grass". tiles[] indexes into this. */
    public final String[] tilesets;
    /** Priority per tileset (higher draws over lower at transitions); -1 = no preference. */
    public final int[] prio;
    public final int[] tiles;
    /** Heights in world units (as MCache.Grid.z), cmaps.x * cmaps.y. */
    public final float[] z;

    public GridData(long id, String[] tilesets, int[] prio, int[] tiles, float[] z) {
        if(tiles.length != cmaps.x * cmaps.y || z.length != cmaps.x * cmaps.y)
            throw(new IllegalArgumentException("grid must be " + cmaps));
        if(prio.length != tilesets.length)
            throw(new IllegalArgumentException("prio/tilesets length mismatch"));
        this.id = id;
        this.tilesets = tilesets;
        this.prio = prio;
        this.tiles = tiles;
        this.z = z;
    }

    public static GridData flat(long id, String tileset, float z) {
        int n = cmaps.x * cmaps.y;
        float[] zs = new float[n];
        java.util.Arrays.fill(zs, z);
        return(new GridData(id, new String[] {tileset}, new int[] {0}, new int[n], zs));
    }
}
