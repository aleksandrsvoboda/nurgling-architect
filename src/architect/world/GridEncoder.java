package architect.world;

import haven.*;

import java.util.*;

import static haven.MCache.cmaps;

/**
 * Encodes GridData into the server's map-data payload, exactly what MCache.mapdata2 / Grid.fill parse:
 *   coord gc, uint8 ver=1, then layers {string name; uint8 len | 0x80+int32 len; body}:
 *   "m"  int64 grid id
 *   "t2" {uint16 encid; uint16 tileid; string res; uint16 resver}* 65535, then one uint8/uint16 per tile
 *   "h"  uint8 fmt=3, float32 per vertex (height / 11; fillz multiplies by 11)
 *
 * Tile ids are global for the whole map (MCache.sets), and their order drives transition blending,
 * so a TileRegistry hands them out consistently across grids.
 */
public class GridEncoder {
    public final TileRegistry tiles;

    public GridEncoder(TileRegistry tiles) {
        this.tiles = tiles;
    }

    public MessageBuf encode(Coord gc, GridData g) {
        MessageBuf out = new MessageBuf();
        out.addcoord(gc);
        out.adduint8(1);

        MessageBuf m = new MessageBuf();
        m.addint64(g.id);
        layer(out, "m", m);

        MessageBuf t = new MessageBuf();
        int[] tileid = new int[g.tilesets.length];
        for(int i = 0; i < g.tilesets.length; i++) {
            TileRegistry.Entry e = tiles.get(g.tilesets[i], g.prio[i]);
            tileid[i] = e.id;
            t.adduint16(i);
            t.adduint16(e.id);
            t.addstring(e.name);
            t.adduint16(e.ver);
        }
        t.adduint16(65535);
        boolean lg = (g.tilesets.length - 1) >= 256;
        for(int i = 0; i < g.tiles.length; i++) {
            if(lg)
                t.adduint16(g.tiles[i]);
            else
                t.adduint8(g.tiles[i]);
        }
        layer(out, "t2", t);

        MessageBuf h = new MessageBuf();
        h.adduint8(3);
        for(int i = 0; i < g.z.length; i++)
            h.addfloat32(g.z[i] / 11f);
        layer(out, "h", h);
        /* A written MessageBuf has no readable window yet; hand over a reader of the finished bytes. */
        return(new MessageBuf(out.fin()));
    }

    private static void layer(MessageBuf out, String name, MessageBuf body) {
        byte[] b = body.fin();
        out.addstring(name);
        if(b.length < 0x80) {
            out.adduint8(b.length);
        } else {
            out.adduint8(0x80);
            out.addint32(b.length);
        }
        out.addbytes(b);
    }

    /** Global tileset ids. A tileset keeps its preferred id unless another tileset already took it. */
    public static class TileRegistry {
        public static final String FALLBACK = "gfx/tiles/nil";

        public static class Entry {
            public final int id;
            public final String name;
            public final int ver;

            Entry(int id, String name, int ver) {
                this.id = id;
                this.name = name;
                this.ver = ver;
            }
        }

        private final Map<String, Entry> byname = new HashMap<>();
        private final Map<Integer, Entry> byid = new HashMap<>();
        private final Resource.Pool pool;

        public TileRegistry(Resource.Pool pool) {
            this.pool = pool;
        }

        public synchronized Entry get(String name, int prefid) {
            Entry e = byname.get(name);
            if(e != null)
                return(e);
            String real = name;
            int ver = version(name);
            if(ver < 0) {
                System.out.println("[architect] tileset missing, drawing " + FALLBACK + " instead: " + name);
                real = FALLBACK;
                ver = Math.max(version(FALLBACK), 0);
            }
            int id = prefid;
            if(id < 0 || id > 65000 || byid.containsKey(id)) {
                id = 1;
                for(int k : byid.keySet())
                    id = Math.max(id, k + 1);
            }
            e = new Entry(id, real, ver);
            byname.put(name, e);
            byid.put(id, e);
            return(e);
        }

        /** The version actually available offline, or -1 if missing or not a tileset. Tiles must be requested at that exact version. */
        private int version(String name) {
            if(pool == null)
                return(0);
            try {
                Resource r = pool.loadwait(name);
                /* Must really be a tileset: MCache.tileset() throws on the UI thread otherwise. */
                if(r.layer(Tileset.class) == null)
                    return(-1);
                return(r.ver);
            } catch(Resource.LoadException | Resource.BadResourceException e) {
                return(-1);
            }
        }
    }
}
