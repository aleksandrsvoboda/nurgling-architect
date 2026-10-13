package architect.edit;

import haven.*;

import java.util.UUID;

/** One planned object: what it is, where, and at which angle. Positions are world units in the planner's world. */
public class PlanObject {
    public final String id;
    public final String res;
    public final byte[] sdt;
    public Coord2d rc;
    public double a;
    /** Layer id (see Layers); assigned the active layer when added without one. */
    public String layer;
    /** The gob showing this object in the view, once created. */
    Gob gob;

    public PlanObject(String id, String res, byte[] sdt, Coord2d rc, double a, String layer) {
        this.id = id;
        this.layer = layer;
        this.res = res;
        this.sdt = (sdt == null) ? new byte[0] : sdt;
        this.rc = rc;
        this.a = a;
    }

    public PlanObject(String res, byte[] sdt, Coord2d rc, double a) {
        this(UUID.randomUUID().toString(), res, sdt, rc, a, null);
    }

    public Gob gob() {
        return(gob);
    }
}
