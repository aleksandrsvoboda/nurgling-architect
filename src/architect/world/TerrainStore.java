package architect.world;

import haven.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static haven.MCache.cmaps;

/**
 * The plan's own copy of its ground: every grid of the world, editable (heights for digging/filling, tiles
 * for paving), with the original heights kept to count soil moved. Saved next to the plan (.hterrain).
 * Purely visual; nothing is exported.
 *
 * Heights sit on tile corners (vertices): vertex (x,y) of a grid is the top-left corner of its tile (x,y),
 * so every vertex belongs to exactly one grid. One unit of soil is 0.1 m at one vertex = 1.1 world units
 * (the in-game Land survey's units).
 */
public class TerrainStore implements WorldSource {
    public static final String EXT = ".hterrain";
    public static final float UNIT = 1.1f;
    private static final byte[] MAGIC = "NARCHTERRAIN".getBytes();
    private static final int VERSION = 1;

    public static class TGrid {
        public final long id;
        public final List<String> tilesets;
        public final int[] tiles;
        public final float[] z;
        /** Heights as first generated, for soil accounting. */
        public final float[] z0;

        TGrid(long id, List<String> tilesets, int[] tiles, float[] z, float[] z0) {
            this.id = id; this.tilesets = tilesets; this.tiles = tiles; this.z = z; this.z0 = z0;
        }

        static TGrid of(GridData g) {
            return(new TGrid(g.id, new ArrayList<>(Arrays.asList(g.tilesets)), g.tiles.clone(), g.z.clone(), g.z.clone()));
        }

        TGrid copy() {
            return(new TGrid(id, new ArrayList<>(tilesets), tiles.clone(), z.clone(), z0));
        }

        GridData data() {
            int[] prio = new int[tilesets.size()];
            Arrays.fill(prio, -1);
            return(new GridData(id, tilesets.toArray(new String[0]), prio, tiles.clone(), z.clone()));
        }

        int tileset(String name) {
            int i = tilesets.indexOf(name);
            if(i < 0) {
                tilesets.add(name);
                i = tilesets.size() - 1;
            }
            return(i);
        }
    }

    public final WorldSource base;
    private final Map<Coord, TGrid> grids = new HashMap<>();
    public int seq = 0;

    public TerrainStore(WorldSource base) {
        this.base = base;
    }

    /** Copies every grid of the base world now, so later edits start from a fixed copy. */
    public void snapshot() {
        Area b = base.bounds();
        for(Coord gc : b)
            get(gc);
    }

    private synchronized TGrid get(Coord gc) {
        TGrid t = grids.get(gc);
        if(t == null) {
            GridData g = base.grid(gc);
            if(g == null)
                return(null);
            grids.put(gc, t = TGrid.of(g));
        }
        return(t);
    }

    /* ---------- WorldSource ---------- */

    public GridData grid(Coord gc) {
        if(!base.bounds().contains(gc))
            return(null);
        TGrid t = get(gc);
        if(t == null)
            return(null);
        synchronized(this) {
            return(t.data());
        }
    }

    public Coord2d spawn() {return(base.spawn());}
    public Area bounds() {return(base.bounds());}
    public String describe() {return(base.describe());}

    /* ---------- editing ---------- */

    /** Height at vertex vc (world tile-corner coordinates), or NaN outside the region. */
    public synchronized float vz(Coord vc) {
        TGrid t = get(vc.div(cmaps));
        return((t == null) ? Float.NaN : t.z[vc.mod(cmaps).x + vc.mod(cmaps).y * cmaps.x]);
    }

    public synchronized float vz0(Coord vc) {
        TGrid t = get(vc.div(cmaps));
        return((t == null) ? Float.NaN : t.z0[vc.mod(cmaps).x + vc.mod(cmaps).y * cmaps.x]);
    }

    /** Sets a vertex height; returns the grid that changed (null if outside). */
    public synchronized Coord setvz(Coord vc, float z) {
        Coord gc = vc.div(cmaps);
        TGrid t = get(gc);
        if(t == null)
            return(null);
        t.z[vc.mod(cmaps).x + vc.mod(cmaps).y * cmaps.x] = z;
        seq++;
        return(gc);
    }

    public synchronized String tile(Coord tc) {
        TGrid t = get(tc.div(cmaps));
        return((t == null) ? null : t.tilesets.get(t.tiles[tc.mod(cmaps).x + tc.mod(cmaps).y * cmaps.x]));
    }

    public synchronized Coord settile(Coord tc, String tileset) {
        Coord gc = tc.div(cmaps);
        TGrid t = get(gc);
        if(t == null)
            return(null);
        t.tiles[tc.mod(cmaps).x + tc.mod(cmaps).y * cmaps.x] = t.tileset(tileset);
        seq++;
        return(gc);
    }

    /** A copy of a grid's editable state, for undo. */
    public synchronized TGrid save(Coord gc) {
        TGrid t = get(gc);
        return((t == null) ? null : t.copy());
    }

    public synchronized void restore(Coord gc, TGrid state) {
        if(state != null) {
            grids.put(gc, state.copy());
            seq++;
        }
    }

    /** Soil units moved compared with the original ground: {dug, filled}. */
    public synchronized long[] soil() {
        long dug = 0, filled = 0;
        for(TGrid t : grids.values()) {
            for(int i = 0; i < t.z.length; i++) {
                int d = Math.round((t.z[i] - t.z0[i]) / UNIT);
                if(d > 0) filled += d; else dug -= d;
            }
        }
        return(new long[] {dug, filled});
    }

    /* ---------- file ---------- */

    public synchronized void write(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(tmp))))) {
            out.write(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(grids.size());
            for(Map.Entry<Coord, TGrid> e : grids.entrySet()) {
                TGrid t = e.getValue();
                out.writeInt(e.getKey().x);
                out.writeInt(e.getKey().y);
                out.writeLong(t.id);
                out.writeInt(t.tilesets.size());
                for(String s : t.tilesets)
                    out.writeUTF(s);
                for(int v : t.tiles)
                    out.writeShort(v);
                for(float v : t.z)
                    out.writeFloat(v);
                for(float v : t.z0)
                    out.writeFloat(v);
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized void read(Path file) throws IOException {
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(file))))) {
            byte[] m = new byte[MAGIC.length];
            in.readFully(m);
            if(!Arrays.equals(m, MAGIC) || in.readInt() != VERSION)
                throw(new IOException("not a terrain file"));
            int n = in.readInt();
            int sz = cmaps.x * cmaps.y;
            for(int k = 0; k < n; k++) {
                Coord gc = Coord.of(in.readInt(), in.readInt());
                long id = in.readLong();
                List<String> ts = new ArrayList<>();
                for(int i = 0, c = in.readInt(); i < c; i++)
                    ts.add(in.readUTF());
                int[] tiles = new int[sz];
                for(int i = 0; i < sz; i++)
                    tiles[i] = in.readUnsignedShort();
                float[] z = new float[sz], z0 = new float[sz];
                for(int i = 0; i < sz; i++)
                    z[i] = in.readFloat();
                for(int i = 0; i < sz; i++)
                    z0[i] = in.readFloat();
                grids.put(gc, new TGrid(id, ts, tiles, z, z0));
            }
        }
        seq++;
    }
}
