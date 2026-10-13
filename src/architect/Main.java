package architect;

import architect.io.*;
import architect.world.WorldSource;
import haven.*;
import haven.iosys.tk.Toolkit;

import java.nio.file.*;

/**
 * Nurgling Architect entry point. Deliberately not haven.MainFrame: no login, no plugins, no network.
 * Starts at the {@link StartScreen}, or straight into a plan with --plan FILE.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        ArchitectEnv.init();
        UI.Runner first = new StartScreen();
        for(int i = 0; i < args.length - 1; i++) {
            if(args[i].equals("--plan")) {
                Path f = Paths.get(args[i + 1]);
                PlanFile.Data d = PlanFile.load(f);
                WorldSource w = WorldRef.open(d.world);
                if(w != null)
                    first = new PlannerRunner(w, f, d.name, d);
            }
        }
        Client cl = new Client(Toolkit.instance());
        cl.wnd.title("Nurgling Architect");
        try {
            cl.run(first);
        } finally {
            cl.dispose();
        }
        System.exit(0);
    }
}
