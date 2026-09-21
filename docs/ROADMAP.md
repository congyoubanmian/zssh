# ZSsh 功能补齐清单（对照 ZCode 开源仓库）

> 对照基线：开源仓库 `zai-org/ZCode`（协议权威定义：`packages/shared/src/zcode-protocol/index.ts`，
> zod schema 全集、全部 `.strict()`）。
> 工作量：S ≈ 半天内，M ≈ 1~2 天，L ≈ 3 天以上。
> 推荐顺序：第一、六节先做（用户感知最强 + 稳定性欠账），二、五节次之。

## APK 已有能力（基线）

连接管理（密码/私钥）、平台检测（platformArch）、M2-A 远端自下载部署（SHA 增量）、
引擎驱动（create/resume/subscribe/send/stop/setModel/setMode/list）、流式回复与思维链、
工具起止提示、权限弹窗（allow/deny）、供应商管理（手动 + 套餐 OAuth 登录自动取 Key）、
配置 push/pull、会话列表/恢复、目录选择、权限模式与模型切换、断线重连（引擎级）。

---

## 一、聊天页交互（感知最强，优先）

| # | 功能 | 协议依据 | 说明 | 量 |
|---|---|---|---|---|
| 1 | 工具实时输出 | `tool.updated` 的 `progress`（stdoutTail/stderrTail/outputPreview）与 `result`（display、结构化结果） | 现在只有「🛠 完成」；长命令（构建/测试）能看到实时尾部输出与 diff 内容 | M |
| 2 | token 用量角标 | `turn.completed` 的 `tokenCount/usage/toolCallCount/duration/resultType` | 每轮结束显示消耗；resultType=error_max_turns 等给用户明确反馈 | S |
| 3 | 问答式输入 UI | 反向请求 `interaction/requestUserInput`（AskUserQuestion / ExitPlanMode 都走它），应答 `{action, content?, reason?}` | 现在是合法 decline；做成选项单选/多选弹窗后，agent 提问/退出计划模式才能正常交互 | M |
| 4 | 权限「本项目始终允许」 | `options[]`（allow_once/allow_project/deny）+ 应答 `permissionUpdates: [{type:"addRules", behavior:"allow", rules:[…]}]` | 服务端已把选项下发，UI 渲染即可；高价值低改动 | S |
| 5 | 思考等级切换 | `session/setThoughtLevel`；reasoningLevel 取值由各模型 `optionSpecs.reasoningLevel.values` 定义 | 模型选择器里已有 levels，补一个独立切换入口 | S |
| 6 | 会话压缩 | `session/compact`（instructions?，返回 compact.state=accepted/already_running） | 长会话防爆上下文；聊天页菜单加一项 | S |
| 7 | 插队状态提示 | `turn.steerQueued` / `turn.steerDrained` 事件 | 运行中发送被引擎排队时，队列卡片显示「已进入引擎队列」 | S |
| 8 | 历史分页加载 | `session/messages`（afterMessageId/limit） | 比解析 create 响应里的 messages 更稳；长历史会话滚动加载 | M |

## 二、文件与工作区（v4 协议族，手机特色场景）

| # | 功能 | 协议依据 | 说明 | 量 |
|---|---|---|---|---|
| 9 | 文件变更列表 + diff 查看 | `v4/conversation/fileChanges`（旧协议无对应方法，diff 内容在 `tool.updated` result 里也有） | 「这轮改了哪些文件」列表 + 点开看 diff；手机上审代码是刚需 | M/L |
| 10 | 检查点回滚 | `checkpoint.created` / `rewind.triggered` 事件 + `v4/conversation/fileRewindPreview` | 回滚前预览将恢复的文件；误操作救星 | M |
| 11 | 图片/附件发送 | `v4/attachment/begin|chunk|commit|abort`（分块上传） | 手机相册选图发给 agent（GLM-4.6V 等视觉模型）；APK 最能出彩的差异功能 | M/L |

## 三、会话管理

| # | 功能 | 协议依据 | 说明 | 量 |
|---|---|---|---|---|
| 12 | 会话搜索/归阅 | `session/list`（includeArchived、limit、sessionIds 1~64、workspace 过滤） | 列表页加搜索框与归档过滤 | S |
| 13 | 子代理会话查看 | `session/subagents`（running[] / ended 分页） | workflow 场景下看子代理进度；配合 ChatScreen 的 source:"subagent" 事件 | M |
| 14 | 目标管理 | `session/goal`（show/set/pause/resume/clear） | 优先级低，桌面也偏边缘 | 低 |

## 四、模型与供应商

| # | 功能 | 协议依据 | 说明 | 量 |
|---|---|---|---|---|
| 15 | 供应商连通性测试 | `provider/testModelConnectivity`（{workspace, selection}） | 模型配置页每个供应商加「测试」按钮，配错立刻发现 | S |
| 16 | 从官方模板添加供应商 | `config/provider/zcode-builtin.json` 模板表（Kimi/DeepSeek/MiniMax/百炼/OpenAI/Anthropic/xAI… 全套 baseUrl+模型清单） | 编辑器加「从模板添加」下拉，预填端点与模型；省去查文档 | S |
| 17 | openai-responses 类型 + 默认模型 | `api.type` 三种取值；顶层 `defaultModelSelection`/`providerOrder`/`modelConfigRules` | 编辑器补第三种 chip；push 时写默认模型 | M |
| 18 | 用量统计页 | `usage/stats`（range=7d/30d/all，@deprecated 但可用） | 按 provider/model 的用量汇总 | M |

## 五、部署与运维

| # | 功能 | 协议依据 | 说明 | 量 |
|---|---|---|---|---|
| 19 | M2-B：手机中转上传部署 | 官方 LocalUploadAssetInstaller 模式（下载组件→SFTP 上传→解压落位）；APK 已有 sshj SFTP 依赖 | 远端无外网机器的部署回退路径 | M |
| 20 | 部署检查更新 | `latest.json` 版本发现（部署模块已实现）+ marker SHA 比对 | 会话页加「检查远端更新」按钮，一键升级 | S |
| 21 | 远端日志查看 | 日志在 `~/.zcode/cli/log/zcode-YYYY-MM-DD.jsonl`（保留 7 天）；`ZCODE_LOG_CONSOLE=1` 镜像 stderr | 排障刚需：tail 当天日志 + 按级别过滤 | S/M |
| 22 | MCP 状态查看 | `mcp/list`（mode=connect/status，返回各服务器状态/工具数/错误） | 设置页展示 MCP 服务器健康度 | S |

## 六、稳定性与安全（前两轮 review 遗留欠账，优先清掉）

| # | 功能 | 说明 | 量 |
|---|---|---|---|
| 23 | TOFU 主机密钥校验 | `SshService` 仍是 `PromiscuousVerifier()`（MITM 风险）：首次展示指纹确认，之后比对（存 ConnectionStore） | S/M |
| 24 | SSH 连接超时 | `connect()` 无超时，网络黑洞时检测页永远转圈；`connectTimeout=15s` | S |
| 25 | 聊天页重连修复 | 「重新连接」只重建引擎不检查 `ssh.isConnected`；改为先走 `ensureConnected` | S |
| 26 | 读循环逐条隔离 | 字段类型异常会杀死读循环并误判断连；handleLine 内按消息 try/catch | S |
| 27 | ViewModel / 进程死亡恢复 | ChatScreen ~640 行、20+ 个 remember 状态，进程死亡全丢；拆 ViewModel + SavedStateHandle | M |
| 28 | SSH keepalive 心跳 | NAT 静默断连后第一次操作才报错；sshj `KeepAliveProvider`（官方 SSH backend 同款） | S |
| 29 | `sid!!` 加固 | createSession 响应缺 sessionId 时的孤儿会话问题 | S |

## 明确不建议做

- **zcode-server 常驻 HTTP/WS 架构**（端口转发 + capability 鉴权）：APK 直连 agent 的 stdio 链路是官方宿主同款，复杂度不值。
- **refresh token 自动续订**：刷新逻辑埋在 CLI 账号运行时里；APK 场景 key 过期重新点一次登录即可。
- **桌面 ServiceCollection 全家桶**（file/git/terminal 远程代理）：那是桌面 UI 的架构，与聊天场景无关。

---

### 落点速查

改动集中在：`AgentSession`（新方法/新事件转发）、`ChatScreen`（事件渲染与交互）、
`SessionsScreen`（部署/更新入口）、`ModelConfigScreen`（模板/测试/用量）、
`SshService`（TOFU/超时/keepalive）、`DeployService`（M2-B、更新检查）。
