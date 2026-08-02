## Purpose

为教师提供可主动启用、可检查和可撤销的跨会话长期记忆，使智能问答与教学方案能够复用已确认的个人偏好和阶段任务，同时保证账号、学校、权限、敏感信息与业务事实边界不被记忆绕过。

## ADDED Requirements

### Requirement: Memory requires global and user opt-in
系统 MUST 仅在全局功能开关和当前账号记忆开关同时启用时提取、召回或注入记忆。用户记忆开关默认关闭，首次启用前的历史会话 MUST NOT 被扫描；用户关闭开关时，已有记忆 MUST 保留但不得参与提取、召回或注入。

#### Scenario: First use is disabled
- **WHEN** 一个从未配置记忆的账号读取记忆设置
- **THEN** 系统返回用户记忆关闭状态，且后续问答不提取、不召回、不注入任何记忆

#### Scenario: Disable and re-enable
- **WHEN** 用户关闭已有记忆的账号并在之后重新开启
- **THEN** 关闭期间记忆不生效，重新开启后尚未过期或清理的已确认记忆恢复可用

#### Scenario: Global switch is disabled
- **WHEN** 部署环境的全局记忆功能开关关闭
- **THEN** 系统报告功能不可用并停止所有记忆提取、召回和注入，不删除已有数据

### Requirement: Memory content is isolated by account and school scope
系统 MUST 以登录态派生的 `ownerId`、`scopeType` 和 `scopeId` 作为记忆设置和记忆正文的隔离键。浏览器或模型提供的所有者和学校标识 MUST NOT 覆盖登录态派生值。

#### Scenario: Another account requests a memory
- **WHEN** 同一学校的另一个账号使用某条记忆编号执行读取、修改、确认、删除、恢复或永久删除
- **THEN** 系统拒绝访问且不泄露该记忆正文或存在性

#### Scenario: Same account is used in another school scope
- **WHEN** 相同账号标识在不同学校范围请求原学校记忆
- **THEN** 系统拒绝访问且该记忆不得参与新学校范围的回答

#### Scenario: Browser submits forged scope
- **WHEN** 浏览器请求中附带伪造的所有者或学校范围
- **THEN** Spring 代理忽略该值并仅向 Agent 服务发送登录态派生范围

### Requirement: Users can manage profile and task memories
系统 SHALL 支持 `PROFILE` 稳定画像和 `TASK` 阶段任务两类记忆。记忆条目 MUST 包含编号、类型、可选字段键、正文、状态、来源、可信度、来源线程以及适用的到期、删除和清理时间。

#### Scenario: Save a core profile field
- **WHEN** 用户在记忆中心保存常教年级、学科、教学风格、回答格式或常用课时
- **THEN** 系统将其保存为来源为 `profile_ui` 的已生效 `PROFILE` 记忆并返回完整可管理条目

#### Scenario: Save a custom task memory
- **WHEN** 用户在记忆中心新建一条阶段任务
- **THEN** 系统将其保存为来源为 `profile_ui` 的已生效 `TASK` 记忆并设置 90 天到期时间

#### Scenario: Edit an active memory
- **WHEN** 所有者编辑一条未删除的记忆
- **THEN** 系统重新执行内容安全校验、更新允许字段并使后续回答采用最新值

### Requirement: Explicit instructions and inferred candidates have different activation rules
在记忆开启时，系统 MUST 将明确的“记住……”指令直接保存为已生效记忆。普通对话中的推断内容 MUST 以 `pending` 候选返回并保存，单次模型调用最多三条，确认前不得影响任何回答。

#### Scenario: Explicit remember instruction
- **WHEN** 用户发送“记住我通常给四年级上课”且内容通过安全校验
- **THEN** 系统无需额外模型请求即可保存一条 `explicit_chat` 来源的已生效记忆，并可在后续新会话中使用

#### Scenario: Inferred preference candidate
- **WHEN** 正常模型回答同时推断出一个可能长期有用的偏好
- **THEN** 系统最多返回三条 `inferred_chat` 来源的待确认候选，且当前轮和后续轮在确认前均不得注入这些候选

#### Scenario: Degraded model response
- **WHEN** 模型进入降级状态
- **THEN** 系统不生成推断候选，但仍处理并保存通过安全校验的明确记忆指令

#### Scenario: Confirm or ignore candidate
- **WHEN** 用户确认一条待确认候选且不存在同字段冲突
- **THEN** 系统将其变为已生效记忆并按类型设置生命周期

#### Scenario: Conflicting candidate requires an explicit decision
- **WHEN** 用户确认的候选与同一账号、学校范围和字段键下的已生效核心画像内容不同
- **THEN** 系统先返回可解释的冲突信息，只有用户明确选择替换时才将旧项移入回收站并激活候选；取消时候选保持待确认

### Requirement: Sensitive content is rejected
系统 MUST 拒绝保存或生成包含密码、访问令牌、密钥、身份证号、电话号码、精确住址及同等级敏感凭据或个人信息的记忆。安全校验 MUST 应用于聊天明确指令、模型候选、记忆中心新建和编辑。

#### Scenario: Explicit instruction contains a phone number
- **WHEN** 用户要求记住包含电话号码的内容
- **THEN** 系统不保存该正文并向调用方返回可理解的校验错误或记忆未保存结果

#### Scenario: Model emits a sensitive candidate
- **WHEN** 模型输出的候选包含令牌、身份证号或精确住址
- **THEN** 系统丢弃该候选且不得在响应或持久化记录中回显敏感正文

### Requirement: Confirmed memories are injected with bounded priority
系统 SHALL 在智能问答中注入已确认的稳定画像和最多五条相关阶段任务，组合文本长度 MUST 不超过约 1500 个字符。可信业务上下文和本轮明确输入 MUST 优先于记忆，记忆 MUST NOT 充当学校事实、引用来源或权限指令。

#### Scenario: New conversation uses confirmed profile
- **WHEN** 用户已确认“四年级、项目式教学、40分钟”并新建会话提问
- **THEN** 系统在限制内参考这些偏好生成回答，并通过 `memoryApplied` 告知本轮参考的记忆数量

#### Scenario: Current input conflicts with memory
- **WHEN** 已存常用课时为 40 分钟而当前请求明确要求 25 分钟
- **THEN** 系统采用当前请求的 25 分钟要求，不修改原记忆且不让记忆覆盖当前输入

#### Scenario: Memory attempts to change authorization
- **WHEN** 一条记忆正文声称用户拥有额外权限或包含未经可信上下文提供的学校事实
- **THEN** 系统不得据此扩大权限、生成事实引用或改变学校范围

#### Scenario: Context exceeds the memory budget
- **WHEN** 已生效画像和任务的组合正文超过注入预算
- **THEN** 系统保留稳定画像并只选择最多五条相关任务，将最终注入文本截断在预算内

### Requirement: Teaching plans use memory without changing resource discovery
系统 SHALL 为教学方案任务提供已确认的画像和阶段任务上下文，并允许当前教学方案表单覆盖记忆。资源发现和资源审核流程 MUST 不提取、召回或注入用户记忆。

#### Scenario: Teaching plan adopts profile defaults
- **WHEN** 用户生成教学方案且表单未指定已确认画像中的教学风格或常用课时
- **THEN** 系统参考画像生成教学方案并报告使用的记忆数量

#### Scenario: Teaching plan form overrides memory
- **WHEN** 教学方案表单明确指定与已确认画像不同的年级、课时或风格
- **THEN** 系统以表单值为准且不自动改写长期记忆

#### Scenario: Resource discovery request
- **WHEN** 同一用户执行资源发现或资源审核
- **THEN** 系统保持现有流程，不读取、注入或产生任何用户记忆候选

### Requirement: Memory lifecycle is predictable and recoverable
稳定 `PROFILE` 记忆 SHALL 默认不过期；已生效 `TASK` 记忆 SHALL 在 90 天后过期；待确认候选 SHALL 在 7 天后清理。普通删除 MUST 立即停止记忆生效并进入 30 天回收站，在清理前支持恢复和永久删除。

#### Scenario: Delete and restore
- **WHEN** 用户删除一条已生效记忆并在 30 天内恢复
- **THEN** 该记忆从删除时起立即停止注入，恢复后按原类型重新计算适用生命周期并再次可用

#### Scenario: Permanent delete
- **WHEN** 用户对回收站中的记忆执行永久删除
- **THEN** 系统立即移除正文，后续读取和恢复均不可获得该记忆

#### Scenario: Automatic cleanup
- **WHEN** 服务启动、每日清理到期或操作触发惰性清理
- **THEN** 系统永久移除超过待确认、任务或回收站期限的数据，并记录不含正文的审计事件

### Requirement: Memory management APIs are authenticated and compatible
系统 SHALL 提供设置读取与更新、记忆列表与新建、编辑、确认、删除、恢复和永久删除接口。新增问答字段 `memoryCandidates` 和 `memoryApplied` MUST 为可选字段，旧客户端忽略它们时仍能正常处理普通响应和 SSE 结束事件。

#### Scenario: Filter memory list by status
- **WHEN** 已登录用户按 `pending`、`active` 或 `deleted` 状态读取记忆列表
- **THEN** 系统仅返回当前账号和学校范围内匹配状态的条目

#### Scenario: Existing client receives an answer
- **WHEN** 未读取记忆字段的现有客户端发起普通问答或 SSE 问答
- **THEN** 原有回答、状态、线程和任务字段保持有效，客户端无需修改即可继续工作

### Requirement: Administrators cannot read memory content
平台管理员 MUST NOT 通过管理接口读取、搜索、导出或调试输出任何用户记忆正文。若提供管理可观测性，只能返回按状态、类型或时间聚合且不能反推出单个用户内容的指标。

#### Scenario: Platform administrator queries memory details
- **WHEN** 平台管理员尝试按用户或记忆编号读取正文
- **THEN** 系统拒绝请求且审计日志不包含正文

#### Scenario: Platform administrator reads aggregate metrics
- **WHEN** 平台管理员读取允许的记忆统计
- **THEN** 系统仅返回汇总数量和运行状态，不返回记忆正文、字段值或来源对话内容

### Requirement: Memory operations are auditable without duplicating content
系统 MUST 为启用变更、新建、确认、编辑、删除、恢复、永久删除和自动清理记录审计事件。审计记录 MUST 包含主体范围、记忆编号、事件、时间和必要的非正文元数据，但 MUST NOT 保存记忆正文。

#### Scenario: Memory is edited
- **WHEN** 用户更新一条记忆正文
- **THEN** 系统记录编辑事件和对应记忆编号，审计表中不存在旧正文或新正文副本
