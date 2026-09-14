package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 媒体类型枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum MediaType {
    /**
     * 图片。
     */
    IMAGE("image"),
    /**
     * 视频。
     */
    VIDEO("video"),
    /**
     * 音频。
     */
    AUDIO("audio"),
    /**
     * 文档。
     */
    DOCUMENT("document"),
    /**
     * 外部链接。
     */
    LINK("link");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;
}
