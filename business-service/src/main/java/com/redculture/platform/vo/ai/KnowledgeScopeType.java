package com.redculture.platform.vo.ai;

import java.util.Locale;

/** 知识范围类型枚举。 */
public enum KnowledgeScopeType {

    /** 以学校为知识检索范围。 */
    SCHOOL,
    /** 以行政区域为知识检索范围。 */
    REGION,
    /** 以单个教育资源为知识检索范围。 */
    RESOURCE;

    /**
     * 将字符串解析为知识范围类型，忽略大小写和首尾空白。
     *
     * @param value 待解析的范围类型
     * @return 对应范围类型；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 范围类型不受支持时抛出
     */
    public static KnowledgeScopeType from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("scopeType must be SCHOOL, REGION or RESOURCE");
        }
    }
}
