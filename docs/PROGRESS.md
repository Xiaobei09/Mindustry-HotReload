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
  - `Mods.java`：`loadMod` 改 public、新增 `reloadMod(LoadedMod)`、`packModSprites()`、`loadSync()` 里启动 `OverlayMods.init()`
  - `OverlayMods.java`（新文件）：overlay 目录监听、debounce、`reload()`（含 UI 重建 `Vars.ui.hudfrag.blockfrag.rebuild()`）
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

- CI workflow `Sync & Release` run 32368517027：
  - `Sync upstream` 任务 ✅ 成功（43s）
  - `Build & publish nightly` 任务 🔄 进行中（首次运行需下载 Gradle/依赖，耗时长）
- 本地工作树已 clean（除 gitignore 的 devdata 等）

## 6. 待办 / 下一步

- [ ] 确认 CI Build 任务完成且 nightly Release 发布成功（`gh release view nightly`）
- [ ] 若 CI 失败：查看构建日志修复（大概率是 sprite packing/tools:pack 或依赖下载问题）
- [ ] （可选）验证 release 的 `mods/overlay` 目录包含编译后的 class（syncDev 已生成）
- [ ] （可选）把 `:core:compileJava --continuous` 集成进 dev-run.sh 的单命令体验（已有，未实测）
- [ ] （可选）真机试玩：本地 `./gradlew :desktop:hotreloadRun` 起游戏验证 overlay 热重载 UI 流程

## 7. 关键技术细节与踩坑记录

### v8 与 v7 的 API 差异（本 fork 是 Mindustry v8）
- **没有 `ContentList`**：mod 主类继承 `mindustry.mod.Mod`，内容写在 `loadContent()`（不是 `load()`）
- **没有 `Recipes` 类**：无全局配方表，内容重载无需重建配方
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

## 8. 恢复后续工作步骤（若上下文丢失）

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