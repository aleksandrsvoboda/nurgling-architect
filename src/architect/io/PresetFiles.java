package architect.io;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * File handling shared by the game's preset folders (blueprints, area presets): file names, writes and deletes
 * done exactly the way nurgling does them, so the game sees what it would have written itself.
 */
public class PresetFiles {
    /** File name from a preset name, as the game makes it (AreaPresetStore.slug). */
    public static String slug(String name) {
        StringBuilder sb = new StringBuilder();
        for(char c : name.trim().toCharArray()) {
            if(Character.isLetterOrDigit(c) || c == '-' || c == '_')
                sb.append(c);
            else if(sb.length() > 0 && sb.charAt(sb.length() - 1) != '_')
                sb.append('_');
        }
        String s = sb.toString();
        while(s.endsWith("_"))
            s = s.substring(0, s.length() - 1);
        return(s.isEmpty() ? "preset" : s);
    }

    /** A free file name in dir for a preset name: slug.json, then slug-2.json, slug-3.json... */
    public static String freename(Path dir, String name) {
        String base = slug(name), file = base + ".json";
        for(int i = 2; Files.exists(dir.resolve(file)); i++)
            file = base + "-" + i + ".json";
        return(file);
    }

    /** As the game's NFileUtils.writeAtomically: write a .tmp, keep the old file as .bak, move the new one in. */
    public static void atomic(Path target, String text) throws IOException {
        if(target.getParent() != null)
            Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Path bak = target.resolveSibling(target.getFileName() + ".bak");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        if(Files.exists(target)) {
            try {
                Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
            } catch(IOException e) {
                /* as in the game: a missing backup does not stop the save */
                System.out.println("[presets] no backup of " + target + ": " + e.getMessage());
            }
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch(AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void delete(Path dir, String file) throws IOException {
        if(file == null)
            return;
        Files.deleteIfExists(dir.resolve(file));
        Files.deleteIfExists(dir.resolve(file + ".bak"));
    }

    /**
     * The JSON to rewrite a preset that was read from disk with only its name and description changed, so
     * whatever a newer game version stores in it survives.
     */
    public static JSONObject renamed(JSONObject raw, String name, String description) {
        JSONObject o = new JSONObject(raw.toString());
        o.put("name", name);
        if(description.isEmpty())
            o.remove("description");
        else
            o.put("description", description);
        return(o);
    }

    /** Newest file time in the folder plus its file count, to notice changes (the game saving one) cheaply. */
    public static long stamp(Path dir) {
        long t = 0, n = 0;
        if(!Files.isDirectory(dir))
            return(0);
        try(DirectoryStream<Path> fs = Files.newDirectoryStream(dir, "*.json")) {
            for(Path f : fs) {
                t = Math.max(t, Files.getLastModifiedTime(f).toMillis());
                n++;
            }
        } catch(IOException e) {
            return(-1);
        }
        return(t * 1000 + n);
    }
}
