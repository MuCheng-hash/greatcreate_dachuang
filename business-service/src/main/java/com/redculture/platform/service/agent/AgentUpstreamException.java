package com.redculture.platform.service.agent;

public class AgentUpstreamException extends RuntimeException {

    private final int statusCode;
    private final String code;
    private final boolean retryable;
    private final String responseBody;

    public AgentUpstreamException(int statusCode,
                                  String code,
                                  boolean retryable,
                                  String responseBody,
                                  Throwable cause) {
        super(code, cause);
        this.statusCode = statusCode;
        this.code = code;
        this.retryable = retryable;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    String responseBody() {
        return responseBody;
    }
}
