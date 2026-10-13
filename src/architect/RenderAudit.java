package architect;

import architect.edit.*;
import architect.res.Catalogue;
import haven.*;

import java.util.*;

/**
 * Dev (-Darchitect.renderaudit=true, on a throwaway plan): places every catalogue object in a grid, waits for
 * the engine to build them, then lists the ones that draw nothing and exits.
 */
class RenderAudit {
    private final PlannerView view;
    private final List<PlanObject> placed = new ArrayList<>();
    private double ready = -1;

    RenderAudit(PlannerView view) {
        this.view = view;
    }

    void tick(Catalogue cat) {
        if(cat == null)
            return;
        String show = System.getProperty("architect.showcase");
        if(show != null) {
            /* res[:hexmask],... in a row, 3 tiles apart, for comparing looks */
            if(ready < 0) {
                int i = 0;
                for(String s : show.split(",")) {
                    String[] p = s.split(":");
                    byte[] sdt = new byte[0];
                    if(p.length > 1) {
                        long m = Long.parseLong(p[1], 16);
                        sdt = new byte[] {(byte)m, (byte)(m >> 8), (byte)(m >> 16), (byte)(m >> 24)};
                    }
                    PlanObject po = new PlanObject(UUID.randomUUID().toString(), p[0], sdt, view.cc.add(i++ * 33 - 60, 0), 0, null);
                    view.plan.add(po);
                    placed.add(po);
                }
                ready = Utils.rtime() + 20;
            } else if(ready > 0 && Utils.rtime() > ready) {
                ready = 0;
                for(PlanObject po : placed) {
                    String why;
                    try {
                        Resource r = Resource.remote().load(po.res).get();
                        Gob g = new Gob(view.glob, po.rc);
                        why = (Looks.drawable(g, r, po.sdt) == null) ? "broken" : "builds";
                    } catch(Loading l) {
                        why = "loading: " + l;
                    }
                    System.out.println("[showcase] " + po.res + " gob=" + (po.gob() != null) + " drawn=" + ((po.gob() == null) ? 0 : haven.render.ArchitectProbe.drawn(po.gob())) + " " + why);
                }
            }
            return;
        }
        if(ready < 0) {
            Set<String> res = new TreeSet<>();
            for(Catalogue.Entry e : cat.build)
                if(e.res != null) res.add(e.res);
            for(Catalogue.Entry e : cat.objects)
                if(!e.res.startsWith("gfx/terobjs/items/")) res.add(e.res);
            int side = (int)Math.ceil(Math.sqrt(res.size())), i = 0;
            Coord2d o = view.cc.sub(side * 16, side * 16);
            for(String r : res) {
                PlanObject p = new PlanObject(r, null, o.add((i % side) * 33 + 5.5, (i / side) * 33 + 5.5), 0);
                view.plan.add(p);
                placed.add(p);
                i++;
            }
            ready = Utils.rtime() + Double.parseDouble(System.getProperty("architect.renderaudit.wait", "60"));
            System.out.println("[audit] placed " + placed.size());
            return;
        }
        if(Utils.rtime() < ready)
            return;
        int zero = 0, nogob = 0;
        for(PlanObject p : placed) {
            Gob g = p.gob();
            if(g == null) {
                nogob++;
                System.out.println("[audit] NOGOB " + p.res);
                continue;
            }
            int n = haven.render.ArchitectProbe.drawn(g);
            if(n == 0) {
                zero++;
                Drawable d = g.getattr(Drawable.class);
                String spr = (d instanceof ResDrawable && ((ResDrawable)d).spr != null) ? ((ResDrawable)d).spr.getClass().getName() : String.valueOf(d);
                System.out.println("[audit] ZERO " + p.res + " " + spr);
            }
        }
        System.out.println("[audit] done: " + placed.size() + " placed, " + zero + " draw nothing, " + nogob + " never built");
        System.exit(0);
    }
}
