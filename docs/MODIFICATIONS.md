# 修改内容清单（相对上游 Anuken/Mindustry）

> 本 fork = Mindustry v8 快照 + **热重载系统**。历史已重写（作者统一为 Xiaobei09），
> 与上游无共同祖先，因此 CI **不合并上游代码**，只镜像上游版本号用于发布。
> 本文列出全部改动，便于 rebase 到新版或排查问题。

## 1. 核心：JVM 热替换 Agent（新增项目）

**`hotreload-agent/`**（全新，上游没有）

- `HotReloadAgent.java`：Java Agent，attach 到运行中的 JVM，轮询
  `-Dhotreload.dir` 指定的 class 目录（默认 800ms，防抖 300ms），检测 `.class`
  变化后调用 `Instrumentation.redefineClasses` 热替换。
- **硬限制**：只能改方法体。增删方法/字段/嵌套类会导致 redefine 静默失败。
- 启动参数：`-javaagent:hotreload-agent.jar -Dhotreload.dir=<classes>`。

## 2. 核心：overlay 目录热重载（新增文件）

**`core/src/mindustry/mod/OverlayMods.java`**（全新）

- 监控 `<data>/mods/` 下一个或多个目录 Mod（`-Doverlay.dirs=a,b,c`，默认 `overlay`），
  文件变化后自动重载该 Mod：销毁旧内容 → 新建类加载器 → 重建 Java + HJSON 内容 →
  重打包贴图 → 刷新建造菜单。
- 手动触发：游戏内 JS 控制台 `OverlayMods.reload();`；`-Doverlay.auto=false` 关闭。
- 首次扫描记录 baseline（防启动误重载）；目录 mtime 计入检测（删除文件可感知）；
  600ms 防抖；重载计数日志含 item registry dump（诊断用）。

## 3. 核心改动（上游文件的修改点）

### `core/src/mindustry/mod/Mods.java`
1. `load()` 末尾调用 `OverlayMods.init()`（服务端不走 loadSync，必须挂在 load 上）。
2. **新增 `reloadMod(LoadedMod)`**：单 Mod 运行时重载——移除旧内容、关闭旧类加载器、
   原位重载并保持 mod 列表顺序；内联了单 Mod 内容创建流程（Java loadContent +
   content/ HJSON 解析 + finishParsing + init/postInit + 贴图重打包）。
   - 注意：**内联是刻意的**（热替换不能给 Mods 加新方法/嵌套类以外的结构）。
   - Java 内容加载后 `content.setCurrentMod(null)` 再解析 HJSON（否则物品名双重前缀）。
3. `loadMod` 从 private 改为 public（reloadMod 复用）。

### `core/src/mindustry/ctype/Content.java`
- 构造器 id 分配从 `列表size` 改为 **max(现有id)+1**：
  部分重载后内容列表有空洞，按 size 分配会与未重载 Mod 的 id 冲突
  （表现为两个物品共享同一存档槽位、数量联动）。原版顺序加载下行为不变。

### `server/src/mindustry/server/ServerLauncher.java`
- 支持 `MINDUSTRY_DATA_DIR` / `mindustry.data.dir` 环境变量覆盖数据目录
  （headless 演示用 devdata）。

## 4. 构建脚本（补丁形式维护）

对上游文件的改动全部收敛为 `patches/01-hotreload.patch`（97 行新增 / 6 行删除），
由 `scripts/make-patches.py` 以严格锚点生成（锚点失配即报错，提示版本漂移）：

| 文件 | 改动 |
|---|---|
| `settings.gradle` | include `hotreload-agent` |
| `desktop/build.gradle` | 新增 `hotreloadRun` 任务（agent + -Xmx1g + 透传 `-Doverlay.dirs/-Doverlay.debug`） |
| `server/build.gradle` | 新增 `hotreloadRun`（数据目录 env） |
| `server/.../ServerLauncher.java` | 支持 MINDUSTRY_DATA_DIR 数据目录覆盖 |
| `core/.../Mods.java` | 仅 4 处微改：parser/lastOrderedMods 去私有、loadMod 公开、load() 末尾挂 OverlayMods.init() |
| `core/.../Content.java` | id 改为 max+1（部分重载不冲突） |

零冲突新增文件放 `inject/`（OverlayMods.java + hotreload-agent/ 整个项目），
`scripts/inject-hotreload.sh` = 拷贝 inject/ + git apply patches/。
reloadMod/packModSprites 逻辑全部位于 OverlayMods.java（我们自己的文件，随意扩展），
Mods.java 的 diff 因此保持极小。

本地开发：master 分支直接内联了同样的改动（gradle.properties 另有 WSL 内存调整
8G→1024m，仅本机需要）。修改注入逻辑后运行 make-patches.py 重新生成补丁。

## 5. 演示 Mod（不入库）

`overlay/` 目录已从仓库移除（避免污染游戏源码库）。演示 mod 由
`scripts/make-overlay.sh` 现场生成（内容纯 HJSON + base64 内嵌贴图，无编译产物，
minGameVersion 146），输出目录 mod 或 zip。本地运行副本在 devdata/mods/overlay。

## 6. CI（`.github/workflows/strict-release.yml`）——严格版本发布

**jar 严格等于上游 tag**：克隆上游指定 tag 源码 → inject-hotreload.sh 注入 →
编译 → 发布同名 Release。补丁打不上=该版本不支持，构建失败并明确报告。

- 手动 dispatch：`ref`（默认最新 v-tag）、`backfill`（批量补齐缺失版本，每次最多 40 个）、
  `nightly`（上游 master HEAD → prerelease）
- 每日 UTC 02:00 自动 nightly
- 资产：Mindustry-HotReload.jar / server.jar / hotreload-agent.jar / overlay.zip

## 7. 已知问题 / 注意事项

- **热重载改内容后旧存档可能无法读取**（内容 id 变化 → 存档越界崩溃）。开新局即可。
- 存档兼容性是热重载系统的固有限制，非 bug。
- 补丁锚点经逐版本探测：**v142+ 可注入**（Gradle 7.5.1 + Java 17 构建通过）；
  v141 及以下锚点失配，不支持。backfill 自动跳过 v142 以下。
- fine-grained PAT 的 push **不会触发** GitHub Actions workflow（GitHub 限制）；
  本工作流全部使用 GITHUB_TOKEN，无外部依赖。

## 8. 验证状态（2026-08-21）

- ✅ 服务端/客户端（WSLg 原生窗口）热重载全链路
- ✅ 多目录（overlay,mymod）同时监控与重载
- ✅ 内容 id 冲突修复（27..31 连续唯一）、双重前缀修复
- ✅ demo-cannon 弹药修复（Turret 必须定义 ammo 才会开火）
- ✅ reloadMod 迁移至 OverlayMods 后功能回归通过
- ✅ 补丁在 v159.7 干净树上注入成功并编译通过；v142-v146 锚点探测通过
