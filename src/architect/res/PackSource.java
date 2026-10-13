package architect.res;

import haven.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;

/**
 * Game art shipped with the planner: pack/*.jar in the planner's folder, entries "res/<name>.res".
 * Lets the planner run on a machine without the game. Built by {@code DevTools pack} from a game cache.
 */
public class PackSource implements Resource.ResSource {
    public static final String PREFIX = "res/";
    public final List<JarFile> jars = new ArrayList<>();

    public PackSource(Path dir) throws IOException {
        if(!Files.isDirectory(dir))
            return;
        try(Stream<Path> s = Files.list(dir)) {
            for(Path p : s.filter(f -> f.toString().endsWith(".jar")).sorted().collect(Collectors.toList()))
                jars.add(new JarFile(p.toFile()));
        }
    }

    public boolean empty() {
        return(jars.isEmpty());
    }

    public InputStream get(String name) throws IOException {
        String ent = PREFIX + name + ".res";
        for(JarFile j : jars) {
            JarEntry e = j.getJarEntry(ent);
            if(e != null)
                return(j.getInputStream(e));
        }
        throw(new FileNotFoundException("not in the planner's pack: " + name));
    }

    /** Every resource name in the pack. */
    public List<String> names() {
        List<String> ret = new ArrayList<>();
        for(JarFile j : jars)
            for(JarEntry e : Collections.list(j.entries()))
                if(e.getName().startsWith(PREFIX) && e.getName().endsWith(".res"))
                    ret.add(e.getName().substring(PREFIX.length(), e.getName().length() - 4));
        return(ret);
    }

    public byte[] read(String name) throws IOException {
        try(InputStream in = get(name)) {
            return(in.readAllBytes());
        }
    }

    public String toString() {
        return("planner pack (" + jars.size() + " jars)");
    }
}
