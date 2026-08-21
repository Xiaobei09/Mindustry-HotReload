# 项目进度与计划 (HOTRELOAD)

> 本文件记录整个项目的目标、架构决策、已完成/未完成清单与关键技术细节，用于意外中断后快速恢复上下文。

## 1. 项目目标（用户需求）

保留现有的"游戏现场"方案（Silicon mod 部署到原版游戏 mods 目录，`/root/Silicon` 不动），
另建新项目 Mindustry-HotReload：

1. 完整游戏分支（fork 上游 Anuken/Mindustry），可在 core 里直接改逻辑、加新物品。
2. **不重启进程**即可热重载代码（JVM 热替换 Agent）。
3. 自动同步上游源码 + GitHub Actions 定时构建并发布 Release。

用户确认的三项决策：
- 形态 = **完整游戏分支**
- 热重载程度 = **不重启进程重载代码**（JVM 热替换）
- 同步/发布 = **GitHub Actions 定时同步 + 发布 Release**

## 2. 仓库信息

- GitHub: `Xiaobei09/Mindustry-HotReload`（fork 自 Anuken/Mindustry，public）
- 本地: `/root/Mindustry-HotReload`（分支 `master`，remote: origin=自己的 fork, upstream=Anuken/Mindustry）
- GitHub 凭据: `/root/.git-credentials`（gh 已登录，账号 Xiaobei09，token 可用）
- 推送命令（origin 未配置凭据时的替代）:
  `git push https://Xiaobei09:<token>@github.com/Xiaobei09/Mindustry-HotReload.git master`

## 3. 架构设计

两条互补的热重载路径：

| 路径 | 适用 | 触发 | 实现 |
|---|---|---|---|
| JVM 热替换 Agent | 核心逻辑（mindustry.*） | `./gradlew :core:compileJava --continuous` | `Instrumentation.redefineClasses` + `ClassFileTransformer` |
| Overlay 内容重载 | 新物品/新方块/mod 内逻辑 | `./gradlew :overlay:syncDev` | 监听器检测文件变化 → `Mods.reloadMod()`：销毁旧内容→关旧classloader→重新load→init/postInit→重打包贴图→重建UI |

## 4. 已完成

- [x] 调研构建结构（模块: desktop/core/server/ios/annotations/tools/tests；Java 17；Arc 依赖走 jitpack；无本地 Android SDK 时自动跳过 android 模块）
- [x] 创建 fork 仓库（gh repo fork）并本地 clone，配置 origin/upstream 双远程
- [x] `hotreload-agent/` 模块：零依赖 Java Agent（premain+agentmain，文件监听，baseline 机制，redefine + transformer）
- [x] **端到端验证通过**：JVM 进程内 `Demo.value()` 1→2 热替换生效，无需重启
- [x] core 集成（全部带 `HOTRELOAD` 标记，保持与上游最小 diff）：
  - `Mods.java`：`loadMod` 改 public、新增 `reloadMod(LoadedMod)`、`packModSprites()`、`load()` 末尾启动 `OverlayMods.init()`（**注意：不能挂 loadSync()，服务端不走 Loadable 链**）
  - `OverlayMods.java`（新文件）：overlay 目录监听、debounce、首次扫描 baseline（防启动误重载）、`reload()`（含 UI 重建 `Vars.ui.hudfrag.blockfrag.rebuild()`）
- [x] `overlay/` 示例内容 mod：`mod.hjson` + Java（新物品 silver、自定义逻辑发电机 demo-generator、墙体 demo-wall）+ HJSON 物品 demo-ore + 生成的 PNG 贴图
- [x] `desktop/build.gradle`：新增 `hotreloadRun` 任务（挂 agent、设 devdata、部署 overlay）
- [x] `scripts/dev-run.sh`、`scripts/dev-compile.sh`
- [x] `.github/workflows/sync-upstream.yml`：每日定时 merge 上游 → 构建 → 发布 nightly Release（含 notes heredoc 修复、checkout v5）
- [x] 本地全量构建验证：`:desktop:dist`（Mindustry.jar 86MB）+ `:server:dist`（server-release.jar）+ `:hotreload-agent:jar` + `:overlay:syncDev` 全部成功
- [x] `.gitignore` 增加 `/devdata/`、`/overlay/build/`、`/overlay/.gradle/`、`hotreload.log`
- [x] README 重写
- [x] 已提交并推送到 master（commit: 2d0482f / 4fd8e35 / 后续）
- [x] CI 手动触发中（workflow_dispatch）——**见下方"当前状态"**

## 5. 当前状态

- CI workflow `Sync & Release` 已成功跑通两次，nightly Release 已发布（4 个资产：Mindustry-HotReload.jar / server jar / hotreload-agent.jar / mods-overlay.zip），每日 02:00 UTC 自动运行
- **无头服务端真机验证全链路通过**（2026-08-20）：
  - agent 挂载 + overlay watcher 启动（服务端）
  - 新增物品 HJSON → 自动 reload mod（`HOTRELOAD: reloaded mod 'overlay'`）
  - 改 `OverlayMods.java` → `:core:compileJava` → 运行中进程 hot-swapped，新代码立即生效（日志变 v2 后改回）
  - 删除物品文件 → 自动 reload（修复：目录 mtime 也要统计，否则删除不可见）
  - demo-ore.json 的 `type` 字段在 v8 报 "Unknown field"（v8 由目录决定类型），已移除
- git 提交身份修复：历史 6 个 commit 作者 root→Xiaobei09（filter-branch + force-push 已推送）
- 服务端数据目录修复：`ServerLauncher` 支持 `MINDUSTRY_DATA_DIR`/`mindustry.data.dir`，`server:hotreloadRun` 用 `workingDir=core/assets` + env 指向 `devdata`（否则 Fi 按 CWD 解析会写错位置）
- 本地工作树已 clean（除 gitignore 的 devdata 等）
- 服务端 hotreloadRun 进程仍在后台运行（端口 6567，日志 `devdata/server-hotreload.log`），可作活体演示；停掉：`pkill -f ServerLauncher`

## 6. 待办 / 下一步

- [ ] （可选）桌面端真机试玩：`./gradlew :desktop:hotreloadRun` 验证 overlay 热重载 UI 流程（服务端已验证，客户端逻辑一致）
- [ ] （可选）新内容热加载后的视觉确认（区块重建等）
- [ ] （可选）把 `:core:compileJava --continuous` 集成进 dev-run.sh 单命令体验

## 7. 关键技术细节与踩坑记录

### v8 与 v7 的 API 差异（本 fork 是 Mindustry v8）
- **没有 `ContentList`**：mod 主类继承 `mindustry.mod.Mod`，内容写在 `loadContent()`（不是 `load()`）
- **没有 `Recipes` 类**：无全局配方表，内容重载无需重建配方
- **`type` 字段在 HJSON 里是多余字段**：v8 内容类型由目录决定（content/items/ 下即 Item），写了会报 "Unknown field" 警告
- `Category` 在 `mindustry.type` 包（不在 world.meta）
- `MappableContent` 构造时自动加 mod 名前缀（`content.transformName`），Java 内容名字无需手写前缀
- 方块自定义逻辑：Block 子类内写 `public class XxxBuild extends Building` 内部类，自动注册为 buildType
- 发电逻辑在 `GeneratorBuild.getPowerProduction()`（不是 updateTile）
- `ContentLoader.remove(content)` 支持运行时移除 mod 内容
- 运行时贴图：`Core.atlas.addRegion(name, new TextureRegion(new Texture(pix)))` 可动态加入 atlas
- 建造菜单刷新：`Vars.ui.hudfrag.blockfrag.rebuild()`
- 目录 mod 的类加载：`Platform.loadJar` 用 URLClassLoader，目录类直接放 mod 根（`overlay/overlay/OverlayMod.class`）
- 贴图打包命名规则（重载时必须一致）：
  - 文件名 `<name>.png` → region `<modname>-<name>`
  - 例外：文件名第一段连字符后已以 `<modname>-` 开头则不重复加前缀

### Gradle 9.3.1 注意
- **顶层 `sourceCompatibility`/`targetCompatibility` 属性已移除**（报 "Could not set unknown property"），必须用 `tasks.withType(JavaCompile){ ... }` 或 `java {}` 扩展
- 本仓库惯例：子项目插件在根 `build.gradle` 的 `project(":x"){}` 块里用 `apply plugin: "java"`；子项目 build.gradle 只放任务
- `ClassFileTransformer` 不是 lambda 友好的函数式接口（JDK21 下 5 参方法非抽象），用匿名类

### GitHub Actions 注意
- workflow 的 `name:` 解析失败会回退显示路径（fallback），`on:` 不生效
- Release notes 里嵌双引号会把 YAML 字符串截断 → 用 `run: |` + heredoc `<<'NOTES'`
- `${{ }}` 在 heredoc 里也会被 GHA 模板展开（先于 bash），单引号 heredoc 也安全
- fork 需 `Settings → Actions → Workflows permissions = Read and write`（已用 API 设置）
- actions/checkout 用 v5（v4 是 Node20 已弃用）

## 7.5 多目录热重载 + 模组接入方式（2026-08-21 验证）

- `OverlayMods` 现支持 `-Doverlay.dirs=a,b,c` 监控多个目录 Mod（默认 `overlay`），
  逗号分隔；watcher 取所有目录 mtime 最大值，任一变化即统一 `reload()` 全部 watched 目录。
- **热替换约束**：只能改方法体（字段/方法/嵌套类增删会导致 redefine 静默失败）。
  故 `overlay.dirs` 用 `System.getProperty` 在方法体内读取，不新增成员；`overlayName`
  字段改为运行时初始化（去掉 ConstantValue），实测 redefine 仍成功。
- desktop/build.gradle 的 hotreloadRun 透传 gradle 的 `-Doverlay.dirs` 到游戏 JVM
  （`System.getProperty` 判断非空则 `jvmArgs("-Doverlay.dirs=...")`），
  命令行 `./gradlew :desktop:hotreloadRun -Doverlay.dirs=overlay,mymod` 即可。
- **目录 Mod 的 Java 主类加载条件**：`mod.hjson` 必须给 `minGameVersion`（如 "160"），
  否则 `Version.isAtLeast("0")` 为 true 但 `meta.getMinMajor() >= minJavaModGameVersion`
  为 false → main 类不加载 → loader=null → `reloadMod` 开头 guard 直接返回 false（静默）。
  缺失时启动无报错但无法热重载 Java 内容。
- 多目录实测（desktop 客户端）：mymod(Java jade + HJSON my-ore)、overlay(silver + demo-ore
  + demo-plasma) 各 2/3 项，任一目录 touch 均触发两目录统一重载（2 mod(s) reloaded）。
- **syncDev 会覆盖 devdata**：手动只加在 devdata 的内容（demo-panel/demo-plasma）会被
  `:overlay:syncDev` 冲掉 → 应把示例内容加入 `overlay/` 源（已补 demo-plasma.json、
  blocks/demo-panel.json、sprites/demo-panel.png）。
- 接入方式文档：`docs/MODS.md`（目录 Mod / jar·zip / scripts/main.js / Plugin / 多目录）。

## 7.6 WSL2 / opencode 崩溃缓解（内存不足）

- 现象：WSL2 总内存仅 3.3GB，gradle daemon(~1.2GB)+客户端(-Xmx1g)+opencode(node) 多 JVM
  并存 → OOM 被杀（曾见客户端两次崩溃：OOM、GLXBadFBConfig 后软渲染解决）。
- Windows 侧 `C:\Users\<用户>\.wslconfig`：
  ```ini
  [wsl2]
  memory=8GB
  swap=4GB
  ```
  改后 `wsl --shutdown` 重启 WSL（IP 会变，portproxy 需同步更新）。
- 日常省内存：构建完 `./gradlew --stop`；不要同时跑服务端+客户端；
  客户端启动用 `-Dorg.gradle.jvmargs=-Xmx768m` 且 desktop 任务已内置 `-Xmx1g`。
- opencode 崩溃同因内存，加内存后自然缓解。

## 7.7 WSLg 图形界面（2026-08-21 验证）

- WSL2 自带 WSLg（Win11/10 21H2+）：GUI 应用直接显示在 Windows 桌面，无需 VNC/截图。
  `DISPLAY=:0`(Xwayland) + `WAYLAND_DISPLAY=wayland-0`，/mnt/wslg 存在即启用。
- 启动：`setsid env DISPLAY=:0 ./gradlew -Dorg.gradle.jvmargs=-Xmx768m :desktop:hotreloadRun -Doverlay.dirs=overlay,mymod`
  （去掉 xvfb-run）；窗口标题 "Mindustry" 出现在 Windows（可用 `DISPLAY=:0 xwininfo -root -tree` 确认）。
- **坑：GLXBadFBConfig** —— WSLg Xwayland 上创建 GL context 失败（LIBGL_ALWAYS_SOFTWARE=1 也无法解决），
  但游戏仍正常运行（窗口 + 渲染正常，日志有 [E] 但无碍）。若黑屏，备选
  `SDL_VIDEODRIVER=wayland`（EGL 路径）或退回 Xvfb+VNC（/root/screenshot-loop.sh、x11vnc 脚本仍在）。
- 图形界面下热重载验证：改 demo-ore 颜色→实时变红、改 mymod my-ore→变蓝、touch OverlayMods.class→
  hot-swapped（均通过）。截图/HTTP/VNC 服务已停（窗口直显，不再需要）。

## 8. 严格版本发布架构（2026-08-21 重构）

- 仓库不再内联改动发布：`patches/01-hotreload.patch`（97 行，make-patches.py 以严格锚点生成）
  + `inject/`（OverlayMods.java、hotreload-agent/ 零冲突新文件）
- `scripts/inject-hotreload.sh`：干净上游树 = 拷贝 inject + git apply patches
- `scripts/make-overlay.sh`：现场生成演示 mod（纯 HJSON+base64 贴图），overlay/ 已移出 git
- reloadMod/packModSprites 全部迁入 OverlayMods.java；Mods.java 仅剩 4 个微 hunk
  （parser/lastOrderedMods 去私有、loadMod public、load() 挂 init()）
- CI `strict-release.yml`：克隆上游 tag → 注入 → 编译 → 发布同名 Release（严格等于该版本）；
  支持 backfill 批量补齐（40/次）、nightly=上游 master HEAD prerelease；需 permissions: contents:write
- 已验证：v159.7 干净树注入编译通过（本机）；CI 端到端发布成功（2m30s，4 资产）
- 兼容下限：v142（更老版本锚点失配，不发布）
- 旧"快照冒名"Release 已批量删除（cleanup-releases.sh，TLS 失败的单独重试）

## 8.1 跨版本兼容收尾（2026-08-21，全部 47 tag 发布成功）

- OverlayMods 版本无关化：rebuild()/Content.postInit() 反射调用、
  content.remove→getBy(type).liveSeq remove、ClassLoaderCloser→URLClassLoader 强转、
  ContentType.folderName 反射回退；inject/ 与 master 两份同步改
- Arc 依赖：jitpack 探测 arc-core+packer 双 POM，未命中则克隆 archash 源码
  publishToMavenLocal——模块列表按该时代 settings.gradle 动态交集（老 Arc 无 filedialogs 等）；
  JDK16 包裹（Gradle<7.3）+ -x javadoc + 发布后 ls 校验
- 踩坑记录：gh api releases 默认分页 30 条（--paginate 才是全量）；CI 内 cd arc 后
  相对路径要写 settings.gradle 而非 arc/settings.gradle；jnigen 模块 publish 不触发原生编译
- 最终状态：v142~v159.7 共 47 个 Release 全部成功（各 4 资产），nightly prerelease，
  git tags 与 releases 一一对应无孤儿

## 9. 恢复后续工作步骤（若上下文丢失）

```bash
cd /root/Mindustry-HotReload
git fetch origin && git pull        # 拉最新（含进度文档）
# 查看 CI:
gh run list --repo Xiaobei09/Mindustry-HotReload --limit 5
# 补发缺失版本（重复执行直到没有缺失）:
gh workflow run strict-release.yml --repo Xiaobei09/Mindustry-HotReload -f backfill=true
# 本地验证注入流水线:
curl -sL -o /root/work/v.tar.gz https://github.com/Anuken/Mindustry/archive/refs/tags/v159.7.tar.gz
cd /root/work && tar xzf v.tar.gz && bash ~/Mindustry-HotReload/scripts/inject-hotreload.sh Mindustry-159.7
```


```bash
cd /root/Mindustry-HotReload
git fetch origin && git pull        # 拉最新（含进度文档）
# 查看 CI:
gh run list --repo Xiaobei09/Mindustry-HotReload --limit 5
gh run view <run-id> --repo Xiaobei09/Mindustry-HotReload
# 验证 release:
gh release view nightly --repo Xiaobei09/Mindustry-HotReload
# 本地验证热重载（agent 已实测通过）:
./gradlew :hotreload-agent:jar :overlay:syncDev
setsid /opt/jdk17/bin/java -javaagent:hotreload-agent/build/libs/hotreload-agent.jar \
  -Dhotreload.dir=<classdir> -cp <classdir> <主类> &
# 修改源码重编译，观察日志 [hotreload] hot-swapped ...
```