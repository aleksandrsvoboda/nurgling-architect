package architect;

import haven.*;
import nurgling.NConfig;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.*;

/**
 * Process-wide setup that keeps the planner off the game's files and off the network.
 *
 * - Config.localdir is redirected to %APPDATA%\Nurgling Architect, so NConfig, ResCache.global
 *   and every nurgling data file (NUtils.getDataFile) live there, never in the game profile.
 * - Resources resolve from the bundled jars plus the game's cache opened read-only.
 *   No HttpSource is ever added (that only happens in Client.setupres, which we never call).
 *
 * Must run before anything touches Config, ResCache or Resource.remote().
 */
public class ArchitectEnv {
    public static final URI RES_CACHE_ID = URI.create("http://game.havenandhearth.com/render/");

    public static Path home;
    public static Path gameData;
    /** The game's local dir (parent of data/): your area presets live here. Read only. */
    public static Path gameRoot;
    public static ReadOnlyGameCache resCache;
    /** The planner's folder (the clone): pack/, engine/, runtime/ live here. */
    public static Path root;
    /** Game art shipped with the planner; empty when no pack was built. */
    public static architect.res.PackSource pack;

    public static void init() {
        String appdata = System.getenv("APPDATA");
        Path appbase = (appdata != null) ? Paths.get(appdata) : Paths.get(System.getProperty("user.home"));
        home = Paths.get(System.getProperty("architect.home", appbase.resolve("Nurgling Architect").toString()));
        gameData = Paths.get(System.getProperty("architect.gamedata", appbase.resolve("Haven and Hearth").resolve("data").toString()));
        gameRoot = gameData.getParent();
        try {
            Files.createDirectories(home);
        } catch(java.io.IOException e) {
            throw(new RuntimeException("cannot create " + home, e));
        }
        setLocaldir(home);

        root = findroot();
        /* The pack makes the planner work without the game; the game's cache, if this machine has
         * one, is read too (never written) for anything newer or missing from the pack. */
        if(Files.isDirectory(gameData)) {
            resCache = new ReadOnlyGameCache(gameData, RES_CACHE_ID);
            Resource.setcache(resCache);
        }
        try {
            pack = new architect.res.PackSource(root.resolve("pack"));
        } catch(java.io.IOException e) {
            throw(new RuntimeException("cannot open the planner's art pack in " + root.resolve("pack"), e));
        }
        if(!pack.empty())
            Resource.remote().add(pack);

        MainFrame.config = new NConfig();
        MainFrame.config.read();
        plannerDefaults();
        System.out.println("[architect] home=" + home + " root=" + root + " pack=" + pack + " gamecache=" + (resCache != null ? gameData : "none"));
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
