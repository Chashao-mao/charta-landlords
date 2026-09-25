# ChartaLandlords

[![Build](https://github.com/Chashao-mao/charta-landlords/actions/workflows/build.yml/badge.svg)](https://github.com/Chashao-mao/charta-landlords/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**斗地主 / Doudizhu (Fight the Landlord) for [Charta](https://modrinth.com/mod/charta)'s card tables.**
在 Charta 的牌桌上开一局斗地主：3 人局 54 张，4 人局 108 张两副牌。

* **抢地主（默认）或经典叫分**；明牌 / 加倍（各 ×2，叠满 ×4）；结算是逐对的，永远零和。
* **完整牌型与边界规则**：顺子 / 连对 / 飞机 / 四带二 / 炸弹 / 王炸，各家口径不一的地方都显式建模。
* **计分**：底分、炸弹与王炸翻倍、春天 / 反春天、跨局累计记分板。
* **记牌器 + 看牌界面**（`V`）：每家的手牌与底牌一页摊开，该公开的公开、不该公开的只显示张数。
* **三档 AI 难度**（保守 / 均衡 / 激进）、**手牌拖拽排序**、一段**原创**中国风 BGM。
* 世界里的那张牌桌不开界面也能看懂局面：扇形各就各位、轮到谁往桌心推、上一手偏到出牌者面前、地主面前一张扣牌。

## 需要

| | |
| --- | --- |
| Minecraft / 加载器 | 1.21.1 / **NeoForge** 21.1.248+ |
| 依赖 | **[Charta](https://modrinth.com/mod/charta) 1.2.5+**（必需，本体不打包，请一并安装） |
| 构建 | JDK 21 + `gradlew`（Charta 的编译期 jar 已随仓库提供） |

## 怎么玩

1. 摆一张**牌桌**与 3~4 把**游戏椅**（朝向牌桌）。
2. 自己坐一把；对手可以是真人，也可以把生物用牵引绳拉住后右键椅子让它坐上（会自动出牌）。
3. 放对应牌堆：**斗地主牌堆（54 张）** 打 3 人局，**斗地主双副牌堆（108 张）** 打 4 人局。
4. 右键牌桌选「斗地主」→ 决定地主（抢 / 叫分）→ 可选的明牌 / 加倍 → 出牌。
5. 点手牌选牌，按「出牌」（回车）、「不出」（空格）、「提示」；`V` 看牌；手牌可按住拖动排序。

界面左上角的 `?`（玩法）里有完整规则、全部牌型与计分说明，中英双语。

## 构建 / 验证 / 发布

```powershell
cd mods\chartalandlords
.\gradlew.bat build                # 编译打包 → build\libs\charta-landlords-<version>.jar
.\gradlew.bat runGameTestServer    # 34 个 GameTest（启动一次无头服务端）
.\gradlew.bat runClient            # 开发客户端
.\gradlew.bat dist                 # 发布产物 → ..\..\dist\（jar + 项目图标 + SHA256SUMS.txt）

cd ..\..                           # 纯逻辑校验：440 项断言，毫秒级，不需要 Minecraft
& tools\logic-check.ps1
python mods\chartalandlords\tools\check_assets.py   # 资源自检：牌背解码回像素逐字节对拍等
```

上传 Modrinth：见 [`mods/chartalandlords/README.md`](mods/chartalandlords/README.md) 的「发布到 Modrinth」
（`tools/upload_modrinth.ps1` 一条命令，先 `-DryRun` 核对）。

## 目录

| 路径 | 说明 |
| --- | --- |
| `mods/chartalandlords/` | 模组本体（NeoForge 工程：源码、资源、测试、工具、文档） |
| `mods/chartalandlords/libs/` | Charta 1.2.5 编译期 jar（MPL-2.0，见该目录 `README.md`） |
| `tools/` | 工作区级校验脚本（`logic-check.ps1` / `compile-check.ps1`） |
| `.github/workflows/` | CI：构建 + GameTest + 纯逻辑校验 |

## 改动前必读（硬规则）

* **协议端口**：`Payloads.PROTOCOL_VERSION` 是客户端/服务端的握手版本。任何 payload 字段或**含义**变化、
  `DoudizhuAction` 顺序变化都必须提升它（历史与理由见该类注释）。
* **纯逻辑层**：`game/engine/` 下不得出现任何 Minecraft / Charta 类型（`logic-check.ps1` 会失败）。
* **工具脚本 ASCII-only**：Windows PowerShell 5.1 按系统码页解码无 BOM 的 `.ps1`，非 ASCII 注释会被误码。
* **发布前四条验证链全绿**：`logic-check`、`compile-check -IncludeTests`、`check_assets.py`、
  `gradlew clean build runGameTestServer`。注意 `runGameTestServer` 在模组加载失败时**仍报 BUILD SUCCESSFUL**，
  通过与否要看日志里的 `All N required tests passed`。

## 许可与致谢

本模组 **MIT**（[`LICENSE`](LICENSE)）。基于 **[Charta](https://modrinth.com/mod/charta)**（作者 Luca Argolo，
MPL-2.0）开发：牌桌、卡牌、椅子与整个牌局注册表 API 都来自它。牌面素材来自 Charta 的标准牌堆。

背景音乐是本模组**原创**的中国风小调，**不是**腾讯那首《欢乐斗地主》（那首受版权保护）。
若你持有某首真实曲目的授权，放个资源包（`assets/chartalandlords/sounds/doudizhu_bgm.ogg` + `sounds.json`）即可替换。

详细文档（玩法细节、规则边界、AI 档位、界面与世界牌桌的取舍、发布流程）见
**[`mods/chartalandlords/README.md`](mods/chartalandlords/README.md)**；
更早的设计笔记与逐版本更新记录在 git 历史里（`git log`）。
