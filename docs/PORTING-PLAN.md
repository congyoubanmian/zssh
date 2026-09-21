# ZCode → ZSsh 移植评审计划（2026-09 调研）

> 来源：对 `zai-org/ZCode`（C:\Users\xiaola\zcode-src）的三轮源码调研（协议面 / UI 面 / CLI+服务面）+ 一轮对照本仓库的可行性评审。
> 与 docs/ROADMAP.md 的关系：ROADMAP 是「协议欠账清单」，本文是「移植候选评审」，重复项已合并标注。

## 一、优先级

### P0（手机场景价值 × 投入产出比最高，6 项）

| 项 | 内容 | 落点 | 量 |
|---|---|---|---|
| A13 | 权限/问答**多端互踢**：桌面端已处理的弹窗手机自动消失（permission.resolved / userInput.resolved 按 toolCallId 关联） | ChatScreen handleEvent 加两 case | S |
| D2 | 问答式输入 UI（ROADMAP#3；AskUserQuestion/ExitPlanMode 走 interaction/requestUserInput，多问题分页+自动决议倒计时纯渲染） | AgentSession 反向请求分支 + ChatScreen 新弹窗 | M |
| B10 | 权限弹窗**内嵌真实工具预览**（编辑请求显示 diff、命令请求显示终端命令，options 按 允许一次→始终允许→拒绝一次→始终拒绝 排序，拒绝可附文字反馈） | ChatScreen 权限弹窗重写 | M |
| A7 | **automation 定时任务管理**（list/create/update/delete；协议支持 cron 表达不了的「每 N 分钟/小时/天」；手机是天然监控端） | AgentSession 加 4 方法 + 新 AutomationScreen + 工具页入口 | M |
| C1 | **套餐额度页**（HTTPS GET {业务域}/api/monitor/usage/quota/limit，5小时窗=unit3,number5 / 每周=unit6 / 工具=TIME_LIMIT / MCP=mcpQuota；percentage 是已用需反转） | 新 QuotaService（复用 LoginService httpJson）+ 我的/工具页入口 | M |
| A9 | 上下文用量进度条（create/resume 响应快照自带 projection.contextUsed/contextWindow，纯渲染；与 ROADMAP#6 compact 闭环） | ChatScreen 顶栏 | S |

### P1
A17 Todo 卡片（快照自带 todos/todoGroups，S/M）· A10「供应商重试中」状态条（复刻桌面 apiRetry 派生，S/M）· A12 权限 escalate/modify+规则写回（并入 ROADMAP#4，S/M）· A2+A3+A15 弱网重同步包（session/read + includeSnapshot + state.updated 脏信号，M）· C2 用量统计页（方法用 v4/usage/stats，M）· A20 归档过滤（并入 ROADMAP#12，S）· B1 Turn 制消息分组+工作段折叠（L，需重构 ChatScreen 气泡模型）· B5 内联 diff +N/-N（随 ROADMAP#1，M）· A16易半 renameSession/deleteSession（v4 滩头，S/M）· B12 触屏细节（S）

### P2
A1 关会话 · A5 标题/关闭事件同步 · A6 后台任务查看与取消 · A8 闲时任务（注意 7 类失败分类）· A11 单会话用量（用 v4/conversation/usage）· A19 OAuth 运行时头（前置：token 持久化）· B2 轮导航迷你地图 · B3 流式 Markdown · B4 代码块工具条 · B7 子代理卡 · B8/B9 排队面板增量 · C3 MCP 管理（先只读，写需 JSON 合并策略）· C4 插件管理（zcode plugins --json）· C5 技能/记忆只读 · C7 服务端过旧提示

### 明确不做
- **v4 全量迁移**：成本 2×L 以上（重写 AgentSession ingestion + ChatScreen 投影模型），legacy 面仍是桌面官方链路且无摘除时间表。替代：**v4 一次性查询滩头**（fileChanges、v4/usage/stats、rename/delete 命令，server.ts 直收无需握手订阅，成本 M）。
- workspace/generateText 起标题：引擎已自动生成标题，伪需求且方法待移除。
- headless `zcode -p`：被 A7 automation 上位替代；且 AppSession.exec 20s 超时跑不动长任务。

## 二、批次计划

**批次一 · 审批闭环**（只动 AgentSession 反向请求 + ChatScreen）
A13 多端互踢 + D2 问答 UI + B10 权限预览弹窗 + A9 上下文用量条。
交付：手机上能正常回答 agent 提问、批权限看 diff、桌面处理过的弹窗自动消失、顶栏显示上下文用量。

**批次二 · 监控端成型**（全新页面 + HTTPS 层，不动聊天页）
A7 定时任务管理 + C1 额度页 + C6 离线解密 credentials.json 补 OAuth 鉴权分支（解密失败降级为仅 API Key 分支）+ C7 过旧提示。
注意：bigmodel monitor 接口 Authorization 头直传 API Key 原文，禁止 Bearer 前缀。

**批次三 · 协议加固与表达力**
A2+A3+A15 重同步包 + A10 重试状态条 + A17 Todo 卡 + A12（并#4）+ A20（并#12）+ A16 易半 rename/delete（v4 滩头）+ B12 触屏细节。
后续衔接 ROADMAP #1/#8 与 B1/B5 可作批次四。

## 三、风险

1. **远端版本漂移**：schema 全 strict，旧引擎遇新参数回 -32602/-32601；按 AppSession.serverVersion 做能力门控，新入口按版本显隐。
2. **deprecated 词摘除**：session/cancelBackgroundTask、usage/stats 等 legacy 方法已标 deprecated，优先用 v4 一次性查询同名替代，暴露面收敛到可替换封装内。
3. **C6 解密脆性**：远端设 ZCODE_CREDENTIAL_SECRET 则密钥公式失效；必须「解密失败→仅 API Key 分支」降级，额度页不能整体报错。
4. **业务后端私有 API 无契约**：quota 接口的 unit/type 词表无版本承诺，解析层宽容（未知跳过不炸），原始 JSON 留调试出口。
5. **v4/command 直调是未承诺路径**：上游可随时加握手门禁；commandId 必须 uuid v7 且重试不变；CAS 命令（edit/retry/队列）严禁伪造 baseRevision，滩头只限无 CAS 命令与只读查询。

## 四、调研新发现（ROADMAP 之外）

1. automation/offPeak 两族定时/闲时任务方法（ROADMAP 完全未覆盖，手机恰是最佳监控终端）。
2. state.updated 通知是最便宜的「快照已脏」信号，弱网重连对齐用它。
3. create/resume 响应快照自带 todos/todoGroups/slashCommands/contextUsed——纯渲染富矿，零协议成本。
4. 远端 credentials.json 可离线解密（密钥可由 uname/HOME/whoami 推导），但官方 refresh token 链路未实现，App 只存 API Key 已是当前最优。
5. v4/command 里 renameSession/deleteSession 无 CAS 要求可直接用；editUserQuery/retryTurn/队列四件套需 v4 订阅，挂 D1 滩头之后。
6. 协议没有 session/search 方法，搜索只能本地过滤（已实现）；归档靠 includeArchived + archivedAt。
7. 手机 Web 端可抄的主要是触屏细节（弹层关闭不回焦输入框、操作常驻不藏 hover 后）与 replayable 重连语义；布局上原生底部导航优于它的抽屉方案，不必对齐。
