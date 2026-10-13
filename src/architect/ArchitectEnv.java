package architect;

import haven.*;
import nurgling.NConfig;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.*;

/**
 * Process-wide setup that keeps the planner off the game's files.
 *
 * - Config.localdir is redirected to %APPDATA%\Nurgling Architect, so NConfig, ResCache.global and every
 *   nurgling data file (NUtils.getDataFile) live there, never in a game profile.
 * - Game art comes from the game's public resource server, downloaded the first time something needs it and
 *   kept in the planner's own cache (%APPDATA%\Nurgling Architect\data). Nothing else ever goes online:
 *   there is no game session, and the game's own cache is never touched.
 *
 * Must run before anything touches Config, ResCache or Resource.remote().
 */
public class ArchitectEnv {
    /** Where the game client downloads resources from (haven.resurl in the client's config). */
    public static final URI RESURL = URI.create(System.getProperty("architect.resurl", "https://game.havenandhearth.com/res/"));

    /** This build's version (VERSION, stamped into the jar), or "dev" when run from a build tree without it. */
    public static final String VERSION = version();

    public static Path home;
    /** The game's local folder, if this PC has the game: only your area presets are read from it. */
    public static Path gameRoot;
    /** The planner's own resource cache (downloaded game art). */
    public static ResCache cache;
    /** The planner's folder (the clone): app/, engine/, runtime/ live here. */
    public static Path root;

    public static void init() {
        /* Never touch the game's Java preferences (node "hafen"): run.bat passes this too, but tests and dev
         * commands might not, and the planner writes preferences (renderer, window size). */
        if(System.getProperty("haven.prefspec") == null)
            System.setProperty("haven.prefspec", "architect");
        String appdata = System.getenv("APPDATA");
        Path appbase = (appdata != null) ? Paths.get(appdata) : Paths.get(System.getProperty("user.home"));
        home = Paths.get(System.getProperty("architect.home", appbase.resolve("Nurgling Architect").toString()));
        gameRoot = appbase.resolve("Haven and Hearth");
        try {
            Files.createDirectories(home);
        } catch(java.io.IOException e) {
            throw(new RuntimeException("cannot create " + home, e));
        }
        setLocaldir(home);
        root = findroot();

        /* The client's own setup, pointed at the planner's folder: cache first, then the server. */
        cache = HashDirCache.get("architect");
        Resource.setcache(cache);
        Resource.addurl(RESURL);

        MainFrame.config = new NConfig();
        MainFrame.config.read();
        plannerDefaults();
        System.out.println("[architect] home=" + home + " root=" + root + " art from " + RESURL + " (cached in " + home.resolve("data") + ")");
    }

    /**
     * Game-client settings that are wrong for a planner, forced on every start. A planner must show
     * every object as itself (nurgling's "hide nature", on by default, turns trees and boulders into
     * markers) and take clicks on the map.
     */
    private static void plannerDefaults() {
        java.util.Map<String, Object> hide = new java.util.HashMap<>(nurgling.tools.GobHide.defaults());
        hide.put("enabled", false);
        NConfig.set(NConfig.Key.hideConf, hide);
        /* DRAG mode (the UI-layout drag menu) swallows every click on the map, camera drags included. */
        NConfig.set(NConfig.Key.show_drag_menu, false);
    }

    /** The planner's folder: the one holding app/ (or -Darchitect.root). */
    private static String version() {
        try(java.io.InputStream in = ArchitectEnv.class.getResourceAsStream("/architect/VERSION")) {
            return((in == null) ? "dev" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip());
        } catch(java.io.IOException e) {
            return("dev");
        }
    }

    private static Path findroot() {
        String r = System.getProperty("architect.root");
        if(r != null)
            return(Paths.get(r));
        try {
            Path jar = Paths.get(ArchitectEnv.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            /* app/architect.jar, or build/classes when run from the build tree: the folder above either */
            return(jar.getParent().getParent());
        } catch(java.net.URISyntaxException | SecurityException e) {
            return(Paths.get(".").toAbsolutePath());
        }
    }

    /** hafen-res.jar next to the engine's hafen.jar: bundled base resources and the lib/* code libraries. */
    public static Path resJar() {
        try {
            Path engine = Paths.get(Resource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return(engine.resolveSibling("hafen-res.jar"));
        } catch(java.net.URISyntaxException | SecurityException e) {
            return(null);
        }
    }

    private static void setLocaldir(Path dir) {
        try {
            Field ld = Config.class.getDeclaredField("localdir");
            Field has = Config.class.getDeclaredField("haslocaldir");
            ld.setAccessible(true);
            has.setAccessible(true);
            synchronized(Config.class) {
                ld.set(null, dir);
                has.setBoolean(null, true);
            }
        } catch(ReflectiveOperationException e) {
            throw(new RuntimeException("engine changed: cannot redirect Config.localdir", e));
        }
    }
}
