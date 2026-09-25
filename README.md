# ChartaLandlords

[![Build](https://github.com/Chashao-mao/charta-landlords/actions/workflows/build.yml/badge.svg)](https://github.com/Chashao-mao/charta-landlords/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**斗地主**（以及后续更多中国牌类玩法）在 [Charta](https://modrinth.com/mod/charta) 牌桌上的实现。
当前内容：牌局本体 **ChartaLandlords**（NeoForge 1.21.1 / MC 1.21.1，3~4 人局），
含抢地主与叫分、明牌 / 加倍、完整牌型与边界规则、计分与记分板、记牌器与看牌界面、三档 AI 难度、
以及一段现场合成的中国风 BGM。

**新克隆怎么跑起来**（需要 JDK 21；Charta 1.2.5 已经放在 `mods/chartalandlords/libs/`，无需额外下载）：

```powershell
cd mods\chartalandlords
.\gradlew.bat build                # 编译打包 → build\libs\charta-landlords-<version>.jar
.\gradlew.bat runGameTestServer    # 34 个 GameTest（会启动一次无头服务端）
.\gradlew.bat runClient            # 开发客户端

cd ..\..                           # 纯逻辑校验（440 项断言，毫秒级，不需要 Minecraft）
& tools\logic-check.ps1
```

玩之前需要 Charta（本体不打包）；装进整合包时把本模组 jar 与 Charta jar 一起放进 `mods`。
许可：本模组 **MIT**（根目录 `LICENSE`）；`mods/chartalandlords/libs/` 里的 Charta 是 **MPL-2.0** 第三方文件，
来源与校验见该目录的 `README.md`。

细节（玩法、界面、规则、AI 档位、牌桌摆位、发布流程）见 **[`mods/chartalandlords/README.md`](mods/chartalandlords/README.md)**；
CI 在根目录 `.github/workflows/build.yml`（构建 + GameTest + 纯逻辑校验）。

---

以下是与本工作区其它模组共用的约定：

| 目录 | 说明 |
| --- | --- |
| `mods/chartalandlords/` | **ChartaLandlords** 模组（NeoForge 1.21.1）：当前实现斗地主牌局。本工作区的第一个模组。 |
| `mods/chartalandlords/libs/` | 编译期依赖：Charta 1.2.5 官方 jar（MPL-2.0，见该目录 `README.md`）。会被提交，好让新克隆直接能编译。 |
| `.github/workflows/` | CI：构建 + GameTest + 纯逻辑校验（GitHub 只读仓库根的 `.github`）。 |
| `tools/` | 构建 / 校验脚本（`compile-check.ps1` / `logic-check.ps1`）与预解压的 Gradle 发行包（不进 git）。 |
| `ref/charta-main/` | 上游 Charta **1.2.5** 源码快照，仅作 API 参照（不进 git；`git clone` 上游即可）。 |
| `dist/` | 本地发布产物（jar + 项目图标 + `SHA256SUMS.txt`，由 `gradlew dist` 生成；不进 git，正式产物在 Modrinth）。 |
| `.gradle-home/` | Gradle + Minecraft 依赖缓存（可删；不进 git）。 |

---

## 1. 规范化约定（本工作区所有模组共用）

### 1.1 命名分层

模组身份分四层，各管一件事，任何一处都不重复写死字面量：

| 层 | ChartaLandlords 的取值 | 用在哪 | 能不能改 |
| --- | --- | --- | --- |
| **模组名**（项目名） | `ChartaLandlords` | `neoforge.mods.toml` 的 `displayName`、`gradle.properties` 的 `mod_name`、`settings.gradle` 的 `rootProject.name` | 随便改，玩家在模组列表里看到的就是它 |
| **产物名** | `charta-landlords` | `gradle.properties` 的 `mod_archive_name` → `build/libs` 与 `dist` 里的 jar 文件名（按文件系统 / Maven 惯例用全小写连字符） | 随便改 |
| **资源命名空间** = `mod_id` | `chartalandlords` | 注册表键（`charta:game_type/chartalandlords:doudizhu`、`charta:rank/chartalandlords:small_joker`）、网络包 id（`chartalandlords:game_action`、`chartalandlords:card_select`）、数据包路径（`data/chartalandlords/**`）、资源路径（`assets/chartalandlords/**`）、语言键前缀（`chartalandlords.*`） | 与模组名同源；**再改会让已存档的牌堆物品、进行中的牌局、语言键全部失效** |
| **组织命名空间** | `chartalandlords` | Maven `group`；Java 包根用「组织 + 模块名」= `chartalandlords.doudizhu` | 随项目走，不随玩法走 |
| **牌局路径** | `doudizhu` | `registry.GameTypes.LANDLORDS_PATH` → 完整键 `chartalandlords:doudizhu`；牌桌图标与语言键由它派生（`…/textures/gui/game/doudizhu.png`、`chartalandlords.doudizhu`） | 保持拼音原名，与 Charta 的「命名空间 = 模组、路径 = 牌局」一致 |

代码里的唯一来源：`Doudizhu.MOD_ID` / `Doudizhu.GROUP_ID` / `registry.GameTypes.LANDLORDS_PATH` /
`Payloads.PROTOCOL_VERSION`。构建侧的对应项：`gradle.properties` 的
`mod_id` / `mod_name` / `mod_archive_name` / `mod_group_id`。

**注意 Java 包不与 `mod_id` 绑死**：如果按 `group.mod_id` 推，就会得到
`chartalandlords.chartalandlords`。所以包名规则是 **`组织.模块名`** = `chartalandlords.doudizhu`，
模块名指的是「这个模组做什么」（斗地主），与资源命名空间解耦。

**包名规范**：`chartalandlords.<模块名>`，全小写，不含生成器/工具名。历史遗留的
`com.codex.doudizhu` 已全部迁移（包目录、`package`、`import`、`mod_group_id` 同步完成）。

**注册键规范**：沿用 Charta 的「命名空间 = 模组、路径 = 牌局」，与上游的
`charta:crazy_eights` / `charta:solitaire` 对齐。牌桌图标与语言键由注册键派生：
`<mod_id>:textures/gui/game/<path>.png` 与 `<mod_id>.<path>`。

**网络包 id 规范**：`<mod_id>:<snake_case 描述>`，对齐上游的 `charta:card_play` 等命名。

### 1.2 协议端口 / protocol port

NeoForge 通过 `event.registrar(version).version` 做握手校验：两端版本字符串不一致时，本模组的
自定义包会被**直接拒绝**，而不是静默按错误的偏移解析。因此：

- 唯一来源是 `Payloads.PROTOCOL_VERSION`（当前 `"8"`，跟随 MC 大版本起步，与 Charta 本体同风格）。
- **任何 payload 的字段增删、含义变化，或 `DoudizhuAction` 枚举的顺序变化，都必须提升该版本号。**
  已经发生过七次：界面绘制顺序重排改变了 `SelectCardPayload.slotId` 指向的卡槽（`"1"` → `"2"`）；
  资源命名空间改名让注册身份全变（`"2"` → `"3"`）；
  1.3.0 为了插入「明牌 / 加倍」阶段而重排了 `DoudizhuGame.Phase` 的枚举顺序
  （`DATA_PHASE` 同步的就是它的 `ordinal()`），并追加了三个动作（`"3"` → `"4"`）；
  1.5.0 明牌与加倍改成可叠加的两个开关，`DECLARE_PASS` 的含义由「跳过」变成「确认」（`"4"` → `"5"`）；
  1.6.0 新增 C2S 包 `card_move`（拖拽排序）与动作 `TIDY_HAND`（`"5"` → `"6"`）；
  1.7.0 明牌展示行注册在所有牌带之前，既有卡槽序号整体后移（`"6"` → `"7"`）；
  1.8.0 底牌不再占一条牌带、明牌展示槽改成屏幕外的数据槽，两处都改变了卡槽编号
  （`"7"` → `"8"`；这一版中途加过的 S2C 包 `table_status` 随后又连同浮空小字一起删掉了，
  协议端口没有必要再为它单独提升）。
- **「只加一个包」也要提升版本号**，这一点反直觉但很重要：包本身是追加的、旧客户端不会解析错，
  可<b>反过来不成立</b>——新客户端对着没有注册该包的旧服务端，一用新功能就会发出对方不认识的
  payload id。客户端比服务端「多一个发送能力」同样是两端不一致，只有握手能挡住它。
- 动作序号即 `DoudizhuAction.ordinal()`，所以新动作只允许**追加**在枚举末尾
  （见 `DoudizhuAction` 的类注释），并由 GameTest 逐项断言 id 不变。

### 1.3 开发端口段

每个模组占用一段固定端口，方便同时开多个 `runServer` / `runClient`：

| 模组 | server-port | query.port | rcon.port |
| --- | --- | --- | --- |
| doudizhu | **25580** | 25580 | 25585 |

- 端口值是 `mods/chartalandlords/gradle.properties` 里的 `dev_server_port` / `dev_query_port` /
  `dev_rcon_port`。
- `gradlew standardizeDevServer` 会把它们（连同 `online-mode=false`、`enable-rcon=false`、
  `motd`）写回 `run/server.properties` 与 `run-gametest/server.properties`；
  `runServer` / `runGameTestServer` 会自动依赖该任务。
- **GameTest 有独立的运行目录 `run-gametest/`**：GameTestServer 会把 `level-name` 指向自己的
  测试世界，共用 `run/` 会覆盖开发存档。

---

## 2. 构建与验证

### 2.1 Gradle（完整构建 / GameTest / 发布产物）

```powershell
cd mods\chartalandlords
.\gradlew.bat build              # 编译与打包 → build\libs\charta-landlords-<version>.jar
.\gradlew.bat dist               # 发布：jar + Modrinth 图标复制到工作区根 dist\，并维护 SHA256SUMS.txt
.\gradlew.bat runGameTestServer  # 跑 DoudizhuGameTests（牌型、比较、发牌、完整对局、超时兜底）
.\gradlew.bat runClient          # 开发客户端
```

> **发布产物里不含 GameTest 类**：测试源码按 MDK 惯例放在 `src/main/java/.../test/`（开发环境要跑），
> 但 `jar` 任务里排除了 `chartalandlords/doudizhu/test/**`——正式安装的客户端/服务端不会注册它们。
> 开发运行用的是 `build/classes`，所以不影响 `runGameTestServer`。
>
> **`runGameTestServer` 在模组加载失败时仍然报 `BUILD SUCCESSFUL`**（FML 崩溃后 dev-launch 的退出码仍是 0）。
> 判定通过与否必须看日志里的 `All N required tests passed`，不能只看构建结果。

> `dist` 是**独立任务**，不挂在 `build` 上——`build` 只负责编译打包，要发布时显式触发。
> 工作区里每个模组都往同一个 `dist/` 写，`SHA256SUMS.txt` 按文件名**逐行合并**（不是整体覆盖），
> 所以多个模组可以共存；格式与 `sha256sum -c` 兼容。
>
> ```powershell
> # 校验发布产物
> cd G:\newmod\ChartaLandlords\dist
> (Get-Content SHA256SUMS.txt) -replace '\s+.*$', '' | ForEach-Object { $_ }   # 或直接用 sha256sum -c
> ```

> Gradle wrapper 固定 9.2.1，首次运行需要联网下载发行包与 NeoForge/Minecraft 依赖；
> wrapper 需要写 `GRADLE_USER_HOME`（默认 `%USERPROFILE%\.gradle`）。在受限于只允许写工作区的
> 沙箱里，请改用：
>
> ```powershell
> $env:GRADLE_USER_HOME = 'G:\newmod\ChartaLandlords\.gradle-home'
> & tools\gradle-9.2.1\bin\gradle.bat --console=plain --no-watch-fs runGameTestServer
> ```
>
> `tools\gradle-9.2.1\` 是预解压好的 Gradle 发行包（来自 `tools\gradle-9.2.1-bin.zip`）；
> `.gradle-home\` 是依赖缓存（≈1.3 GB），删掉后下次构建会重新下载。

### 2.2 离线编译校验（无需 Gradle / 无需联网）

```powershell
& tools\compile-check.ps1                       # 主源码
& tools\compile-check.ps1 -IncludeTests         # 含 GameTest 源码
& tools\compile-check.ps1 -OutDir <唯一目录>     # 并发执行时用不同输出目录
```

它用 `build\moddev\artifacts\neoforge-*-merged.jar`（Minecraft + NeoForge）、`mods\chartalandlords\libs` 下的
Charta jar、以及本机 Gradle 模块缓存里的其余依赖直接调用 `javac`。只能发现类型/签名错误，
游戏逻辑正确性由 GameTest 覆盖。

### 2.3 纯逻辑校验（无需 Minecraft）

```powershell
& tools\logic-check.ps1
```

**纯逻辑层是目录级的约定，不是启发式**：`chartalandlords/doudizhu/game/engine/` 下的每一个文件都
不得引用任何 Minecraft / Charta 类型，只吃 `int[]` 点数数组
（3..10 = 3..10，J/Q/K/A = 11/12/13/14，2 = 15，小王 = 16，大王 = 17）。
`game/` 包里的 `DoudizhuRules` / `DoudizhuAi` / `DoudizhuValues` 只是 `Card ↔ 点数` 适配层。

因此可以用普通 `javac` + `java` 在类路径上完全不含游戏的情况下，穷举校验牌型判定、比较关系、
候选生成、提示轮换、计分、倍数与 AI 决策（当前 171 + 20 + 249 = **440 项断言全绿**，
含对随机手牌的 fuzz，以及「抢地主 / 明牌 / 加倍」的 4536 组倍数零和穷举）。

断言程序在 `mods/chartalandlords/tools/logic-harness/`（`HarnessMain` 规则 / `AiHarnessMain` AI /
`PlayHarnessMain` 计分·倍数·难度档位·提示循环·规则开关）。脚本会先断言 `game/engine/` 里没有任何游戏类型 import——
**往引擎里混进一张 `Card` 会立刻失败**，这正是保持这层可离线测试的护栏。

### 2.4 资源自检（无需 Minecraft，无需 Gradle）

```powershell
cd mods\chartalandlords
python tools\check_assets.py
```

`gen_assets.py` 画资源，`check_assets.py` 证明**确实画进去了**：Charta 的 `.mccard/.mcsuit` 是私有的
「每像素一字节（alpha 档位 << 6 | 调色板索引）+ gzip」格式，位偏移错了游戏里就是一张花牌，
看文件大小完全发现不了。它把牌背/花色解码回像素与设计图逐字节对拍，再校验两副牌堆 JSON 的
张数/字段/「54 种各 2 张」，以及三张图标（选桌 70、模组 logo 140、Modrinth 560）的尺寸与同源性。
发布前必跑。

> 工具脚本一律保持 **ASCII-only**：Windows PowerShell 5.1 在没有 BOM 时按系统 ANSI 码页解码 `.ps1`，
> 中文注释会被误码，而误码字节序列甚至能吞掉后面的换行，把下一条语句并进注释里（真的踩过一次）。

### 2.5 资源再生成

```powershell
cd mods\chartalandlords
python tools\gen_assets.py
```

生成 `data/chartalandlords/decks/*.json`（54 / 108 张）、`.mccard`/`.mcsuit` 私有格式图片、牌桌图标、
模组 logo、项目图标（`src/main/resources/icon.png`，700×700，对齐 Charta 的 `common/src/main/resources/icon.png`）
与 GameTest 空结构。资源包元数据 `pack.mcmeta` 放在 `src/main/templates/`，`${mod_name}` 由 gradle 展开。

### 2.6 发布到 Modrinth

```powershell
cd mods\chartalandlords
$env:GRADLE_USER_HOME = 'G:\newmod\ChartaLandlords\.gradle-home'
& ..\..\tools\gradle-9.2.1\bin\gradle.bat --console=plain build runGameTestServer dist

# 干跑（不联网、不需要 token）：确认项目字段、版本字段、文件路径都对
powershell -NoProfile -ExecutionPolicy Bypass -File tools\upload_modrinth.ps1 -DryRun

# 真上传（token 勾 PROJECT_CREATE + PROJECT_WRITE + VERSION_CREATE）
$env:MODRINTH_TOKEN = 'mrp_...'
powershell -NoProfile -ExecutionPolicy Bypass -File tools\upload_modrinth.ps1
```

上传 `dist\charta-landlords-<version>.jar` 与项目图标 `dist\charta-landlords-icon.png`（700×700）；
项目页文案在 `mods/chartalandlords/modrinth/`（`summary.txt` / `description.md` / `changelog-*.md` / `upload.json`）。
项目字段（slug、分类、**MIT** 许可、`1.21.1` + NeoForge、**required** 依赖 Charta ≥1.2.5）
与发布前检查清单一律见 `mods/chartalandlords/README.md` 的「发布到 Modrinth」一节——**那张清单是唯一出处**，
避免两处写法漂移。

---

## 3. 目录速查（`mods/chartalandlords`）

```
src/main/java/chartalandlords/doudizhu/
  Doudizhu.java / DoudizhuClient.java    模组入口（公共 / 客户端）
  registry/                              注册表：JokerRanks / GameTypes / Menus / Payloads
  game/
    engine/                              ★ 纯逻辑层：不得引用任何 Minecraft / Charta 类型
      RuleEngine.java  RuleOptions.java  牌型判定 / 比较 / 候选生成 / 提示
      HandShape.java                     手牌形态启发式（控场分、死单、拆牌、最少手数）
      AiPolicy.java                      叫分 / 抢地主 / 首出 / 跟牌策略
      AiContext.java                     决策上下文（含记牌推断）
      AiProfile.java                     AI 难度档位（叫分 / 抢 / 明牌门槛、炸弹与封杀触发线、各种惩罚权重）
      DoudizhuScore.java                 计分（底分 / 局倍数 / 逐座位个人倍数 / 春天 / 反春天）
      Combo.java  ComboType.java         牌型描述
    DoudizhuRules.java                   Card ↔ 点数的适配层
    DoudizhuAi.java                      AI 的 Card 适配层
    DoudizhuValues.java                  点数体系与展示顺序
    DoudizhuGame.java  DoudizhuMenu.java 牌局状态机与界面容器（几何常量集中在 Menu）
    DoudizhuAction.java                  网络动作枚举（序号即协议）
  network/                               三个 C2S 包（选牌 / 动作 / 手牌排序）
  client/DoudizhuScreen.java             牌局界面（含拖拽指示线）
  client/DoudizhuHandsScreen.java        「看牌」界面：每家的手牌与底牌（独立 Screen）
  client/DoudizhuBgm.java                内置 BGM（音符盒现场合成 + 资源包 .ogg 挂钩）
  test/DoudizhuGameTests.java            GameTest 自动化验收
src/main/templates/META-INF/neoforge.mods.toml
src/main/resources/{assets,data}/chartalandlords/**
tools/gen_assets.py
tools/logic-harness/                     ★ 纯逻辑校验断言程序（package game.engine，不参与打包）
```

> **为什么不把 `DoudizhuGame` / `DoudizhuMenu` 这些前缀也去掉**：它们在 `game` 包里确实冗余，
> 但去掉后会与 Charta 的 `Game` / `Menu` / `Rank` 等类型重名，每处都要写全限定名。
> 冗余前缀在这里是更清晰的代价更低的选择。

---

## 4. 上游参考

| 来源 | 借鉴内容 |
| --- | --- |
| [kwai/DouZero](https://github.com/kwai/DouZero) | 按张数驱动的牌型判定、逐类型合法着法生成、顺子/连对/飞机的最小长度常量 |
| [datamllab/rlcard](https://github.com/datamllab/rlcard) | `get_gt_cards()` 式「提示」管线、带牌选择约束（不能一张不带、不能带整炸、不能双王同带）、`get_landlord_score` 叫分基线 |
| [esrrhs/doudizhu_ai](https://github.com/esrrhs/doudizhu_ai) | `HandShape` 启发式：死单计数、拆牌判定、控场牌被当带牌、最小安全压制、安全首出排序、超压惩罚 |
| [Pagat "Dou Dizhu"](https://www.pagat.com/climbing/doudizhu.html) | 牌型与比较的权威描述；四带二的比较/被压关系；飞机翅膀规则；四人（两副牌）变体差异 |
| [联众 斗地主规则](https://www.ourgame.com/game/game-intro-new/h2g/lord_002.html) | 中文规则措辞与被压关系（「比较相同张数最多的牌点数」） |
| [onestraw/doudizhu](https://github.com/onestraw/doudizhu) | 「同一手牌可能有多种解读」的哈希校验思路，以及「两个王不算对子」等边界 |

---

## 5. 更新记录

### chartalandlords 1.8.0

- **修掉「点第二行的牌、第一行的牌进了出牌区」**：扇形算术在边缘返回 `-1`，调用方退回用
  「框架记录的悬停牌号」，而那个牌号可能来自**另一行**，拼出跨槽的 (slotId, cardId)。
  现在落在行内一定返回合法下标，且去掉跨槽兜底。
- **修掉「点上一手和底牌重叠处会拿起上一手的牌」**：上一手的展示行用了默认可写的 `GameSlot`，
  而默认 `canRemoveCard` 是 `isEmpty()==false`，框架的「拿起牌」流程真能把牌拎起来。
  所有展示槽改成统一只读，点在自绘牌带上一律吃掉事件。
- **牌桌摆位**：各座位的手牌扇形整局保留，并且**待在自己的牌槽里**（`FAN_PUSH_OUT` 38 → 0：
  推出去之后只有「轮到谁」那一家看着像在槽里，其余三家都偏到桌外）；**轮到谁就把谁的扇形往桌心推
  0.19 格**（44 → 30），换人时上一家自动退回；上一手牌**挪到出牌者面前**（`PLAY_DRIFT` 0.26 → 0.55）；
  **桌面上不再摊开底牌**，改成地主面前**一张横放的扣牌**当地主标记（真正的底牌仍在界面的底牌预览
  /「看牌」页/聊天播报里），标记的落点由出牌区落点推出来，于是「底牌和出牌重合了」这种撞车
  在结构上不可能发生（GameTest 断言两者间隔 ≥60 单位、桌面恰好一张扣牌）。
- **世界里的信息不再用文字**：中途试过的「浮空小字」方案（连同一个 `table_status` S2C 包、
  一个渲染器与客户端缓存）**连同 `registerExtraRenderer` 钩子一起删除**——1 格大的牌桌上，
  一张牌才 0.156 格宽，任何悬浮字都比牌还大、从哪个角度看都是糊的。改用牌的位置表达局面。
- **新增「看牌」界面**：记牌器标题行右侧的 `看牌` 徽章（或快捷键 `V`）打开一页，
  把自己与已明牌座位的手牌画成正面、其余座位按真实张数画牌背，底牌也塞进同一页。
  它是**独立 Screen**（和 Charta 的牌堆 / 历史界面同一套做法），
  第一版自绘一层盖在牌局界面上会被框架的卡牌与按钮压住；换成独立界面后牌局界面根本不渲染。
  没有新增任何协议（数据本来就在菜单里同步）。
- **明牌展示槽改成屏幕外的数据槽**（不再画成一条糊成白条的牌带）——看牌界面读的就是它。
- **拖拽手感**：拖动时那张牌跟着鼠标走（借用框架「手上牌」那条最上层绘制，自己画会被手牌盖住）
  + 手牌带上画金色箭头与插入竖线（与落点共用同一支扇形算法），拖拽阈值 4px → 6px。
- **底牌在界面里改成右上角小牌预览**，把 52.5px 的整行高度还给牌桌主界面。
- **局外（世界牌桌）再加一层可读性**：上一手牌从桌心**往出牌者那一侧偏 26%**，并且**按出牌者的朝向
  摆放**（不同人出的牌朝向不同、正面朝出牌者；收桌后回中朝向也回默认）；扇形不再往外推、
  底牌再往桌子中间挪；并把扇形 / 底牌 / 轮到谁这三处摆位统一改用一个**固定的桌心常量**——
  它们原先拿会动的 `playArea` 当中心，上一手牌一偏，轮到同一个人的位移就跟着变
  （GameTest 抓到「推出去 44、退回 42.66」）。
- **模组图标**沿用游戏选择界面那张图（同一个 `game_icon()` 画出来，jar 根目录 140×140 给模组列表），
  并关掉 `logoBlur` 让小尺寸下用最近邻缩放。
- **协议端口 `"7"` → `"8"`**（底牌带消失 → 小预览、明牌行 → 屏幕外数据槽，卡槽编号整体变化）。
- GameTest 31 → **34 个**（新增「轮到谁就推谁的扇形」「扇形命中逐张往返一致」
  「上一手牌偏向出牌者那一侧 + 朝向抄出牌者的扇形」，删除随浮空小字作废的「局面摘要与客户端缓存」）。

### chartalandlords 1.7.0

- **修掉「明牌别人看不到牌」**：牌桌上那圈牌本来就会翻成正面，但牌局界面占了屏幕中央一大块，
  别人的牌扇基本被面板挡住，而界面里原本没有任何展示别人手牌的地方。
  现在新增**明牌展示行**（每个座位一行，只有明牌过的座位有牌；空槽框架不画，
  所以没人明牌时布局不变），并把整手牌同时发一条到聊天栏。
- **协议端口 `"6"` → `"7"`**：明牌展示行位置最高、必须最先注册，所有既有卡槽序号整体后移，
  `SelectCardPayload.slotId` 的含义随之改变。
- GameTest 30 → **31 个**。

### chartalandlords 1.6.0

- **手牌手动排序**：按住手牌拖到别的位置即可调整顺序；拖过之后该座位切到**手动牌序**，
  出牌与选牌都不再重排整只手牌（位置不会每出一手就跳一次），`整理` 按钮回到默认点数顺序。
  同时修掉「地主新到手的牌无法调整牌序」：拿底牌时手动牌序会被保留，三张新牌接在末尾并提示。
- **出牌手感**：非法出牌不再清空选牌（可以拿掉一张直接重试）；新增 `回车` 出牌 / `空格` 不出；
  撤回改为按点数插回原位，出牌前后手牌位置基本不动。
- **显示**：新增逐座位的**表态牌子**（`抢` / `不抢` / `N分` / `不叫`），抢地主状态行补上
  「当前地主候选」；座位标记位扩到 5 位并新增逐座位叫分数据槽。
- **协议端口 `"5"` → `"6"`**：新增纯追加的 C2S 包 `chartalandlords:card_move` 与动作 `TIDY_HAND`。
  包格式没变、旧客户端不会解析错，但新客户端对着旧服务端一拖牌会发出对方不认识的 payload id，
  所以仍属两端不一致，必须靠握手挡住（见 §1.2）。
- GameTest 26 → **30 个**。

### chartalandlords 1.5.0

- **明牌与加倍可以同时选**（个人倍数 ×4）。这一阶段从「一人一次二选一」改成**两个独立开关 + 确认**：
  开关按下即生效（确认前就能看到自己押了多少），明牌**单向不可撤销**（牌已经摊在别人眼前），
  加倍可以反复切换；座位盒叠满时显示 `×4` 徽章。
- **协议端口 `"4"` → `"5"`**：动作序号与包格式都没变，变的是 `DECLARE_PASS` 的语义
  （「跳过」→「确认并结束」），按工作区规则语义变化同样提升版本号（见 §1.2）。
- GameTest 25 → **26 个**（新增「叠满的个人倍数进结算」）。

### chartalandlords 1.4.0

- **抢地主改为默认开启**（底分固定 1，一圈「抢 / 不抢」，最后一个抢的人当地主）。关掉该选项即回到
  经典 1/2/3 分叫分。选项与动作编号都没变，所以**协议端口仍是 `"4"`**。
- **AI 抢地主决策独立成一条策略**：门槛随「已经有多少人抢过」抬高、自己是最后一个决定的人时降低，
  不再是拿叫分门槛顺手判断。
- **难度档位多出两条真正的差异轴**：抢地主意愿、明牌 / 加倍意愿（加上原有叫分门槛、炸弹触发线，
  以及新增的「开始封杀」触发线，共五个维度）。
- **修掉一个标定错误**：AI 的门槛以前写绝对控场分，而控场分与手牌张数强相关（3 人局 17 张均值 2、
  4 人局 25 张均值 12），导致 4 人局几乎所有手牌都够叫 3 分、AI 也几乎人人明牌。
  改成**相对基线** `2 + (手牌数 − 17) × 5 / 4`（20 万手随机牌标定，误差 ≤2 分）。
- **AI 出牌两条新逻辑**：对手快走完时的**封杀**（跟牌改出压不动的牌、首出优先出对手一只手接不住的牌型），
  以及**残局保形**（手牌 ≤10 张时顺带评估「走完之后还剩几手」，不再为跟一张小牌拆掉顺子）。
- 纯逻辑断言 374 → **440**，GameTest 24 → **25**。

### chartalandlords 1.3.0

- 新增**抢地主模式**（牌局选项，默认关）：叫分换成一圈「抢 / 不抢」，底分固定 1，
  每次抢翻一倍，最后一个抢的人当地主；复用同一个 `bid(player, score)` 入口，因此不需要新增网络动作。
- 新增**明牌 / 加倍阶段**（牌局选项，默认开）：地主确定后 10 秒内每人各选一次
  `明牌`（公开手牌 + 个人倍数 ×2）/ `加倍`（个人倍数 ×2）/ `跳过`，超时自动跳过。
  明牌的手牌同时被算作「已见」，所以记牌器与 AI 的记牌推断会跟着更新。
- 计分升级为「**局倍数 × 逐对个人倍数**」：地主与每个农民逐对结算，
  任意人数 / 任意个人倍数组合下仍然严格零和（新增 4536 组穷举断言）。
- 新增 **AI 难度档位**（0 保守 / 1 均衡 / 2 激进）：难度是一张权重表 `AiProfile`，
  传进同一个 `AiPolicy`；只调权重，规则与牌型判定一行没动（由 GameTest 断言换档不改 `RuleOptions`）。
- 新增**斗地主 BGM**：客户端用原版音符盒音效现场合成的一段**原创** 150 BPM 中国风小调
  （竹笛主旋律 + 琵琶分解和弦 + 低音 + 大鼓/板/小锣），走 `SoundSource.MUSIC` 跟随原版音乐滑条，
  记牌器标题行的 `♪ 音乐 开/关` 徽章点击切换；资源包提供 `chartalandlords:doudizhu_bgm`
  时自动改放真实音频。**不含**「欢乐斗地主」原曲——那是腾讯的原创音乐作品。
- **选桌图标与模组 logo 重绘**：中国红毡面 + 双金边 + `DOUDIZHU` 点阵标题 + 金冠 + 五张牌扇
  （`♠3 / 小王 / 大王 / ♠2 / ♥A`，大王在最上层）；logo 由 128×128 改为 140×140，
  正好是 70 单位设计网格的 2 倍，笔画是干净的整数倍像素。
- 协议端口 **`"3"` → `"4"`**：`DoudizhuGame.Phase` 插入 `DECLARING` 导致枚举序号重排，
  且 `DoudizhuAction` 追加 `REVEAL_HAND` / `DOUBLE_UP` / `DECLARE_PASS`（id 8/9/10）。
- 产物名与资源命名空间不变；`DoudizhuScore` / `DoudizhuAi` / `AiPolicy` 的旧签名全部保留并保持等价。
- 纯逻辑断言 191 → **374**，GameTest 用例 17 → **24**。

### chartalandlords 1.2.0

- 包名 / Maven group / 注册与网络命名空间规范化（见 §1.1），`ActionPayload` → `GameActionPayload`，
  payload id `doudizhu:action` → `doudizhu:game_action`、`doudizhu:select_card` → `doudizhu:card_select`。
- 抽出协议版本常量 `ModPayloads.PROTOCOL_VERSION`，并写明提升规则（见 §1.2）。
- 固定开发端口段（见 §1.3），新增 `standardizeDevServer` 任务。
- 规则引擎拆分为**无 Minecraft 依赖**的纯逻辑层（`RuleEngine` / `HandShape` / `AiPolicy` / `RuleOptions`），
  便于离线穷举校验与后续复用。
- 修正牌型判定与比较的边界问题，补齐规则可选开关（四带二两单可为对、大小王不可同带、
  飞机翅膀不可单对混用、各牌型长度上限、允许拆炸凑牌型），并把开关接到 Charta 原生的牌局选项界面。
- 新增**计分**：底分 = 叫分，炸弹/王炸与春天/反春天各翻倍，地主与每个农民逐家零和结算。
- 新增**提示循环**（反复按提示轮换候选）与**记牌器**（显示每个点数还剩几张没露面；
  数据不变量由 GameTest 逐局、逐座位、逐点数与独立算出的期望值比对）。
- 界面视觉重建：桌沿 + 4 段微渐变毡面、统一的「内凹面板」原语、呼吸式行动方描边、
  座位累计分数始终可见、状态行显示底分/炸弹/倍数与提示进度；记牌器为带标题条的自适应分组框。
- AI 重写：最少手数分解 + 控场评估 + 农民协作 + 记牌推断 + 炸弹纪律，与「提示」共用同一套候选生成。
- 界面重做：修正手牌两行的叠放方向、消除状态栏与 Charta 自带按钮的冲突、修掉长牌型展示截断、
  重做座位条、新增当前选择反馈与撤回按钮、所有按钮加 tooltip。
  **绘制方向统一为「屏幕位置越靠下越晚绘制」**，于是底牌不再压住上一手、出牌区完整可见、
  多行牌型逐行露出左上角点数；该不变量由 GameTest 对全部槽位断言。

各条改动的详细说明见 [`mods/chartalandlords/README.md`](mods/chartalandlords/README.md) 的更新记录。
