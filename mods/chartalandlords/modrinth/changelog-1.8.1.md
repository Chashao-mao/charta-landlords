# 1.8.1

## 新增

* **`/doudizhu rule airplane <3|6>` 与 `/doudizhu rule ai <0|1|2>`**：选项界面只有开关，精确数值在聊天栏设置（普通玩家即可用，不需要作弊）。
* **AI 强化搜索**：内置 AI 可以改用离线验证过的 PIMC 搜索策略（采样 + 完全信息极小极大，带节点预算与兜底校验）；与「AI 保守」正交，可组合成"保守风格 + 强搜索"。
* **农民配合**（`CooperativePolicy`）：上一手是队友打的就不压（除非能一把走完）；地主只剩 ≤ 2 张时必定挑一手最便宜的牌封杀。离线断言 `CoopHarnessMain` 6 条，挂在 `logic-check` 里。

## 修复

* **选项界面不再有滑块**：飞机带牌上限与 AI 难度原本是滑块，Charta 的滑块手柄会被画到槽位外面（玩家截图反馈）；两者都改成复选框。
* 选项提示里带括号的范围（如 `（2~6）`）改成显式换行，不再被自动折行切断。
* 内在一致性：把强搜索策略搬进引擎后，`game/engine/` 仍然零 Minecraft 依赖（`logic-check` 每次都会校验）。

## 验证

四条链全绿：`logic-check`（472 项断言）/ `compile-check -IncludeTests`（36 文件）/ `check_assets.py` / `gradle clean build runGameTestServer dist`（36 个 GameTest 全过）。