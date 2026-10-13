package architect.edit;

import haven.*;

import java.util.*;

/**
 * The objects being planned and their gobs in the view. All mutations go through here so the view, undo/redo
 * and saving see one consistent list.
 *
 * Every mutation is recorded as a before/after {@link State} per object. Mutations between {@link #begin} and
 * {@link #commit} form one undo step; a mutation outside them is a step of its own.
 */
public class Plan {
    public final Glob glob;
    public final Layers layers = new Layers();
    private int layerseq = -1;
    private final Map<String, PlanObject> objs = new LinkedHashMap<>();
    private final Map<Gob, PlanObject> bygob = new HashMap<>();
    public int seq = 0;

    /** An object's full description at one moment; null in a Change means "does not exist". */
    public static final class State {
        public final String res;
        public final byte[] sdt;
        public final Coord2d rc;
        public final double a;
        public final String layer;

        public State(String res, byte[] sdt, Coord2d rc, double a, String layer) {
            this.res = res; this.sdt = sdt; this.rc = rc; this.a = a; this.layer = layer;
        }

        static State of(PlanObject o) {
            return(new State(o.res, o.sdt, o.rc, o.a, o.layer));
        }
    }

    private static final class Change {
        final String id;
        final State before;
        State after;

        Change(String id, State before, State after) {
            this.id = id; this.before = before; this.after = after;
        }
    }

    public static final class Edit {
        public final String name;
        final LinkedHashMap<String, Change> changes = new LinkedHashMap<>();
        /** Changes kept outside the plan (terrain): {undo, redo}, applied in order. */
        final List<Runnable[]> custom = new ArrayList<>();
        /** Area id -> {before, after}; null = did not exist. */
        final LinkedHashMap<String, PlanArea[]> areas = new LinkedHashMap<>();

        Edit(String name) {
            this.name = name;
        }
    }

    private final Deque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private Edit cur = null;
    private int depth = 0;
    public static final int MAXUNDO = 500;

    public Plan(Glob glob) {
        this.glob = glob;
    }

    public synchronized Collection<PlanObject> objects() {
        return(new ArrayList<>(objs.values()));
    }

    public synchronized int size() {
        return(objs.size());
    }

    public synchronized PlanObject get(String id) {
        return(objs.get(id));
    }

    public synchronized PlanObject of(Gob gob) {
        return(bygob.get(gob));
    }

    /* ---------- undo grouping ---------- */

    public synchronized void begin(String name) {
        if(depth++ == 0)
            cur = new Edit(name);
    }

    public synchronized void commit() {
        if(depth == 0)
            throw(new IllegalStateException("commit without begin"));
        if(--depth > 0)
            return;
        Edit e = cur;
        cur = null;
        e.changes.values().removeIf(c -> same(c.before, c.after));
        e.areas.values().removeIf(c -> (c[0] == null) ? (c[1] == null) : c[0].same(c[1]));
        if(e.changes.isEmpty() && e.areas.isEmpty() && e.custom.isEmpty())
            return;
        undo.push(e);
        while(undo.size() > MAXUNDO)
            undo.removeLast();
        redo.clear();
    }

    private static boolean same(State a, State b) {
        if(a == null || b == null)
            return(a == b);
        return(a.res.equals(b.res) && Arrays.equals(a.sdt, b.sdt) && a.rc.equals(b.rc) && a.a == b.a
               && Objects.equals(a.layer, b.layer));
    }

    private void record(String id, State before, State after) {
        Edit e = cur;
        if(e == null)
            throw(new IllegalStateException());
        Change c = e.changes.get(id);
        if(c == null)
            e.changes.put(id, new Change(id, before, after));
        else
            c.after = after;
    }

    public synchronized String undoname() {
        return(undo.isEmpty() ? null : undo.peek().name);
    }

    public synchronized String redoname() {
        return(redo.isEmpty() ? null : redo.peek().name);
    }

    public boolean undo() {
        Edit e;
        synchronized(this) {
            if(undo.isEmpty() || depth > 0)
                return(false);
            e = undo.pop();
            redo.push(e);
        }
        List<Change> cs = new ArrayList<>(e.changes.values());
        Collections.reverse(cs);
        for(Change c : cs)
            apply(c.id, c.before);
        for(PlanArea[] c : e.areas.values())
            applyarea((c[0] != null) ? c[0].id : c[1].id, c[0]);
        for(int i = e.custom.size() - 1; i >= 0; i--)
            e.custom.get(i)[0].run();
        return(true);
    }

    public boolean redo() {
        Edit e;
        synchronized(this) {
            if(redo.isEmpty() || depth > 0)
                return(false);
            e = redo.pop();
            undo.push(e);
        }
        for(Change c : e.changes.values())
            apply(c.id, c.after);
        for(PlanArea[] c : e.areas.values())
            applyarea((c[0] != null) ? c[0].id : c[1].id, c[1]);
        for(Runnable[] c : e.custom)
            c[1].run();
        return(true);
    }

    /** Records a change made outside the plan (e.g. to the terrain) as part of the current undo step. */
    public void custom(String name, Runnable undo, Runnable redo) {
        begin(name);
        try {
            synchronized(this) {
                cur.custom.add(new Runnable[] {undo, redo});
            }
        } finally {
            commit();
        }
    }

    /* ---------- areas ---------- */

    private final Map<String, PlanArea> areas = new LinkedHashMap<>();
    private final Map<String, MCache.Overlay> overlays = new HashMap<>();
    private final Map<String, PlanArea> shownarea = new HashMap<>();
    public int areaseq = 0;

    public synchronized Collection<PlanArea> areas() {
        return(new ArrayList<>(areas.values()));
    }

    public synchronized PlanArea area(String id) {
        return(areas.get(id));
    }

    /** Adds or replaces an area (same id), undoably. */
    public void putarea(PlanArea a) {
        begin("Area");
        try {
            synchronized(this) {
                if(a.layer == null || layers.get(a.layer) == null)
                    a = a.with(a.name, a.color, a.tiles, layers.active());
                PlanArea before = areas.put(a.id, a);
                recordarea(a.id, before, a);
                areaseq++;
            }
        } finally {
            commit();
        }
    }

    public void removearea(String id) {
        begin("Delete area");
        try {
            synchronized(this) {
                PlanArea before = areas.remove(id);
                if(before == null)
                    return;
                recordarea(id, before, null);
                areaseq++;
            }
        } finally {
            commit();
        }
    }

    private void recordarea(String id, PlanArea before, PlanArea after) {
        PlanArea[] c = cur.areas.get(id);
        if(c == null)
            cur.areas.put(id, new PlanArea[] {before, after});
        else
            c[1] = after;
    }

    private synchronized void applyarea(String id, PlanArea a) {
        if(a == null)
            areas.remove(id);
        else
            areas.put(id, a);
        areaseq++;
    }

    /** Loads areas from a file without undo steps. */
    public synchronized void loadareas(Collection<PlanArea> list) {
        for(PlanArea a : list)
            areas.put(a.id, (layers.get(a.layer) != null) ? a : a.with(a.name, a.color, a.tiles, layers.active()));
        areaseq++;
    }

    /** The topmost visible area containing tile tc, or null. */
    public synchronized PlanArea areaat(Coord tc) {
        PlanArea ret = null;
        for(PlanArea a : areas.values())
            if(layers.shown(a.layer) && a.tiles.contains(tc))
                ret = a;
        return(ret);
    }

    private static final Map<Integer, MCache.OverlayInfo> olinfo = new HashMap<>();
    /** Ground overlay in the area's colour: a translucent fill and a stronger outline. */
    private static MCache.OverlayInfo olinfo(java.awt.Color c) {
        synchronized(olinfo) {
            return(olinfo.computeIfAbsent(c.getRGB(), k -> new MCache.OverlayInfo() {
                final Material fill = new Material(new haven.render.BaseColor(c.getRed(), c.getGreen(), c.getBlue(), Math.max(c.getAlpha(), 70)), haven.render.States.maskdepth);
                final Material edge = new Material(new haven.render.BaseColor(c.getRed(), c.getGreen(), c.getBlue(), 230), haven.render.States.maskdepth);
                public Collection<String> tags() {return(Collections.singletonList("show"));}
                public Material mat() {return(fill);}
                public Material omat() {return(edge);}
            }));
        }
    }

    private int olseq = -1, ollseq = -1;

    @SuppressWarnings("deprecation")
    private void syncoverlays() {
        if(olseq == areaseq && ollseq == layers.seq)
            return;
        olseq = areaseq;
        ollseq = layers.seq;
        Map<String, PlanArea> want = new HashMap<>();
        for(PlanArea a : areas())
            if(layers.shown(a.layer))
                want.put(a.id, a);
        for(Iterator<Map.Entry<String, MCache.Overlay>> i = overlays.entrySet().iterator(); i.hasNext();) {
            Map.Entry<String, MCache.Overlay> e = i.next();
            PlanArea a = want.get(e.getKey()), was = shownarea.get(e.getKey());
            if(a == null || was == null || !a.color.equals(was.color)) {
                e.getValue().destroy();
                i.remove();
                shownarea.remove(e.getKey());
            }
        }
        for(PlanArea a : want.values()) {
            MCache.Overlay ol = overlays.get(a.id);
            if(ol == null) {
                overlays.put(a.id, glob.map.new Overlay(a.tiles, olinfo(a.color)));
            } else {
                ol.update(a.tiles);
            }
            shownarea.put(a.id, a);
        }
    }

    /* ---------- mutations ---------- */

    public void add(PlanObject o) {
        begin("Place");
        try {
            synchronized(this) {
                if(o.layer == null || layers.get(o.layer) == null)
                    o.layer = layers.active();
                objs.put(o.id, o);
                record(o.id, null, State.of(o));
                seq++;
            }
            show(o);
        } finally {
            commit();
        }
    }

    public void remove(PlanObject o) {
        begin("Delete");
        try {
            Gob g;
            synchronized(this) {
                if(objs.remove(o.id) == null)
                    return;
                record(o.id, State.of(o), null);
                g = unlink(o);
                seq++;
            }
            if(g != null)
                glob.oc.remove(g);
        } finally {
            commit();
        }
    }

    public void move(PlanObject o, Coord2d rc, double a) {
        begin("Move");
        try {
            synchronized(this) {
                if(objs.get(o.id) != o)
                    return;
                State before = State.of(o);
                o.rc = rc;
                o.a = a;
                record(o.id, before, State.of(o));
                seq++;
            }
            Gob g = o.gob;
            if(g != null)
                g.move(o.rc, o.a);
        } finally {
            commit();
        }
    }

    /** Fills an empty plan from a file: no undo steps, objects on unknown layers go to the active one. */
    public void load(Collection<PlanObject> list) {
        synchronized(this) {
            for(PlanObject o : list) {
                if(layers.get(o.layer) == null)
                    o.layer = layers.active();
                objs.put(o.id, o);
            }
            seq++;
        }
        for(PlanObject o : list)
            show(o);
    }

    /** Moves an object to another layer (undoable); it disappears if that layer is hidden. */
    public void setlayer(PlanObject o, String layer) {
        begin("Move to layer");
        try {
            synchronized(this) {
                if(objs.get(o.id) != o || layers.get(layer) == null)
                    return;
                State before = State.of(o);
                o.layer = layer;
                record(o.id, before, State.of(o));
                seq++;
            }
            layerseq = -1;
        } finally {
            commit();
        }
    }

    /** Removes a layer or folder and, as one undoable step, every object on it. */
    public void removelayer(String id) {
        Set<String> gone = layers.remove(id);
        begin("Delete layer");
        try {
            for(PlanObject o : objects())
                if(gone.contains(o.layer))
                    remove(o);
            for(PlanArea a : areas())
                if(gone.contains(a.layer))
                    removearea(a.id);
        } finally {
            commit();
        }
    }

    /** Whether the object is selectable: on a visible, unlocked layer. */
    public boolean editable(PlanObject o) {
        return(layers.shown(o.layer) && !layers.locked(o.layer));
    }

    /** Shows/hides gobs to match layer visibility; cheap when nothing changed. Call every tick. */
    public void tick() {
        syncoverlays();
        if(layers.seq == layerseq)
            return;
        layerseq = layers.seq;
        for(PlanObject o : objects()) {
            boolean want = layers.shown(o.layer);
            if(want && o.gob == null) {
                show(o);
            } else if(!want && o.gob != null) {
                Gob g;
                synchronized(this) {
                    g = unlink(o);
                }
                if(g != null)
                    glob.oc.remove(g);
            }
        }
    }

    /** Undo/redo: make object id look like s (or not exist). Not recorded. */
    private void apply(String id, State s) {
        PlanObject o;
        Gob drop = null;
        boolean create = false;
        synchronized(this) {
            o = objs.get(id);
            if(s == null) {
                if(o != null) {
                    objs.remove(id);
                    drop = unlink(o);
                }
            } else if(o == null || !o.res.equals(s.res) || !Arrays.equals(o.sdt, s.sdt)) {
                if(o != null) {
                    objs.remove(id);
                    drop = unlink(o);
                }
                o = new PlanObject(id, s.res, s.sdt, s.rc, s.a, (layers.get(s.layer) != null) ? s.layer : layers.active());
                objs.put(id, o);
                create = true;
            } else {
                o.rc = s.rc;
                o.a = s.a;
                if(!Objects.equals(o.layer, s.layer)) {
                    o.layer = (layers.get(s.layer) != null) ? s.layer : layers.active();
                    layerseq = -1;
                }
            }
            seq++;
        }
        if(drop != null)
            glob.oc.remove(drop);
        if(create)
            show(o);
        else if(s != null && o.gob != null)
            o.gob.move(o.rc, o.a);
    }

    private Gob unlink(PlanObject o) {
        Gob g = o.gob;
        o.gob = null;
        if(g != null)
            bygob.remove(g);
        return(g);
    }

    /* Sprites throw Loading until their textures resolve, so gobs are built on the loader, which retries. */
    private void show(PlanObject o) {
        Indir<Resource> res = Resource.remote().load(o.res);
        glob.loader.defer(() -> {
            synchronized(Plan.this) {
                if(objs.get(o.id) != o || o.gob != null || !layers.shown(o.layer))
                    return;
            }
            /* Unique (virtual) ids: OCache keys objects by id; with a shared id it ticks and removes only one. */
            Gob g = glob.oc.new Virtual(o.rc, o.a);
            ResDrawable d = Looks.drawable(g, res.get(), o.sdt);
            if(d == null)
                return;
            g.setattr(d);
            g.move(o.rc, o.a);
            synchronized(Plan.this) {
                if(objs.get(o.id) != o)
                    return;
                o.gob = g;
                bygob.put(g, o);
            }
            glob.oc.add(g);
        }, null);
    }
}
