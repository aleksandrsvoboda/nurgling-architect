package architect.res;

import haven.*;

import java.util.*;

/**
 * A light, engine-free read of a .res file: version and the raw layers, without instantiating anything.
 * Used to index thousands of resources quickly; real loading still goes through haven.Resource.
 *
 * Format (Resource.load): "Haven Resource 1", uint16 version, then until EOF:
 * cstring layer name, int32 length, bytes.
 */
public class ResFile {
    private static final byte[] SIG = "Haven Resource 1".getBytes(Utils.ascii);
    public final int ver;
    public final List<String> names = new ArrayList<>();
    private final List<byte[]> data = new ArrayList<>();

    private ResFile(int ver) {
        this.ver = ver;
    }

    /** Parses res bytes, or returns null when they are not a resource. */
    public static ResFile parse(byte[] raw) {
        if(raw.length < SIG.length + 2 || !Arrays.equals(Arrays.copyOf(raw, SIG.length), SIG))
            return(null);
        MessageBuf in = new MessageBuf(raw, SIG.length, raw.length - SIG.length);
        try {
            ResFile ret = new ResFile(in.uint16());
            while(!in.eom()) {
                String nm = in.string();
                int len = in.int32();
                ret.names.add(nm);
                ret.data.add(in.bytes(len));
            }
            return(ret);
        } catch(Message.BinError e) {
            return(null);
        }
    }

    public boolean has(String layer) {
        return(names.contains(layer));
    }

    public byte[] layer(String name) {
        int i = names.indexOf(name);
        return((i < 0) ? null : data.get(i));
    }

    /** The "tooltip" layer: the object's display name, or null. */
    public String tooltip() {
        byte[] b = layer("tooltip");
        return((b == null) ? null : new String(b, Utils.utf8));
    }

    /** The "action" layer of a menu entry (pagina), as Resource.AButton reads it. */
    public static class Action {
        public final String parent, name;
        public final String[] ad;

        Action(String parent, String name, String[] ad) {
            this.parent = parent;
            this.name = name;
            this.ad = ad;
        }

        /** The blueprint id for "bp" actions (what the server places when you click it), else null. */
        public String bp() {
            return((ad.length >= 2 && ad[0].equals("bp")) ? ad[1] : null);
        }
    }

    public Action action() {
        byte[] b = layer("action");
        if(b == null)
            return(null);
        try {
            MessageBuf in = new MessageBuf(b);
            String pr = in.string();
            in.uint16();
            String name = in.string();
            in.string();    /* prerequisite skill */
            in.uint16();    /* hotkey */
            String[] ad = new String[in.uint16()];
            for(int i = 0; i < ad.length; i++)
                ad[i] = in.string();
            return(new Action(pr.isEmpty() ? null : pr, name, ad));
        } catch(Message.BinError e) {
            return(null);
        }
    }
}
