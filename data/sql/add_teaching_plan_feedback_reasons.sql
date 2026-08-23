-- 教学方案反馈原因标签增量迁移。
-- 部署前请先备份数据库；本脚本仅用于已经执行 add_teaching_plan_feedback.sql 的环境。
ALTER TABLE teaching_plan_feedback
    ADD COLUMN reason_codes_json JSON NULL COMMENT '负面反馈原因编码 JSON 数组' AFTER rating;
