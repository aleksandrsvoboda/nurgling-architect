package architect;

import architect.io.*;
import architect.ui.*;
import architect.world.*;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.*;

/**
 * First screen: reopen a plan, or start one on a generated world or a flat sandbox.
 * Hands over to a {@link PlannerRunner}; closing a plan comes back here.
 */
public class StartScreen implements UI.Runner {
    private volatile UI.Runner chosen = null;
    private volatile String error = null;
    /* "Download all game art": one at a time, kept across screen rebuilds. */
    private static volatile architect.res.Prefetch prefetch = null;
    private static volatile String prefetchmsg = null;

    public String title() {
        return("Nurgling Architect");
    }

    public static Path plansdir() {
        return(ArchitectEnv.home.resolve("plans"));
    }

    static class PlanEntry {
        final Path file;
        final String name, info;

        PlanEntry(Path file, String name, String info) {
            this.file = file;
            this.name = name;
            this.info = info;
        }
    }

    static List<PlanEntry> plans() {
        List<PlanEntry> ret = new ArrayList<>();
        if(!Files.isDirectory(plansdir()))
            return(ret);
        try(Stream<Path> s = Files.list(plansdir())) {
            List<Path> files = s.filter(p -> p.toString().endsWith(PlanFile.EXT)).collect(Collectors.toList());
            files.sort(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed());
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            for(Path p : files) {
                String n = p.getFileName().toString();
                ret.add(new PlanEntry(p, n.substring(0, n.length() - PlanFile.EXT.length()),
                                      "saved " + df.format(new Date(p.toFile().lastModified()))));
            }
        } catch(IOException e) {
            new Warning(e, "cannot list plans").issue();
        }
        return(ret);
    }

    class Screen extends Widget {
        final Text.Foundry big = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 22).aa(true);
        final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 14).aa(true);
        final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
        final Text.Foundry small = new Text.Foundry(Text.sans, 10).aa(true);
        final Color DIM = new Color(143, 163, 164);
        final List<PlanEntry> plans = plans();
        final Listbox<PlanEntry> planlist;
        final GenPanel gen;
        final TextEntry name;
        final Button genb, sandb, smaller, bigger, create;
        Button dlb, delb;
        /* Delete asks for a second click on the same plan. */
        PlanEntry deleting = null;
        boolean sandbox = false;
        int size = 10;
        final Map<Object, Tex> tex = new HashMap<>();
        static final int LEFT = 380;

        Screen(Coord sz) {
            super(sz);
            int x0 = UI.scale(24), y0 = UI.scale(90), lw = UI.scale(LEFT - 48);
            planlist = add(new Listbox<PlanEntry>(lw, 12, UI.scale(36)) {
                protected PlanEntry listitem(int i) {return(plans.get(i));}
                protected int listitems() {return(plans.size());}
                protected void drawbg(GOut g) {g.chcolor(NStyle.windowBg); g.frect(Coord.z, sz); g.chcolor();}
                protected void drawitem(GOut g, PlanEntry e, int i) {
                    g.image(text(e.name, fnd, Color.WHITE), Coord.of(UI.scale(6), UI.scale(3)));
                    g.image(text(e.info, small, DIM), Coord.of(UI.scale(6), UI.scale(20)));
                }
            }, x0, y0);
            int hw = (lw - UI.scale(6)) / 2;
            Button open = add(new Button(hw, "Open plan", false, this::openplan), x0, planlist.c.y + planlist.sz.y + UI.scale(8));
            delb = add(new Button(hw, "Delete plan", false, this::deleteplan), x0 + hw + UI.scale(6), open.c.y);
            dlb = add(new Button(lw, "Download all game art (offline use)", false, this::download), x0, open.c.y + open.sz.y + UI.scale(24));

            int px = UI.scale(LEFT);
            genb = add(new Button(UI.scale(200), "Generate a world", false, () -> sandbox = false), px, y0);
            sandb = add(new Button(UI.scale(200), "Flat sandbox", false, () -> sandbox = true), px + UI.scale(206), y0);
            int gy = y0 + genb.sz.y + UI.scale(12);
            gen = add(new GenPanel(Coord.of(sz.x - px - UI.scale(24), sz.y - gy - UI.scale(70))), px, gy);
            int by = sz.y - UI.scale(52);
            name = add(new TextEntry(UI.scale(260), "My base"), px, by + UI.scale(4));
            smaller = add(new Button(UI.scale(30), "-", false, () -> size(-2)), px + UI.scale(270), by);
            bigger = add(new Button(UI.scale(30), "+", false, () -> size(2)), px + UI.scale(400), by);
            create = add(new Button(UI.scale(200), "Create plan", false, this::createplan), sz.x - UI.scale(224), by);
        }

        Tex text(String s, Text.Foundry f, Color c) {
            String key = s + "\u0001" + f.hashCode() + "\u0001" + c.getRGB();
            Tex t = tex.get(key);
            if(t == null)
                tex.put(key, t = f.render(s, c).tex());
            return(t);
        }

        void size(int d) {
            size = Utils.clip(size + d, 4, 20);
            gen.size = size;
        }

        /* Game art normally downloads the first time each object is used; this gets all of it now. */
        void download() {
            if(prefetch != null)
                return;
            architect.res.Catalogue cat;
            try {
                cat = architect.res.Catalogue.shipped();
            } catch(IOException e) {
                error = e.getMessage();
                return;
            }
            architect.res.Prefetch p = new architect.res.Prefetch(cat, ArchitectEnv.cache, m -> prefetchmsg = m);
            prefetch = p;
            prefetchmsg = "Starting download...";
            Thread t = new HackThread(() -> {
                try {
                    int n = p.run();
                    prefetchmsg = (p.failed.get() == 0) ? String.format("All game art downloaded (%d files). The planner now works offline.", n)
                        : String.format("Downloaded %d files; %d could not be fetched (offline, or gone from the server).", n, p.failed.get());
                } catch(InterruptedException e) {
                    prefetchmsg = "Download stopped.";
                } finally {
                    prefetch = null;
                }
            }, "Architect prefetch");
            t.setDaemon(true);
            t.start();
        }

        /** Moves the selected plan (and its terrain and backups) to plans\deleted, so it can be recovered. */
        void deleteplan() {
            PlanEntry e = planlist.sel;
            if(e == null) {
                error = "Choose a plan to delete";
                return;
            }
            if(deleting != e) {
                deleting = e;
                delb.change("Click again to delete");
                error = "Click Delete again to delete \"" + e.name + "\" (it goes to plans\\deleted)";
                return;
            }
            Path bin = plansdir().resolve("deleted");
            try {
                Files.createDirectories(bin);
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                try(Stream<Path> s = Files.list(plansdir())) {
                    for(Path p : s.collect(Collectors.toList())) {
                        String n = p.getFileName().toString();
                        if(Files.isRegularFile(p) && (n.equals(e.name + PlanFile.EXT) || n.startsWith(e.name + PlanFile.EXT + ".")
                                                      || n.equals(e.name + architect.world.TerrainStore.EXT)))
                            Files.move(p, bin.resolve(stamp + " " + n));
                    }
                }
                plans.remove(e);
                planlist.change(null);
                error = "Deleted \"" + e.name + "\" (kept in plans\\deleted)";
            } catch(IOException ex) {
                error = "Could not delete " + e.name + ": " + ex.getMessage();
            }
            deleting = null;
            delb.change("Delete plan");
        }

        void openplan() {
            PlanEntry e = planlist.sel;
            if(e == null) {
                error = "Choose a plan to open";
                return;
            }
            try {
                PlanFile.Data d = PlanFile.load(e.file);
                WorldSource w = WorldRef.open(d.world);
                if(w == null) {
                    error = "\"" + e.name + "\" was made on a saved game map, which is no longer supported";
                    return;
                }
                chosen = new PlannerRunner(w, e.file, d.name, d);
            } catch(IOException ex) {
                error = "Could not open " + e.name + ": " + ex.getMessage();
            }
            synchronized(StartScreen.this) {
                StartScreen.this.notifyAll();
            }
        }

        void createplan() {
            String n = name.text().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            if(n.isEmpty()) {
                error = "Give the plan a name";
                return;
            }
            Path file = plansdir().resolve(n + PlanFile.EXT);
            if(Files.exists(file)) {
                error = "A plan called \"" + n + "\" already exists";
                return;
            }
            WorldSource w = sandbox ? new SandboxWorld(size, size, "gfx/tiles/grass") : gen.make();
            chosen = new PlannerRunner(w, file, n, null, !sandbox && gen.trees);
            synchronized(StartScreen.this) {
                StartScreen.this.notifyAll();
            }
        }

        public void tick(double dt) {
            if(deleting != null && planlist.sel != deleting) {
                deleting = null;
                delb.change("Delete plan");
            }
            if(parent != null && !sz.equals(parent.sz)) {
                /* Rebuilt on resize: simplest way to lay everything out again. */
                Widget p = parent;
                destroy();
                p.add(new Screen(p.sz), Coord.z);
                return;
            }
            if(gen.visible == sandbox)
                gen.show(!sandbox);
            super.tick(dt);
        }

        public void draw(GOut g) {
            g.chcolor(NStyle.infoBg);
            g.frect(Coord.z, sz);
            g.chcolor();
            g.image(text("Nurgling Architect", big, NStyle.border), Coord.of(UI.scale(24), UI.scale(18)));
            g.image(text("Base planner. Plan on a generated world or a flat sandbox. No game install or login needed.", fnd, DIM),
                    Coord.of(UI.scale(24), UI.scale(50)));
            g.image(text("Your plans", head, Color.WHITE), planlist.c.sub(0, UI.scale(22)));
            g.image(text("New plan", head, Color.WHITE), genb.c.sub(0, UI.scale(22)));
            if(sandbox)
                g.image(text("A flat grass world to plan on. Shape it with the Terrain tools.", fnd, Color.WHITE), gen.c.add(0, UI.scale(8)));
            g.image(text(String.format("%d × %d grids", size, size), fnd, Color.WHITE), smaller.c.add(UI.scale(40), UI.scale(4)));
            if(plans.isEmpty())
                g.image(text("No plans yet", fnd, DIM), planlist.c.add(UI.scale(8), UI.scale(8)));
            g.image(text("Game art downloads from the game's server the first time it's used.", small, DIM), dlb.c.sub(0, UI.scale(16)));
            String pm = prefetchmsg;
            if(pm != null)
                g.image(text(pm, small, Color.WHITE), dlb.c.add(0, dlb.sz.y + UI.scale(4)));
            String err = error;
            if(err != null)
                g.image(text(err, fnd, new Color(255, 120, 100)), Coord.of(UI.scale(24), sz.y - UI.scale(28)));
            super.draw(g);
            Button on = sandbox ? sandb : genb;
            g.chcolor(NStyle.border);
            g.rect(on.c, on.sz);
            g.chcolor();
        }
    }

    public UI.Runner run(UI ui) throws InterruptedException {
        synchronized(ui) {
            ui.core.mode = nurgling.NCore.Mode.IDLE;
            ui.root.add(new Screen(ui.root.sz), Coord.z);
        }
        synchronized(this) {
            while(chosen == null)
                wait();
        }
        return(chosen);
    }
}
