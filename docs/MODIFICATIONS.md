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

## 4. 构建脚本

| 文件 | 改动 |
|---|---|
| `settings.gradle` | include `hotreload-agent`、`overlay` |
| `desktop/build.gradle` | 新增 `hotreloadRun` 任务（agent + -Xmx1g + 透传 `-Doverlay.dirs`） |
| `server/build.gradle` | 新增 `hotreloadRun`（workingDir=core/assets，注入数据目录 env） |
| `gradle.properties` | daemon 堆 8G→1024m（WSL 3.3GB 内存约束；CI 不受影响） |

## 5. overlay 示例 Mod（新增目录）

`overlay/`：可热重载的演示 Mod（`mod.hjson` hidden:false + minGameVersion 160）
- Java 内容：silver（物品）、demo-generator（发电机）、demo-wall（墙）— `OverlayMod.java`
- HJSON 内容：demo-ore、demo-plasma（物品）、demo-panel（5x5 红墙）、
  demo-cannon（3x3 连发炮塔，copper 弹药）
- `:overlay:syncDev` 把源同步到 devdata/mods/overlay（会覆盖手动放 devdata 的内容）

## 6. CI（`.github/workflows/sync-upstream.yml`）

- **stable**：每日 UTC 02:00 取上游最新 `v*` tag → 在我们 master 提交上打同名 tag →
  构建 → 发布同名稳定版 Release（一次构建，产物复用）。
- **nightly**：发现新版本或手动 dispatch 时构建发布 prerelease。
- 不合并上游代码（历史独立）；tag 用 GITHUB_TOKEN 推（同 job 内构建，无需触发链）。

## 7. 已知问题 / 注意事项

- **热重载改内容后旧存档可能无法读取**（内容 id 变化 → 存档越界崩溃）。开新局即可。
- 存档兼容性是热重载系统的固有限制，非 bug。
- stable 与 nightly 各自构建一次（约 2×15min CI 时间）——可优化为 artifact 共享。
- notify job 失败时会开 issue，连续失败可能刷屏。
- fine-grained PAT 的 push **不会触发** GitHub Actions workflow（GitHub 限制），
  因此 CI 内部闭环完成全部工作，不依赖外部 token 触发。

## 8. 验证状态（2026-08-21）

- ✅ 服务端/客户端（Xvfb 软渲染、WSLg 原生窗口）热重载全链路
- ✅ 多目录（overlay,mymod）同时监控与重载
- ✅ 内容 id 冲突修复（27..31 连续唯一）、双重前缀修复
- ✅ demo-cannon 弹药修复（Turret 必须定义 ammo 才会开火）
- ✅ CI：v159.7 稳定版 + nightly 预发布均已发布
