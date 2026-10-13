package architect.edit;

import java.util.*;

/**
 * The plan's layer tree, shaped like the in-game Base planner's: top-level folders holding layers, and
 * top-level layers. Every object belongs to one layer; new objects go to the active layer.
 * Hidden layers' objects are taken out of the view; locked layers' objects can't be selected.
 */
public class Layers {
    public static class Node {
        public final String id;
        public final boolean folder;
        public String name;
        public boolean visible = true, locked = false, open = true;
        /** Folder this layer is in, or null at the top level. Folders are always top-level. */
        public String parent;

        public Node(String id, boolean folder, String name, String parent) {
            this.id = id;
            this.folder = folder;
            this.name = name;
            this.parent = parent;
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    private String active;
    public int seq = 0;

    public Layers() {
        active = addLayer("Layer 1", null).id;
    }

    public synchronized List<Node> all() {
        return(new ArrayList<>(nodes));
    }

    /** Rows in display order: each top-level node, folders followed by their layers. */
    public synchronized List<Node> rows() {
        List<Node> ret = new ArrayList<>();
        for(Node n : nodes) {
            if(n.parent != null)
                continue;
            ret.add(n);
            if(n.folder && n.open) {
                for(Node c : nodes)
                    if(n.id.equals(c.parent))
                        ret.add(c);
            }
        }
        return(ret);
    }

    public synchronized Node get(String id) {
        if(id == null)
            return(null);
        for(Node n : nodes)
            if(n.id.equals(id))
                return(n);
        return(null);
    }

    public synchronized String active() {
        if(get(active) == null) {
            Node first = nodes.stream().filter(n -> !n.folder).findFirst().orElse(null);
            active = (first != null) ? first.id : addLayer("Layer 1", null).id;
        }
        return(active);
    }

    public synchronized void activate(String id) {
        Node n = get(id);
        if(n != null && !n.folder) {
            active = id;
            seq++;
        }
    }

    public synchronized Node addLayer(String name, String folder) {
        Node n = new Node(UUID.randomUUID().toString(), false, name, folder);
        insert(n);
        return(n);
    }

    public synchronized Node addFolder(String name) {
        Node n = new Node(UUID.randomUUID().toString(), true, name, null);
        nodes.add(n);
        seq++;
        return(n);
    }

    /** Adds a node as loaded from a file; ids are kept. */
    public synchronized void restore(Node n) {
        nodes.add(n);
        seq++;
    }

    public synchronized void clear() {
        nodes.clear();
        active = null;
        seq++;
    }

    /** Puts a new layer after the last member of its folder (or at the end). */
    private void insert(Node n) {
        int at = nodes.size();
        if(n.parent != null) {
            for(int i = 0; i < nodes.size(); i++)
                if(nodes.get(i).id.equals(n.parent) || n.parent.equals(nodes.get(i).parent))
                    at = i + 1;
        }
        nodes.add(at, n);
        seq++;
    }

    /** Removes a node (a folder takes its layers with it). Returns the ids of the removed layers. */
    public synchronized Set<String> remove(String id) {
        Set<String> gone = new HashSet<>();
        for(Iterator<Node> i = nodes.iterator(); i.hasNext();) {
            Node n = i.next();
            if(n.id.equals(id) || id.equals(n.parent)) {
                if(!n.folder)
                    gone.add(n.id);
                i.remove();
            }
        }
        seq++;
        return(gone);
    }

    /** Moves a top-level node, or a layer within its folder, one step up (-1) or down (+1). */
    public synchronized void shift(String id, int dir) {
        Node n = get(id);
        if(n == null)
            return;
        List<Node> sibs = new ArrayList<>();
        for(Node s : nodes)
            if(Objects.equals(s.parent, n.parent))
                sibs.add(s);
        int i = sibs.indexOf(n), j = i + dir;
        if(j < 0 || j >= sibs.size())
            return;
        Collections.swap(nodes, nodes.indexOf(sibs.get(i)), nodes.indexOf(sibs.get(j)));
        seq++;
    }

    public synchronized void changed() {
        seq++;
    }

    /** Whether objects on this layer show: the layer and its folder must both be visible. */
    public synchronized boolean shown(String layer) {
        Node n = get(layer);
        if(n == null)
            return(true);
        Node f = get(n.parent);
        return(n.visible && (f == null || f.visible));
    }

    public synchronized boolean locked(String layer) {
        Node n = get(layer);
        if(n == null)
            return(false);
        Node f = get(n.parent);
        return(n.locked || (f != null && f.locked));
    }

    public synchronized String uniqueName(String base) {
        Set<String> taken = new HashSet<>();
        for(Node n : nodes)
            taken.add(n.name);
        if(!taken.contains(base))
            return(base);
        for(int i = 2; ; i++)
            if(!taken.contains(base + " " + i))
                return(base + " " + i);
    }
}
