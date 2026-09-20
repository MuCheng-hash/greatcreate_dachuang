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
     * 将外部传入的字符串转换为知识范围枚举。
     *
     * <p>该方法用于处理请求参数等文本输入：空值或仅包含空白字符的输入不指定范围，
     * 因而返回 {@code null}；非空输入会先去除首尾空白并统一转换为大写，再与枚举常量比对，
     * 所以 {@code school}、{@code School} 和 {@code " SCHOOL "} 都会解析为 {@link #SCHOOL}。</p>
     *
     * <p>大小写转换使用 {@link Locale#ROOT}，避免服务器默认语言环境影响结果。例如，
     * 在特定语言环境下，字母大小写规则可能与枚举常量的 ASCII 命名不一致。</p>
     *
     * @param value 待解析的范围类型
     * @return 对应范围类型；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入非空但不是 {@code SCHOOL}、{@code REGION} 或
     *                                  {@code RESOURCE} 时抛出
     */
    public static KnowledgeScopeType from(String value) {
        // 未传入范围或只传入空白时，交由调用方按“未指定范围”处理，避免 valueOf 抛出空值异常。
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // 先清理首尾空白并按与系统语言无关的规则转为大写，再匹配枚举常量名称。
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            // 不直接透出 valueOf 的实现细节，向调用方提供稳定、明确的可选范围提示。
            throw new IllegalArgumentException("scopeType 必须为 SCHOOL、REGION 或 RESOURCE");
        }
    }
}
