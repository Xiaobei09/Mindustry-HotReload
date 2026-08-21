package mindustry.mod;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.TextureRegion;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.ctype.*;
import mindustry.mod.Mods.LoadedMod;
import mindustry.type.ErrorContent;

import java.util.Locale;

/**
 * HOTRELOAD: watches one or more mod directories under {@code <data>/mods} and hot-reloads
 * them in-game without a restart.
 *
 * Which directories are watched is controlled by {@code -Doverlay.dirs=name1,name2,...}
 * (default {@code overlay}). Each entry is a directory mod under the game's mods folder.
 * While the game runs:
 *   - edit {@code <dir>/content/*.json|hjson} to add items/blocks/units...;
 *   - edit {@code <dir>/src/**} Java code, recompile, and copy the new classes in;
 *   - the game detects the change and reloads the mod: old content is disposed, a fresh
 *     classloader is created, content is re-initialized and new sprites are packed.
 *
 * All of the standard mod shapes work here: directory mods, jar/zip mods dropped into
 * {@code <data>/mods}, script mods (a {@code scripts/main.js} in the mod root), and
 * service plugins (a main class extending {@code mindustry.plugin.Plugin}).
 *
 * Trigger a manual reload any time (e.g. from the in-game JS console):
 *   {@code OverlayMods.reload();}
 *
 * Disable the watcher with {@code -Doverlay.auto=false}; verbose per-item logging
 * with {@code -Doverlay.debug=true}.
 *
 * This class also hosts the runtime reload machinery ({@link #reloadMod(LoadedMod)}),
 * kept OUT of {@link Mods} on purpose: it is injected into upstream sources as a new
 * file, so only a handful of one-token visibility changes in Mods.java ever need to be
 * maintained as patches.
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
        for(String name : System.getProperty("overlay.dirs", "overlay").split(",")){
            final String name0 = name.trim();
            LoadedMod mod = Vars.mods.mods.find(m -> m.name.equals(name0));
            if(mod == null){
                Log.warn("[HOTRELOAD] watched mod '@' not found in @ — nothing to reload.", name0, Vars.modDirectory);
                continue;
            }

            Log.info("[HOTRELOAD] reloading mod '@'...", name0);
            if(reloadMod(mod)){
                reloadedAny++;
                if(!Vars.headless && Vars.ui != null && Vars.ui.hudfrag != null && Vars.ui.hudfrag.blockfrag != null){
                    //rebuild the block placement menu so new/removed blocks show up
                    Vars.ui.hudfrag.blockfrag.rebuild();
                }
                Log.info("[HOTRELOAD] core hot-swap OK: '@' now contributes @ content entries.",
                    name0,
                    Vars.content.getBy(ContentType.item).count(c -> ((MappableContent)c).name.startsWith(name0 + "-")));
                if(Boolean.parseBoolean(System.getProperty("overlay.debug", "false"))){
                    Log.info("[HOTRELOAD] item registry dump:");
                    for(Content c : Vars.content.getBy(ContentType.item)){
                        MappableContent mc = (MappableContent)c;
                        Log.info("  item '@' id=@ identity=@ mod=@", mc.name, mc.id, System.identityHashCode(mc),
                            mc.minfo.mod == null ? "?" : mc.minfo.mod.name);
                    }
                }
            }
        }
        Log.info("[HOTRELOAD] overlay reload COMPLETE (@ mod(s) reloaded) [v2]", reloadedAny);
    }

    /** Runtime hot-reload of a single mod: disposes its content and classloader, reloads it
     * from disk with a fresh classloader, then re-initializes content and re-packs sprites.
     * Mirrors {@code Mods.loadContent()} but scoped to one mod; no restart needed. */
    static boolean reloadMod(LoadedMod mod){
        if(mod == null || mod.loader == null || Vars.android || Vars.ios || Vars.skipModCode) return false;
        Mods mods = Vars.mods;

        try{
            //dispose all content owned by this mod
            for(ContentType type : ContentType.all){
                for(Content c : Vars.content.getBy(type).copy()){
                    if(c.minfo.mod == mod){
                        Vars.content.remove(c);
                    }
                }
            }

            //close the old classloader and drop the old mod entry
            ClassLoaderCloser.close(mod.loader);
            mod.dispose();
            int index = mods.mods.indexOf(mod);
            mods.mods.remove(mod);
            mods.lastOrderedMods = null;

            //reload from disk with a fresh classloader, keeping the same position in the mod list
            LoadedMod reloaded = mods.loadMod(mod.file, false, true);
            if(reloaded == null){
                return false;
            }
            reloaded.state = Mods.ModState.enabled;
            if(index >= 0){
                mods.mods.insert(index, reloaded);
            }else{
                mods.mods.add(reloaded);
            }

            //re-create the fresh mod's content (Java first, then HJSON), mirroring loadContent()
            if(reloaded.main != null && !reloaded.meta.hidden){
                Vars.content.setCurrentMod(reloaded);
                try{
                    reloaded.main.loadContent();
                }catch(Throwable e){
                    Log.err("HOTRELOAD: content error in '@'", reloaded.name, e);
                }
                Vars.content.setCurrentMod(null);
            }

            Fi contentRoot = reloaded.root.child("content");
            if(contentRoot.exists()){
                Seq<LoadRun> runs = new Seq<>();
                for(ContentType type : ContentType.all){
                    String lower = type.name().toLowerCase(Locale.ROOT);
                    String oldName = lower + (lower.endsWith("s") ? "" : "s");
                    Fi[] folders = {oldName.equals(type.folderName) ? null : contentRoot.child(oldName), contentRoot.child(type.folderName)};
                    for(Fi folder : folders){
                        if(folder != null && folder.exists()){
                            for(Fi file : folder.findAll(f -> f.extEquals("json") || f.extEquals("hjson"))){
                                runs.add(new LoadRun(type, file, reloaded));
                            }
                        }
                    }
                }
                runs.sort();
                for(LoadRun l : runs){
                    Content current = Vars.content.getLastAdded();
                    try{
                        Content loaded = mods.parser.parse(l.m, l.file.nameWithoutExtension(), l.file.readString("UTF-8"), l.file, l.type);
                        Log.debug("[@] Loaded '@'.", l.m.meta.name, loaded);
                    }catch(Throwable e){
                        if(current != Vars.content.getLastAdded() && Vars.content.getLastAdded() != null){
                            mods.parser.markError(Vars.content.getLastAdded(), l.m, l.file, e);
                        }else{
                            ErrorContent error = new ErrorContent();
                            mods.parser.markError(error, l.m, l.file, e);
                        }
                    }
                }
            }
            mods.parser.finishParsing();

            //re-initialize the fresh mod's content
            Vars.content.setCurrentMod(reloaded);
            for(ContentType type : ContentType.all){
                for(Content c : Vars.content.getBy(type)){
                    if(c.minfo.mod == reloaded){
                        try{ c.init(); }catch(Throwable e){ Log.err("HOTRELOAD: init error for '@'", c, e); }
                    }
                }
            }
            for(ContentType type : ContentType.all){
                for(Content c : Vars.content.getBy(type)){
                    if(c.minfo.mod == reloaded){
                        try{ c.postInit(); }catch(Throwable e){ Log.err("HOTRELOAD: postInit error for '@'", c, e); }
                    }
                }
            }
            Vars.content.setCurrentMod(null);

            if(!Vars.headless){
                //pack new sprites into the atlas and reload icons for the fresh mod
                packModSprites(reloaded);
                for(ContentType type : ContentType.all){
                    for(Content c : Vars.content.getBy(type)){
                        if(c.minfo.mod == reloaded){
                            try{ c.loadIcon(); }catch(Throwable e){ Log.err("HOTRELOAD: loadIcon error for '@'", c, e); }
                            try{ c.load(); }catch(Throwable e){ Log.err("HOTRELOAD: load error for '@'", c, e); }
                        }
                    }
                }
            }

            Log.info("HOTRELOAD: reloaded mod '@'", reloaded.name);
            return true;
        }catch(Throwable e){
            Log.err("HOTRELOAD: failed to reload mod '@'", mod.name, e);
            return false;
        }
    }

    /** Packs a reloaded mod's sprite PNGs into the runtime atlas, mirroring startup naming rules. */
    static void packModSprites(LoadedMod mod){
        if(Vars.headless || Core.atlas == null) return;
        Fi spritesDir = mod.root.child("sprites");
        if(!spritesDir.exists()) return;

        for(Fi file : spritesDir.findAll(f -> f.extEquals("png"))){
            String baseName = file.nameWithoutExtension();
            int hyphen = baseName.indexOf('-');
            boolean prefixed = hyphen != -1 && baseName.substring(hyphen + 1).startsWith(mod.name + "-");
            String fullName = (prefixed ? "" : mod.name + "-") + baseName;

            if(Core.atlas.getRegionMap().containsKey(fullName)) continue;
            try{
                Pixmap pix = new Pixmap(file.readBytes());
                Texture texture = new Texture(pix);
                texture.setFilter(TextureFilter.linear);
                Core.atlas.addRegion(fullName, new TextureRegion(texture));
                pix.dispose();
                Log.info("HOTRELOAD: packed sprite '@'", fullName);
            }catch(Throwable e){
                Log.err("HOTRELOAD: failed to pack sprite '@'", fullName, e);
            }
        }
    }

    private static class LoadRun implements Comparable<LoadRun>{
        final ContentType type;
        final Fi file;
        final LoadedMod m;

        LoadRun(ContentType type, Fi file, LoadedMod m){
            this.type = type;
            this.file = file;
            this.m = m;
        }

        @Override
        public int compareTo(LoadRun l){
            int d = m.name.compareTo(l.m.name);
            return d != 0 ? d : file.name().compareTo(l.file.name());
        }
    }
}