# 模组接入方式（MODS.md）

本项目对原版 Mindustry 的模组加载方式**零破坏**（全部走原版 `Mods.load()` 流程），
并在其上增加了一层**目录级热重载**。以下接入方式全部可用：

## 1. 目录 Mod（推荐，支持热重载）

把整个模组目录放进游戏数据目录的 `mods/` 下：

```
<data>/mods/<name>/            # 例如 devdata/mods/overlay/
├── mod.hjson                  # name / displayName / version / minGameVersion / main
├── content/items/*.json       # 内容文件（类型由所在目录决定）
├── content/blocks/*.json
├── sprites/*.png              # 贴图（自动打包为图集）
└── src/<pkg>/<Main>.class     # 可选：Java 主类（见下）
```

- 内容（HJSON）**改文件即热重载**：新增/删除/修改物品、方块、单位等都行，游戏内秒生效。
- Java 主类：在 `overlay/` 项目里改源码，`./gradlew :overlay:syncDev` 编译同步，
  或直接把编译好的 `.class` 覆盖进目录——watcher 检测到变化即重建类加载器并重新注册内容。
- 手动触发重载：游戏内 JS 控制台执行 `OverlayMods.reload();`。

### 多目录同时热重载

`-Doverlay.dirs=a,b,c` 逗号分隔多个目录名（默认 `overlay`），全部目录会被同时监控、
统一重载：

```bash
./gradlew :desktop:hotreloadRun -Doverlay.dirs=overlay,mymod
```

注意：热替换 Agent 会监控的是 `core/build/classes` 的**方法体**改动（见 PROGRESS.md），
对目录 Mod 的 Java 类没有结构限制（每次重载都新建类加载器）。

## 2. jar / zip Mod

把打包好的模组 `xxx.jar` / `xxx.zip` 拖进 `<data>/mods/`，启动时自动加载（原版行为）。

- 内容文件同样支持热重载吗？jar 是压缩包，watcher 只监控**目录**。
  解压成目录后即获得热重载（见方式 1）。
- 依赖打包限制与原版一致：Mindustry 需 `compileOnly`，不要打进 jar。

## 3. JS 脚本 Mod（scripts/main.js）

在目录 Mod 根部放 `scripts/main.js`，启动时由原版脚本引擎执行：

```js
// 脚本内可用完整 Mindustry API
const myOre = new Item("my-ore", Color.valueOf("22c07a"));
```

- 脚本改动**不支持热重载**（原版只在启动时加载脚本）；需要重启。
- 适用场景：不熟 Java、快速原型、服务端小功能。

## 4. Plugin（服务端插件）

在目录 Mod 根部放 `src/<pkg>/<Main>.class`，主类继承 `mindustry.plugin.Plugin`，
并给 `mod.hjson` 写 `main: "<pkg>.<Main>"`。启动时作为插件加载（原版行为）。

- 仅服务端使用；`Plugin` 无内容，只有事件/命令逻辑。
- 热重载：代码结构不变时 Agent 可热替换（见 PROGRESS.md 限制），
  或重启服务端。

## 5. Workshop 模组

原版支持从创意工坊订阅下载；本仓库 fork 未改动该链路。
创意工坊模组默认**不参与**目录热重载（目录在平台缓存区），如需热重载请手动复制为目录 Mod。

## 对比速查

| 方式 | 内容热重载 | 代码热重载 | 适用 |
|---|---|---|---|
| 目录 Mod (overlay) | ✅ 秒级 | ✅ 结构不变即可 | 开发迭代、演示 |
| jar/zip Mod | 需解压 | 需解压 | 分发 |
| JS 脚本 | ❌ 重启 | ❌ 重启 | 快速原型 |
| Plugin | — | ✅ 结构不变即可 | 服务端功能 |