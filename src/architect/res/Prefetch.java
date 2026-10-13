package architect.res;

import haven.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Downloads every resource the catalogue uses, and everything those need (following each resource's "deps"
 * layer), into the planner's cache so it works fully offline afterwards. Sounds are skipped: the planner is silent.
 * Resources already cached are not downloaded again.
 */
public class Prefetch {
    private final Catalogue cat;
    private final ResCache cache;
    private final Consumer<String> progress;
    public final AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
    private volatile boolean cancel = false;

    public Prefetch(Catalogue cat, ResCache cache, Consumer<String> progress) {
        this.cat = cat;
        this.cache = cache;
        this.progress = progress;
    }

    public void cancel() {
        cancel = true;
    }

    private static boolean wanted(String name) {
        return(!name.startsWith("sfx/"));
    }

    /** Runs to completion (or cancellation); returns the number of resources now available offline. */
    public int run() throws InterruptedException {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        BlockingQueue<String> todo = new LinkedBlockingQueue<>();
        for(String n : cat.resources())
            if(seen.add(n))
                todo.add(n);
        AtomicInteger busy = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            while(!cancel) {
                String n = todo.poll(200, TimeUnit.MILLISECONDS);
                if(n == null) {
                    if(busy.get() == 0 && todo.isEmpty())
                        break;
                    continue;
                }
                busy.incrementAndGet();
                pool.submit(() -> {
                    try {
                        /* Low priority: whatever the planner needs right now is loaded first. */
                        Loading.waitfor(Resource.remote().load(n, -1, -10));
                        try(InputStream in = cache.fetch("res/" + n)) {
                            ResFile f = ResFile.parse(in.readAllBytes());
                            if(f != null)
                                for(String d : f.deps())
                                    if(wanted(d) && seen.add(d))
                                        todo.add(d);
                        } catch(IOException e) {
                            /* bundled resources are not in the cache; they need nothing downloaded */
                        }
                        done.incrementAndGet();
                    } catch(Resource.LoadException | Resource.BadResourceException e) {
                        failed.incrementAndGet();
                    } finally {
                        busy.decrementAndGet();
                        progress.accept(String.format("Downloading game art for offline use... %d of %d", done.get() + failed.get(), seen.size()));
                    }
                });
            }
        } finally {
            pool.shutdownNow();
        }
        return(done.get());
    }
}
