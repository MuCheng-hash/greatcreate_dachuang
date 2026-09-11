package com.redculture.platform.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 封装分页查询的数据列表、总量和分页参数。
 *
 * @param <T> 分页记录类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 当前页数据列表。
     */
    private List<T> records;

    /**
     * 符合查询条件的记录总数。
     */
    private long total;

    /**
     * 当前页码。
     */
    private long pageNum;

    /**
     * 每页记录数。
     */
    private long pageSize;

    /**
     * 根据分页数据创建分页结果。
     *
     * @param <T> 返回数据类型
     * @param records 当前页数据列表
     * @param total 记录总数
     * @param pageNum 页码
     * @param pageSize 每页记录数
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        return new PageResult<>(records, total, pageNum, pageSize);
    }

    /**
     * 创建指定分页参数的空结果。
     *
     * @param <T> 返回数据类型
     * @param pageNum 页码
     * @param pageSize 每页记录数
     * @return 空分页结果
     */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(Collections.emptyList(), 0, pageNum, pageSize);
    }
}
