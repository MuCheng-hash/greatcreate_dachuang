## Purpose

让跨会话记忆的同字段冲突在用户可见、可选择且原子一致的状态迁移中解决，避免任何激活入口静默替换已有偏好。

## ADDED Requirements

### Requirement: Activating a conflicting memory requires an explicit decision

系统 MUST 在确认、恢复、手动新增为已生效或编辑为已生效之前，检测当前账号、学校范围和规范化字段键下的其他已生效记忆。存在不同内容的冲突时，系统 MUST NOT 静默替换。

#### Scenario: Confirmation preview reports scoped conflicts

- **WHEN** 当前用户预检一条待确认的核心画像候选
- **THEN** 系统仅返回当前账号和学校范围内的候选、同字段已生效冲突项及是否与现有值完全重复的标记，且不改变任何记忆状态

#### Scenario: Conflict needs explicit replacement

- **WHEN** 待确认候选与同字段已生效记忆内容不同，且确认请求未明确允许替换
- **THEN** 系统返回 409 冲突结果且候选和旧记忆状态均保持不变

#### Scenario: Explicit replacement recycles old values atomically

- **WHEN** 用户明确选择用候选替换同字段旧值
- **THEN** 系统在同一事务中将全部冲突旧项移入 30 天回收站、激活候选并记录不含正文的审计，完成后同字段仅剩候选为已生效状态

#### Scenario: Keep old value recycles the candidate

- **WHEN** 用户在冲突弹窗选择保留旧值
- **THEN** 客户端将待确认候选移入回收站，旧记忆保持已生效

#### Scenario: Cancellation keeps candidate pending

- **WHEN** 用户取消冲突弹窗、按 Esc 或点击遮罩
- **THEN** 系统不发起状态变更，候选保持待确认

### Requirement: Duplicate and alias handling are stable

系统 MUST 将 `response_format` 规范为 `answer_format` 进行读取、比较和新写入。与同字段已有已生效记忆内容完全相同的候选 MUST NOT 生成重复已生效条目。

#### Scenario: Exact duplicate is not activated twice

- **WHEN** 用户确认一条与已有同字段值完全相同的候选
- **THEN** 系统保留已有已生效记忆、将新候选移入回收站并返回“该记忆已存在”的可处理结果

#### Scenario: Historical response-format is equivalent

- **WHEN** 历史记录使用 `response_format` 而新候选使用 `answer_format`
- **THEN** 系统将二者视为同一字段键并执行相同冲突或重复规则，不批量改写历史记录

### Requirement: All activation paths preserve scope isolation and consistency

恢复、手动新增和编辑字段也 MUST 使用相同的冲突保护。Spring MUST 从登录态派生所有者和学校范围，浏览器不得指定冲突项、所有者或学校范围。成功后的界面 MUST 同步受影响状态列表，不得依靠页面刷新纠正陈旧冲突。

#### Scenario: Restore cannot silently overwrite

- **WHEN** 用户恢复一条与当前已生效同字段记忆冲突的回收站条目
- **THEN** 系统返回冲突预检/409 或在用户明确替换后完成原子状态迁移，不得静默删除当前值

#### Scenario: UI synchronizes after replacement

- **WHEN** 用户用“26岁男教师”替换已生效的“28岁女教师”
- **THEN** 成功返回后已生效列表立即只显示“26岁男教师”，旧值立即显示在回收站，无需刷新页面
