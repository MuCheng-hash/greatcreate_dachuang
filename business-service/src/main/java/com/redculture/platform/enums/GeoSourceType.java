package com.redculture.platform.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 地理信息来源枚举，定义该业务维度允许使用的标准取值。
 */
@Getter
@RequiredArgsConstructor
public enum GeoSourceType {
    /**
     * 高德地图 POI。
     */
    AMAP_POI("amap_poi"),
    /**
     * 人工录入。
     */
    MANUAL("manual"),
    /**
     * 学校官方资料。
     */
    SCHOOL_OFFICIAL("school_official"),
    /**
     * 政府文件。
     */
    GOVERNMENT_DOC("government_doc"),
    /**
     * 卫星影像校正。
     */
    SATELLITE_FIX("satellite_fix"),
    /**
     * 其他。
     */
    OTHER("other");

    /**
     * 枚举持久化值。
     */
    @EnumValue
    private final String value;

    /**
     * 根据持久化值解析地理信息来源。
     *
     * @param value 枚举持久化值
     * @return 匹配的枚举值；输入为空时返回 {@code null}
     * @throws IllegalArgumentException 输入值不受支持时抛出
     */
    @JsonCreator
    public static GeoSourceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (GeoSourceType item : values()) {
            if (item.value.equalsIgnoreCase(normalized) || item.name().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("不支持的 geoSourceType：" + value);
    }

    /**
     * 获取写入数据库或接口的枚举值。
     *
     * @return 枚举持久化值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
