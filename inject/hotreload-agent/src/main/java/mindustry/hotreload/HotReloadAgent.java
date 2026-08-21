package mindustry.hotreload;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * HOTRELOAD: a dependency-free JVM agent that watches a directory of compiled .class files
 * and hot-swaps changed classes into the running JVM via Instrumentation.redefineClasses.
 *
 * Usage:
 *   -javaagent:hotreload-agent.jar
 *   -Dhotreload.dir=<class output dir>   (default: build/classes/java/main)
 *   -Dhotreload.poll=<ms>                (default: 800)
 *   -Dhotreload.debounce=<ms>            (default: 300)
 *
 * Pair with `./gradlew :core:compileJava --continuous` for a save-and-apply dev loop.
 * Logic changes (method bodies) apply instantly without restarting the game.
 */
public class HotReloadAgent{
    private static volatile Instrumentation inst;
    private static Path watchDir;
    private static long poll = 800;
    private static long debounce = 300;

    //class name -> latest bytes seen on disk. Served by the transformer for classes that are
    //loaded *after* a compile, and used for redefineClasses on classes already loaded.
    private static final ConcurrentMap<String, byte[]> pending = new ConcurrentHashMap<>();
    //class name -> last applied mtime, used as change baseline
    private static final ConcurrentMap<String, Long> applied = new ConcurrentHashMap<>();
    private static volatile boolean baselineRecorded;

    public static void premain(String args, Instrumentation instrumentation){
        init(instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation){
        init(instrumentation);
    }

    private static void init(Instrumentation instrumentation){
        if(inst != null) return;
        inst = instrumentation;
        watchDir = Path.of(System.getProperty("hotreload.dir", "build/classes/java/main"));
        poll = Long.getLong("hotreload.poll", 800);
        debounce = Long.getLong("hotreload.debounce", 300);

        inst.addTransformer(new java.lang.instrument.ClassFileTransformer(){
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer){
                if(className == null) return null;
                byte[] bytes = pending.get(className.replace('/', '.'));
                return bytes != null ? bytes : null;
            }
        }, false);

        Thread watcher = new Thread(HotReloadAgent::watchLoop, "hotreload-watcher");
        watcher.setDaemon(true);
        watcher.start();
        log("HotReload agent attached. Watching " + watchDir + " (poll=" + poll + "ms, debounce=" + debounce + "ms)");
    }

    private static void watchLoop(){
        while(true){
            try{
                if(Files.isDirectory(watchDir)) scan();
            }catch(Throwable e){
                log("watcher error: " + e);
            }
            try{
                Thread.sleep(poll);
            }catch(InterruptedException ignored){
            }
        }
    }

    private static void scan() throws Exception{
        List<String> changed = new ArrayList<>();
        try(var stream = Files.walk(watchDir)){
            for(Path p : (Iterable<Path>)stream::iterator){
                if(!p.toString().endsWith(".class")) continue;
                String name = classNameOf(p);
                if(name == null || name.contains("module-info") || name.contains("META-INF")) continue;

                long mtime = Files.getLastModifiedTime(p).toMillis();
                Long last = applied.get(name);
                if(last == null || last != mtime){
                    //first scan only records the baseline without touching the JVM
                    if(!baselineRecorded){
                        applied.put(name, mtime);
                        continue;
                    }
                    applied.put(name, mtime);
                    changed.add(name);
                }
            }
        }

        //the first scan only records the baseline; from then on, mtime changes are hot-swaps
        if(!baselineRecorded){
            baselineRecorded = true;
            return;
        }

        if(changed.isEmpty()) return;

        //debounce so a burst of writes (multi-class compile) is applied atomically-ish
        Thread.sleep(debounce);

        int ok = 0;
        for(String name : changed){
            try{
                byte[] bytes = Files.readAllBytes(classFileOf(name));
                pending.put(name, bytes);
                if(redefine(name, bytes)) ok++;
            }catch(Throwable e){
                log("skip " + name + ": " + e);
            }
        }
        if(ok > 0) log("hot-swapped " + ok + " class(es): " + String.join(", ", changed.subList(0, Math.min(changed.size(), 12))));
    }

    /** Applies new bytecode to a loaded class; no-ops if the class has not been loaded yet
     * (the transformer will serve the latest bytes when it loads). */
    private static boolean redefine(String name, byte[] bytes){
        if(inst == null) return false;
        try{
            for(Class<?> clazz : inst.getAllLoadedClasses()){
                if(clazz.getName().equals(name)){
                    inst.redefineClasses(new ClassDefinition(clazz, bytes));
                    return true;
                }
            }
        }catch(Throwable e){
            log("redefine failed " + name + ": " + e);
        }
        return false;
    }

    private static String classNameOf(Path classFile){
        Path rel = watchDir.relativize(classFile);
        String s = rel.toString().replace('\\', '/');
        if(!s.endsWith(".class")) return null;
        return s.substring(0, s.length() - ".class".length()).replace('/', '.');
    }

    private static Path classFileOf(String className){
        return watchDir.resolve(className.replace('.', '/') + ".class");
    }

    static synchronized void log(String msg){
        System.out.println("[hotreload] " + msg);
        try(var out = new PrintStream(new FileOutputStream("hotreload.log", true))){
            out.println("[hotreload] " + msg);
        }catch(Exception ignored){
        }
    }
}