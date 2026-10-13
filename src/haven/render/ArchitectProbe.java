package haven.render;

import haven.Gob;

import java.util.ArrayList;

/** Dev: how many drawable nodes the engine built for a gob (render-tree slots are package-private). */
public class ArchitectProbe {
    public static int drawn(Gob g) {
        int n = 0;
        for(RenderTree.Slot s : new ArrayList<>(g.slots))
            n += count(s);
        return(n);
    }

    private static int count(RenderTree.Slot s) {
        if(!(s instanceof RenderTree.TreeSlot))
            return(0);
        RenderTree.TreeSlot t = (RenderTree.TreeSlot)s;
        int n = (t.obj() instanceof Rendered) ? 1 : 0;
        for(RenderTree.TreeSlot c : t.children())
            n += count(c);
        return(n);
    }
}
