package com.redculture.platform.vo.ai;

import lombok.Data;

/** 大语言模型模型可选项。 */
@Data
public class LlmModelOption {
    /** 唯一标识。 */
    private String id;
    /** 显示名称。 */
    private String displayName;
    /** 本轮实际使用的模型服务提供方。 */
    private String provider;
    /** 本轮实际使用的模型名称。 */
    private String model;
    /** 是否为默认项。 */
    private boolean isDefault;
    /** 模型是否支持 JSON 对象输出。 */
    private boolean supportsJsonObject;
    /** 模型是否支持按 JSON Schema 生成结构化输出。 */
    private boolean supportsJsonSchema;
    /** 模型是否支持结构化工具调用结果。 */
    private boolean supportsStructuredToolOutput;
}
