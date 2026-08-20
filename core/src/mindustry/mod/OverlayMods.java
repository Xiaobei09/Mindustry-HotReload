package mindustry.mod;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;

/**
 * HOTRELOAD: watches the "overlay" mod directory and hot-reloads it in-game without a restart.
 *
 * The overlay is a regular directory mod living in {@code <data>/mods/overlay} (see the
 * {@code overlay/} project in this repository). While the game runs:
 *   - edit {@code overlay/content/*.json|hjson} to add items/blocks/units...;
 *   - edit {@code overlay/src/**} Java code, recompile with {@code ./gradlew :overlay:syncDev};
 *   - the game detects the change and reloads the mod: old content is disposed, a fresh
 *     classloader is created, content is re-initialized and new sprites are packed.
 *
 * Trigger a manual reload any time (e.g. from the in-game JS console):
 *   {@code OverlayMods.reload();}
 *
 * Disable the watcher with {@code -Doverlay.auto=false}.
 */
public class OverlayMods{
    public static final String overlayName = "overlay";
    private static final long pollInterval = 500;
    private static final long debounce = 600;

    private static Thread watcher;
    private static volatile long lastReloadTime;
    private static volatile long changeSeenAt;
    private static volatile boolean reloading;

    /** Finds the overlay mod directory, if present. */
    public static Fi overlayDir(){
        return Vars.modDirectory != null && Vars.modDirectory.child(overlayName).exists() ? Vars.modDirectory.child(overlayName) : null;
    }

    /** Starts the file watcher. Called from {@code Mods.loadSync()}. No-op when disabled or already running. */
    public static void init(){
        if(watcher != null) return;
        if(!Boolean.parseBoolean(System.getProperty("overlay.auto", "true"))) return;

        watcher = new Thread(OverlayMods::watchLoop, "overlay-watcher");
        watcher.setDaemon(true);
        watcher.start();
        Log.info("[HOTRELOAD] overlay watcher started (dir: @)", Vars.modDirectory != null ? Vars.modDirectory.child(overlayName) : "n/a");
    }

    private static void watchLoop(){
        boolean baseline = false;
        while(true){
            try{
                Fi dir = overlayDir();
                if(dir != null){
                    long mtime = latestModTime(dir);
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

    /** Latest modification time across all files of the overlay mod (content + classes). */
    private static long latestModTime(Fi dir){
        long[] max = {dir.lastModified()};
        dir.walk(f -> {
            if(!f.isDirectory() && f.lastModified() > max[0]){
                max[0] = f.lastModified();
            }
        });
        return max[0];
    }

    /** Hot-reloads the overlay mod in place. Safe to call from the JS console or any thread. */
    public static void reload(){
        if(Vars.mods == null) return;

        LoadedMod mod = Vars.mods.mods.find(m -> m.name.equals(overlayName));
        if(mod == null){
            Log.warn("[HOTRELOAD] overlay mod '@' not found in @ — nothing to reload.", overlayName, Vars.modDirectory);
            return;
        }

        Log.info("[HOTRELOAD] reloading overlay mod...");
        if(Vars.mods.reloadMod(mod) && !Vars.headless && Vars.ui != null && Vars.ui.hudfrag != null){
            //rebuild the block placement menu so new/removed blocks show up
            Vars.ui.hudfrag.blockfrag.rebuild();
        }
        Log.info("[HOTRELOAD] overlay reload complete.");
    }
}