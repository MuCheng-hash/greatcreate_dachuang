## 1. Contract Tests

- [x] 1.1 Add failing Vue tests for temporary model visibility during streaming and its absence after final, stop, failure, and historical restoration
- [x] 1.2 Add failing Vue tests for the memory destructive-action confirmation flow, safe cancellation, fixed bulk targets, and no native browser confirmation

## 2. Frontend Implementation

- [x] 2.1 Restrict actual-model presentation in intelligent Q&A and teaching-plan views to active generation only while preserving response and snapshot metadata
- [x] 2.2 Add an accessible, styled reusable confirmation dialog with focus trapping, Esc/backdrop cancellation, focus restoration, and in-flight protection
- [x] 2.3 Replace memory-center native confirms for recycle, permanent delete, and bulk clear with the new dialog and existing memory APIs

## 3. Verification

- [x] 3.1 Run focused and full frontend Vitest suites plus TypeScript checking
- [ ] 3.2 Build production frontend assets, restart or verify Spring-served assets, and perform browser acceptance for model hiding and all confirmation variants
- [ ] 3.3 Validate the OpenSpec change strictly and confirm all tasks are complete

> 验收备注：生产构建、Spring 重启和新静态资源已验证；教师账号下的真实浏览器交互验收仍需可用的学校账号，因此 3.2 与 3.3 保持未完成。当前自动化浏览器会被未登录状态重定向至登录页，待确认记忆常驻区也保留该真实账号验收限制。

## 4. Composer Memory Suggestions

- [x] 4.1 Add failing Vue tests for composer-adjacent pending-memory rendering, success removal, failure retention, and final-candidate focus restoration
- [x] 4.2 Aggregate current-session pending candidates in `AssistantView`, move their cards above the composer, and show localized operation feedback without changing APIs or history restoration
- [x] 4.3 Run focused and full frontend Vitest suites, TypeScript checking, production build, and verify Spring serves the refreshed asset

## 5. Timed Composer Feedback

- [x] 5.1 Add a failing Vue regression test proving a successful candidate action remains visible before, then disappears at, five seconds
- [x] 5.2 Auto-dismiss successful composer memory feedback after five seconds; cancel stale timers on replacement, chat clearing, and component unmount while retaining error feedback
- [x] 5.3 Run the full frontend verification suite, typecheck, production build, and verify the updated static asset is served by Spring
