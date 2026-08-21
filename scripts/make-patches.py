#!/usr/bin/env python3
"""Generates patches/*.patch against a pristine Mindustry checkout.

Usage: make-patches.py <path-to-pristine-mindustry-clone>
Applies the hot-reload edits with strict anchors; any mismatch aborts (version drift).
Then run: cd <checkout> && git diff > ../patches/XX-....patch  (done automatically).
"""
import subprocess, sys, pathlib

target = pathlib.Path(sys.argv[1]).resolve()
root = pathlib.Path(__file__).resolve().parent.parent

def edit(rel, old, new, count=1):
    p = target / rel
    s = p.read_text()
    n = s.count(old)
    assert n == count, f"{rel}: anchor found {n}x (expected {count}):\n{old}"
    p.write_text(s.replace(old, new))

# ---- Mods.java: visibility + init hook --------------------------------------
edit("core/src/mindustry/mod/Mods.java",
     "    private ContentParser parser = new ContentParser();",
     "    ContentParser parser = new ContentParser();")

edit("core/src/mindustry/mod/Mods.java",
     "    private @Nullable Seq<LoadedMod> lastOrderedMods = new Seq<>();",
     "    @Nullable Seq<LoadedMod> lastOrderedMods = new Seq<>();")

edit("core/src/mindustry/mod/Mods.java",
     """        sortMods();
        buildFiles();
    }""",
     """        sortMods();
        buildFiles();

        //HOTRELOAD: start the overlay content watcher (no-op if the overlay mod is absent).
        //Hooked here (not loadSync) because the headless server never runs the Loadable chain.
        OverlayMods.init();
    }""")

edit("core/src/mindustry/mod/Mods.java",
     "    private LoadedMod loadMod(Fi sourceFile, boolean overwrite, boolean initialize) throws Exception{",
     """    //HOTRELOAD: made public so the overlay mod can be reloaded at runtime
    public LoadedMod loadMod(Fi sourceFile, boolean overwrite, boolean initialize) throws Exception{""")

# ---- Content.java: collision-free ids after partial reload -------------------
edit("core/src/mindustry/ctype/Content.java",
     """    public Content(){
        this.id = (short)Vars.content.getBy(getContentType()).size;
        Vars.content.handleContent(this);
    }""",
     """    public Content(){
        //HOTRELOAD: assign a unique id (max+1) instead of list size, so partially reloaded
        //content (a single mod being hot-reloaded) never collides with ids of remaining content.
        short max = 0;
        for(Content c : Vars.content.getBy(getContentType())){
            if(c.id >= max) max = (short)(c.id + 1);
        }
        this.id = max;
        Vars.content.handleContent(this);
    }""")

# ---- settings.gradle: include the agent project ------------------------------
edit("settings.gradle",
     "include 'desktop', 'core', 'server', 'ios', 'annotations', 'tools', 'tests'",
     "include 'desktop', 'core', 'server', 'ios', 'annotations', 'tools', 'tests', 'hotreload-agent'")

# ---- desktop/build.gradle: hotreloadRun task ---------------------------------
edit("desktop/build.gradle",
     """        finalizedBy "zip${platform.toString()}"

    }
}""",
     """        finalizedBy "zip${platform.toString()}"

    }
}

//HOTRELOAD: dev run with the in-process hot-swap agent + overlay content watcher.
tasks.register('hotreloadRun', JavaExec){
    dependsOn classes
    dependsOn ':core:compileJava'
    dependsOn ':hotreload-agent:jar'
    mainClass = project.mainClassName
    classpath = sourceSets.main.runtimeClasspath
    standardInput = System.in
    workingDir = project.assetsDir
    ignoreExitValue = true

    if(System.getProperty("os.name").toLowerCase().contains("mac")){
        jvmArgs("-XstartOnFirstThread")
    }

    jvmArgs += [
        "-Xmx1g",
        "-javaagent:${rootProject.file('hotreload-agent/build/libs/hotreload-agent.jar')}",
        "-Dhotreload.dir=${rootProject.file('core/build/classes/java/main').path}",
        "-Dhotreload.poll=800",
        "-Dhotreload.debounce=300",
        "-XX:+ShowCodeDetailsInExceptionMessages"
    ]

    //HOTRELOAD: pass through watched overlay dirs, e.g. ./gradlew :desktop:hotreloadRun -Doverlay.dirs=overlay,mymod
    if(System.getProperty("overlay.dirs") != null){
        jvmArgs("-Doverlay.dirs=" + System.getProperty("overlay.dirs"))
    }
    //HOTRELOAD: verbose per-item registry dump after each reload, e.g. -Doverlay.debug=true
    if(System.getProperty("overlay.debug") != null){
        jvmArgs("-Doverlay.debug=" + System.getProperty("overlay.debug"))
    }

    if(project.hasProperty("args")){
        args Eval.me(project.getProperties()["args"])
    }

    if(project.hasProperty("jvmArgs")){
        jvmArgs((List<String>) Eval.me(project.getProperties()["jvmArgs"]))
    }

    //dev data dir: keeps the overlay mod and saves inside the repo, away from real saves
    environment("MINDUSTRY_DATA_DIR", rootProject.file("devdata").path)

    if(args.contains("debug")){
        mainClass = "mindustry.debug.DebugLauncher"
    }
}""")

# ---- server/build.gradle: hotreloadRun task ----------------------------------
edit("server/build.gradle",
     """dist.dependsOn classes""",
     """dist.dependsOn classes

//HOTRELOAD: headless server run with the in-process hot-swap agent + overlay watcher.
tasks.register('hotreloadRun', JavaExec){
    dependsOn classes
    dependsOn ':hotreload-agent:jar'
    mainClass = project.mainClassName
    classpath = sourceSets.main.runtimeClasspath
    standardInput = System.in
    workingDir = project.assetsDir
    ignoreExitValue = true

    //HOTRELOAD: repo-local data dir (mods, saves) so the overlay mod is picked up from devdata/mods
    environment("MINDUSTRY_DATA_DIR", rootProject.file("devdata").path)

    jvmArgs += [
        "-javaagent:${rootProject.file('hotreload-agent/build/libs/hotreload-agent.jar')}",
        "-Dhotreload.dir=${rootProject.file('core/build/classes/java/main').path}",
        "-Dhotreload.poll=800",
        "-Dhotreload.debounce=300"
    ]

    if(project.hasProperty("appArgs")){
        args Eval.me(appArgs)
    }
    if(project.hasProperty("jvmArgs")){
        jvmArgs((List<String>) Eval.me(project.getProperties()["jvmArgs"]))
    }
}""")

# ---- ServerLauncher.java: data dir override ----------------------------------
edit("server/src/mindustry/server/ServerLauncher.java",
     """    public void init(){
        Core.settings.setDataDirectory(Core.files.local("config"));""",
     """    public void init(){
        //HOTRELOAD: honor the same data-dir override as the client (MINDUSTRY_DATA_DIR / -Dmindustry.data.dir),
        //so dev runs can point the mods folder at a repo-local directory without touching the real data dir.
        String dataDir = System.getProperty("mindustry.data.dir", OS.env("MINDUSTRY_DATA_DIR"));
        Core.settings.setDataDirectory(dataDir != null ? Core.files.absolute(dataDir) : Core.files.local("config"));""")

print("All edits applied cleanly.")
