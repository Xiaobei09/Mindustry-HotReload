# Mindustry-HotReload

原版 Mindustry + **热重载开发系统**：改核心逻辑或 mod 内容，游戏进程不重启即时生效。
所有 Release 均为**严格构建**：克隆上游指定 tag 源码 → 注入热重载补丁 → 编译发布。

## Release 下载

| 资产 | 用途 |
|---|---|
| `Mindustry-HotReload.jar` | 客户端（桌面版） |
| `server.jar` | 专用服务器 |
| `hotreload-agent.jar` | JVM 热替换 agent（开发用） |
| `overlay.zip` | 演示 mod：放进 mods/ 后边玩边改它的内容文件，观察实时重载 |

- **稳定版** `vX.Y`：严格等于上游同名 tag + 热重载补丁（兼容范围 **v142+**）
- **nightly**：上游 master HEAD + 补丁（预发布）

## 热重载能做什么

| 你改什么 | 操作 | 生效方式 |
|---|---|---|
| 核心逻辑（`mindustry.*`） | 重编译 core | JVM agent 进程内热替换 class |
| mod 内容/逻辑（新物品、方块、单位） | 保存到 watched 目录 | 监听器重载整个 mod（新 classloader + 内容重建 + 贴图打包） |
| 数值微调 | 游戏内 JS 控制台 | 立即生效 |

## 本地开发

```bash
# 启动（自动挂 agent + 监听 devdata/mods 下的 overlay,mymod 目录）
./gradlew :desktop:hotreloadRun -Doverlay.dirs=overlay,mymod
# 服务端同理
./gradlew :server:hotreloadRun -Doverlay.dirs=overlay
```

- watched 目录是**目录 mod**（`mod.hjson` + `content/` + `sprites/` + 可选 `src/`），
  改动后约 1 秒内自动重载；也可在游戏内 JS 控制台手动 `OverlayMods.reload()`
- 演示 mod 可用 `scripts/make-overlay.sh <目录>` 生成
- 详细文档见 [docs/MODS.md](docs/MODS.md)（mod 接入）、[docs/MODIFICATIONS.md](docs/MODIFICATIONS.md)（改动清单）

## 构建架构（对上游的侵入最小化）

master 分支的注入面收敛为：

- `inject/` —— 零冲突新文件：`OverlayMods.java`（全部重载逻辑）、`hotreload-agent/`
- `scripts/make-patches.py` —— 对上游文件的**语义锚点编辑**（锚点失配即报错）：
  Mods.java 仅 4 处微改（3 个可见性 + 1 行 init 钩子）、Content.java id 防冲突、
  settings/desktop/server 构建脚本、ServerLauncher 数据目录
- `scripts/inject-hotreload.sh <树>` —— 拷贝 inject/ + 执行语义编辑，用于任意干净上游树
- `.github/workflows/strict-release.yml` —— 克隆上游 tag → 注入 → 编译 → 发布；
  支持 `backfill`（批量补齐缺失版本）与 `nightly`

> 补丁锚点经逐版本探测：v142+ 可注入；更老版本不发布。
> 本机 WSL 内存受限时调低 `gradle.properties` 的 `-Xmx`（勿用命令行覆盖，会丢 `--add-opens`）。
