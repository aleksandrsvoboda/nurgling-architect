package architect;

import haven.ResCache;

import java.io.*;
import java.net.URI;
import java.nio.file.*;

/**
 * Reads the game's HashDirCache without ever writing to it, locking it or creating files.
 *
 * Same addressing as haven.HashDirCache: file "%016x.%d" of namehash(namehash(0, cacheId), name),
 * collisions chained by index, each file starting with [byte 1][UTF cacheId][UTF name] then the payload.
 * The whole blob is read into memory so no handle stays open while the running game rewrites it.
 */
public class ReadOnlyGameCache implements ResCache {
    public final Path base;
    public final URI id;
    private final long idhash;

    public ReadOnlyGameCache(Path base, URI id) {
        this.base = base;
        this.id = id;
        this.idhash = namehash(0, id.toString());
    }

    private static long namehash(long h, String name) {
        for(int i = 0; i < name.length(); i++)
            h = (h * 31) + name.charAt(i);
        return(h);
    }

    public OutputStream store(String name) throws IOException {
        throw(new IOException("the game cache is read-only for the planner: " + name));
    }

    public InputStream fetch(String name) throws IOException {
        byte[] data = read(name);
        if(data == null)
            throw(new FileNotFoundException(name));
        return(new ByteArrayInputStream(data));
    }

    /** Payload bytes of a cached blob, or null if the cache has no such name. */
    public byte[] read(String name) throws IOException {
        long h = namehash(idhash, name);
        for(int idx = 0; ; idx++) {
            Path p = base.resolve(String.format("%016x.%d", h, idx));
            byte[] raw;
            try {
                raw = Files.readAllBytes(p);
            } catch(NoSuchFileException e) {
                return(null);
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            try {
                if(in.readByte() != 1)
                    continue;
                String cid = in.readUTF();
                String nm = in.readUTF();
                if(cid.equals(id.toString()) && nm.equals(name)) {
                    int off = raw.length - in.available();
                    byte[] ret = new byte[raw.length - off];
                    System.arraycopy(raw, off, ret, 0, ret.length);
                    return(ret);
                }
            } catch(EOFException e) {
                /* Truncated or being written by the game right now; treat as a different entry. */
            }
        }
    }

    public String toString() {
        return("ReadOnlyGameCache(" + base + ", " + id + ")");
    }
}
