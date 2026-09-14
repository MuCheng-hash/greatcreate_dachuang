package com.redculture.platform.vo.ai;

import lombok.Data;

/** Agent 跨会话用户记忆条目。 */
@Data
public class AgentMemoryItem {

    /** 唯一标识。 */
    private String id;

    /** 记忆类型，用于区分稳定画像与阶段性任务等内容。 */
    private String memoryType;

    /** 结构化记忆字段键；自定义记忆可为空。 */
    private String fieldKey;

    /** 内容。 */
    private String content;

    /** 记忆生命周期状态，可为 {@code pending}、{@code active} 或 {@code deleted}。 */
    private String status;

    /** 来源。 */
    private String source;

    /** 产生该记忆的对话线程标识。 */
    private String sourceThreadId;

    /** 当前结果的置信度。 */
    private Double confidence;

    /** 过期时间。 */
    private String expiresAt;

    /** 进入删除状态的时间。 */
    private String deletedAt;

    /** 允许永久清理该数据的时间。 */
    private String purgeAfter;

    /** 创建时间。 */
    private String createdAt;

    /** 最后更新时间。 */
    private String updatedAt;
}
