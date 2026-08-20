# Mindustry-HotReload

> 保留线上玩法不变（原版 Mindustry + 现有 mod 照常运行），另起一套可**完全热重载**的开发/发布流水线：
> 自动同步上游源码 → 改逻辑、加物品、加方块 → 进程不重启即时生效 → 自动发布 nightly Release。

这是一个对 [Anuken/Mindustry](https://github.com/Anuken/Mindustry) 的完整 fork，额外加入：

- **JVM 热替换 Agent**（`hotreload-agent/`）—— 运行中的游戏进程内直接热替换已加载的类，改核心逻辑（`mindustry.*`）无需重启。
- **Overlay 内容热重载**（`overlay/`）—— 一个新的独立 mod，新增物品/方块/单位等**内容**以及 mod 内逻辑，改完自动在游戏中重载生效。
- **GitHub Actions 自动同步**—— 每天自动拉取上游 master、合并、构建，并发布 nightly Release，玩家直接下载即用。

## 开发工作流（热重载）

环境要求：JDK 17+（本仓库用 Gradle wrapper，无本地 Android SDK 也能构建桌面端）。

```bash
# 1. 一次性部署 overlay 内容 mod 到 devdata/mods/overlay
./gradlew :overlay:syncDev

# 2. 启动游戏（自动挂上热替换 agent + 开启 overlay 监听）
./gradlew :desktop:hotreloadRun
```

游戏起来之后，改代码的三种玩法：

| 你改什么 | 操作 | 生效方式 |
|---|---|---|
| 核心逻辑（`mindustry.logic.*`、`mindustry.world.*` 等） | `./gradlew :core:compileJava --continuous` | JVM agent 检测到新编译的 class，**当前进程内直接热替换**，无需重启 |
| overlay 内容/逻辑（新物品、新方块、mod 内逻辑） | `./gradlew :overlay:syncDev` | overlay 监听器检测到变化，**重载整个 overlay mod**（新 classloader + 内容重建 + 贴图打包） |
| 数值微调（不改代码） | 游戏内 JS 控制台执行 `overlay.OverlayMod.bonus = 5f` | 下个 tick 立即生效 |

> 冷启动时如果 mod 目录（`devdata/mods/`）里有 overlay，游戏会自动加载它；把 `overlay/` 里的内容直接改掉再运行 `:overlay:syncDev` 即可。

## 新增物品 / 方块示例

`overlay/src/main/java/overlay/OverlayMod.java` 里内置了示例：

- `silver` —— 新物品（纯 Java 声明）
- `demo-generator` —— 带自定义逻辑的发电机（`DemoGeneratorBuild.updateTile()`），逻辑实时可调
- `demo-wall` —— 普通新墙体
- `content/items/demo-ore.json` —— 纯 HJSON 声明的新物品（无需写 Java）

新增方块/物品后跑一次 `./gradlew :overlay:syncDev`，游戏内建造菜单会立即出现新方块（`PlacementFragment` 会自动重建）。

## 核心逻辑热替换原理（agent）

`hotreload-agent` 是一个零依赖的 Java Agent：

```
-javaagent:hotreload-agent.jar
-Dhotreload.dir=core/build/classes/java/main   # 监听的 class 输出目录
-Dhotreload.poll=800                            # 轮询间隔 ms
-Dhotreload.debounce=300                        # 变更去抖 ms
```

- 监听 class 输出目录，`.class` 文件 mtime 变化后读取最新字节码。
- 对已加载的类调用 `Instrumentation.redefineClasses()` 立即生效。
- 对尚未加载的类通过 `ClassFileTransformer` 在加载时提供最新字节码。
- 日志写入 `hotreload.log` 与控制台。

也可以热附加到已运行的服务器（远程调试/线上热修）：

```bash
# 找到目标 JVM pid
jps -l
# 用 agentmain 附加（JDK 自带 tools 支持 attach 到同机 JVM）
./gradlew :hotreload-agent:jar
java -jar hotreload-agent.jar <pid>   # 或者用 jcmd: jcmd <pid> JVMTI.agent_load ...
```

## 自动同步上游 + 发布（CI/CD）

`.github/workflows/sync-upstream.yml` 每天 02:00 UTC（或手动点击 **Run workflow**）自动执行：

1. **同步**：拉取 `Anuken/Mindustry` master → 合并到本仓库 `master`（保留本项目的改动；冲突会失败并创建 issue 提醒人工处理）。
2. **构建**：JDK 17 构建桌面端、服务端、agent、overlay。
3. **发布**：创建/更新 `nightly` Release，内含：
   - `Mindustry-HotReload.jar`（完整游戏，含 overlay 重载能力）
   - `Mindustry-HotReload-server.jar`（无头服务器）
   - `hotreload-agent.jar`（可附加到任意 JVM 热替换逻辑）
   - `mods/overlay/`（放入游戏的 `mods` 目录即可开箱体验内容热编辑）

如需在 fork 上跑工作流：`Settings → Actions → General → Workflow permissions` 选择 **Read and write permissions**（本仓库已配置）。

## 项目结构

```
├── core/                 # 游戏本体源码（上游）+ HOTRELOAD 标记的最小改动
│   └── src/mindustry/mod/
│       ├── Mods.java     # 3 处 HOTRELOAD 标记改动：loadMod 公开、reloadMod()、watcher 钩子
│       └── OverlayMods.java  # overlay 监听器（文件变化 → 重载 mod → 重建 UI）
├── hotreload-agent/      # JVM 热替换 Agent（独立模块，零依赖）
├── overlay/              # 可热重载的示例内容 mod（Java + HJSON + 贴图）
├── devdata/              # 开发数据目录（保存、mods，gitignore）
├── scripts/              # dev-run / dev-compile 辅助脚本
└── .github/workflows/    # 自动同步 + 构建 + 发布
```

## 与上游同步冲突

本项目对上游的改动都带 `HOTRELOAD` 标记且极小（`Mods.java` 约 40 行 + 一个新文件 + 两个新模块），日常自动合并极少冲突。若冲突：

```bash
git remote add upstream https://github.com/Anuken/Mindustry.git
git fetch upstream
git merge upstream/master
# 手工解决冲突（找 `HOTRELOAD` 标记区域），然后推送即可
```

## 构建

```bash
./gradlew :desktop:dist :server:dist :hotreload-agent:jar  # 产出桌面端/服务端/agent
./gradlew :overlay:syncDev                                 # 部署 overlay 到 devdata
```

## 许可

Mindustry 采用 GPL-3.0，本仓库同样遵循。详见 [LICENSE](LICENSE)。