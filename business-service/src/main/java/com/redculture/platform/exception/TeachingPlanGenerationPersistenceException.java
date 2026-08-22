package com.redculture.platform.exception;

public class TeachingPlanGenerationPersistenceException extends RuntimeException {
    public TeachingPlanGenerationPersistenceException(Throwable cause) {
        super("generation_record_save_failed", cause);
    }
}
