package mindustry.mod;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;

/**
 * HOTRELOAD: watches one or more mod directories under {@code <data>/mods} and hot-reloads
 * them in-game without a restart.
 *
 * Which directories are watched is controlled by {@code -Doverlay.dirs=name1,name2,...}
 * (default {@code overlay}). Each entry is a directory mod under the game's mods folder,
 * e.g. the bundled {@code overlay/} project, or any other directory mod you drop in.
 * While the game runs:
 *   - edit {@code <dir>/content/*.json|hjson} to add items/blocks/units...;
 *   - edit {@code <dir>/src/**} Java code, recompile, and copy the new classes in;
 *   - the game detects the change and reloads the mod: old content is disposed, a fresh
 *     classloader is created, content is re-initialized and new sprites are packed.
 *
 * All of the standard mod shapes work here: directory mods, jar/zip mods dropped into
 * {@code <data>/mods}, script mods (a {@code scripts/main.js} in the mod root), and
 * service plugins (a main class extending {@code mindustry.plugin.Plugin}). Content files
 * and code in any watched directory reload live; classic jar mods and scripts are loaded
 * at startup like in stock Mindustry.
 *
 * Trigger a manual reload any time (e.g. from the in-game JS console):
 *   {@code OverlayMods.reload();}
 *
 * Disable the watcher with {@code -Doverlay.auto=false}.
 */
public class OverlayMods{
    /** First watched directory name (default "overlay"). */
    public static final String overlayName = System.getProperty("overlay.dirs", "overlay").split(",")[0].trim();
    private static final long pollInterval = 500;
    private static final long debounce = 600;

    private static Thread watcher;
    private static volatile long lastReloadTime;
    private static volatile long changeSeenAt;
    private static volatile boolean reloading;

    /** Finds the first watched mod directory, if present. */
    public static Fi overlayDir(){
        return Vars.modDirectory != null && Vars.modDirectory.child(overlayName).exists() ? Vars.modDirectory.child(overlayName) : null;
    }

    /** Starts the file watcher. Called from {@code Mods.load()}. No-op when disabled or already running. */
    public static void init(){
        if(watcher != null) return;
        if(!Boolean.parseBoolean(System.getProperty("overlay.auto", "true"))) return;

        watcher = new Thread(OverlayMods::watchLoop, "overlay-watcher");
        watcher.setDaemon(true);
        watcher.start();
        Log.info("[HOTRELOAD] overlay watcher started (dirs: @)", System.getProperty("overlay.dirs", "overlay"));
    }

    private static void watchLoop(){
        boolean baseline = false;
        while(true){
            try{
                long mtime = -1;
                boolean any = false;
                for(String name : System.getProperty("overlay.dirs", "overlay").split(",")){
                    Fi dir = Vars.modDirectory != null ? Vars.modDirectory.child(name.trim()) : null;
                    if(dir != null && dir.exists()){
                        any = true;
                        mtime = Math.max(mtime, latestModTime(dir));
                    }
                }
                if(any){
                    if(!baseline){
                        //first scan: record the baseline without reloading anything
                        lastReloadTime = mtime;
                        baseline = true;
                    }else if(mtime > lastReloadTime){
                        if(changeSeenAt == 0){
                            changeSeenAt = Time.millis();
                        }else if(!reloading && Time.millis() - changeSeenAt > debounce){
                            long seen = mtime;
                            lastReloadTime = seen;
                            changeSeenAt = 0;
                            reloading = true;
                            Core.app.post(() -> {
                                try{
                                    reload();
                                }catch(Throwable e){
                                    Log.err("[HOTRELOAD] overlay reload failed", e);
                                }finally{
                                    reloading = false;
                                }
                            });
                        }
                    }
                }
            }catch(Throwable e){
                Log.err("[HOTRELOAD] overlay watcher error", e);
            }
            try{
                Thread.sleep(pollInterval);
            }catch(InterruptedException ignored){
            }
        }
    }

    /** Latest modification time across all files/dirs of a mod directory (content + classes).
     *  Directory mtimes are included so deletions are detected too. */
    private static long latestModTime(Fi dir){
        long[] max = {dir.lastModified()};
        dir.walk(f -> {
            if(f.lastModified() > max[0]){
                max[0] = f.lastModified();
            }
        });
        return max[0];
    }

    /** Hot-reloads all watched mods in place. Safe to call from the JS console or any thread. */
    public static void reload(){
        if(Vars.mods == null) return;

        int reloadedAny = 0;
        for(String name0 : System.getProperty("overlay.dirs", "overlay").split(",")){
            final String name = name0.trim();
            LoadedMod mod = Vars.mods.mods.find(m -> m.name.equals(name));
            if(mod == null){
                Log.warn("[HOTRELOAD] watched mod '@' not found in @ — nothing to reload.", name, Vars.modDirectory);
                continue;
            }

            Log.info("[HOTRELOAD] reloading mod '@'...", name);
            if(Vars.mods.reloadMod(mod)){
                reloadedAny++;
                if(!Vars.headless && Vars.ui != null && Vars.ui.hudfrag != null){
                    //rebuild the block placement menu so new/removed blocks show up
                    Vars.ui.hudfrag.blockfrag.rebuild();
                }
                //HOTRELOAD-DEMO: core hot-swap marker — count what the mod now contributes
                Log.info("[HOTRELOAD] core hot-swap OK: '@' now contributes @ content entries.",
                    name,
                    Vars.content.getBy(mindustry.ctype.ContentType.item).count(c -> ((mindustry.ctype.MappableContent)c).name.startsWith(name + "-")));
                Log.info("[HOTRELOAD] item registry dump:");
                for(Object c : Vars.content.getBy(mindustry.ctype.ContentType.item)){
                    mindustry.ctype.MappableContent mc = (mindustry.ctype.MappableContent)c;
                    Log.info("  item '@' id=@ identity=@ mod=@", mc.name, mc.id, System.identityHashCode(mc), mc.minfo.mod == null ? "?" : mc.minfo.mod.name);
                }
            }
        }
        Log.info("[HOTRELOAD] overlay reload complete (@ mod(s) reloaded).", reloadedAny);
    }
}