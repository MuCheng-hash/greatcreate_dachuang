package com.redculture.platform.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 教学方案反馈原因枚举，定义该业务维度允许使用的标准取值。
 */
public enum TeachingPlanFeedbackReason {
    /**
     * 适用学段不匹配。
     */
    GRADE_MISMATCH,
    /**
     * 主题偏离。
     */
    THEME_DEVIATION,
    /**
     * 资源不匹配。
     */
    RESOURCE_MISMATCH,
    /**
     * 实施难度过高。
     */
    HARD_TO_IMPLEMENT,
    /**
     * 时长安排不合理。
     */
    DURATION_UNREASONABLE,
    /**
     * 存在安全风险。
     */
    SAFETY_RISK,
    /**
     * 内容不完整。
     */
    CONTENT_INCOMPLETE,
    /**
     * 表述不清晰。
     */
    UNCLEAR_EXPRESSION,
    /**
     * 其他。
     */
    OTHER;

    /**
     * 全部受支持反馈原因代码的只读集合。
     */
    private static final Set<String> CODES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * 判断业务错误码是否为受支持的反馈原因。
     *
     * @param code 待校验的反馈原因代码
     * @return 受支持时返回 {@code true}
     */
    public static boolean supports(String code) {
        return CODES.contains(code);
    }

    /**
     * 按枚举声明顺序返回全部反馈原因代码。
     *
     * @return 按声明顺序排列的原因代码列表
     */
    public static List<String> orderedCodes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
