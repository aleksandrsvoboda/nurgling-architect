package architect.edit;

import haven.*;
import haven.render.*;

import java.awt.Color;
import java.util.*;

/** The selected plan objects, shown with an orange tint. Objects deleted from the plan drop out automatically. */
public class Selection {
    private final Plan plan;
    private final Set<String> ids = new LinkedHashSet<>();

    public Selection(Plan plan) {
        this.plan = plan;
    }

    /** Gob tint for selected objects. */
    public static class Tint extends GAttrib implements Gob.SetupMod {
        static final Pipe.Op state = new BaseColor(new Color(255, 178, 102, 255));

        public Tint(Gob gob) {
            super(gob);
        }

        public Pipe.Op gobstate() {
            return(state);
        }
    }

    public synchronized List<PlanObject> objects() {
        List<PlanObject> ret = new ArrayList<>();
        for(String id : ids) {
            PlanObject o = plan.get(id);
            if(o != null && plan.editable(o))
                ret.add(o);
        }
        return(ret);
    }

    public synchronized boolean contains(PlanObject o) {
        return(ids.contains(o.id));
    }

    public synchronized boolean isEmpty() {
        return(objects().isEmpty());
    }

    public synchronized int size() {
        return(objects().size());
    }

    public synchronized void set(Collection<PlanObject> objs) {
        ids.clear();
        for(PlanObject o : objs)
            ids.add(o.id);
    }

    public synchronized void add(PlanObject o) {
        ids.add(o.id);
    }

    public synchronized void toggle(PlanObject o) {
        if(!ids.remove(o.id))
            ids.add(o.id);
    }

    public synchronized void clear() {
        ids.clear();
    }

    /** Keeps gob tints in step with the selection; gobs come and go with undo, so this runs every tick. */
    public void tick() {
        Set<String> sel;
        synchronized(this) {
            sel = new HashSet<>(ids);
        }
        for(PlanObject o : plan.objects()) {
            Gob g = o.gob();
            if(g == null)
                continue;
            boolean want = sel.contains(o.id);
            boolean has = g.getattr(Tint.class) != null;
            if(want && !has)
                g.setattr(new Tint(g));
            else if(!want && has)
                g.delattr(Tint.class);
        }
    }
}
