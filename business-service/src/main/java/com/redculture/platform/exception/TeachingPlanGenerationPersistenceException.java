package com.redculture.platform.exception;

/**
 * 表示 AI 教学方案已经生成但保存失败。
 */
public class TeachingPlanGenerationPersistenceException extends RuntimeException {
    /**
     * 创建教学方案生成结果持久化异常。
     *
     * @param cause 导致持久化失败的原始异常
     */
    public TeachingPlanGenerationPersistenceException(Throwable cause) {
        super("generation_record_save_failed", cause);
    }
}
