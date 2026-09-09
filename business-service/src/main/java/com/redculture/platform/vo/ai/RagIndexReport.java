package com.redculture.platform.vo.ai;

/**
 * RAG 索引构建报告。
 *
 * @param totalChunks 参与本次处理的内容分块总数
 * @param indexedChunks 成功写入索引的分块数量
 * @param failedChunks 索引失败的分块数量
 * @param skippedChunks 因无需更新等原因跳过的分块数量
 * @param deletedPoints 从向量集合中删除的过期点位数量
 * @param collectionName 本次写入的向量集合名称
 * @param aliasSwitched 是否已将查询别名切换到新集合
 */
public record RagIndexReport(int totalChunks,
                             int indexedChunks,
                             int failedChunks,
                             int skippedChunks,
                             int deletedPoints,
                             String collectionName,
                             boolean aliasSwitched) {

    /**
     * 创建仅包含基础索引统计的报告，其余统计采用默认值。
     *
     * @param totalChunks 参与处理的内容分块总数
     * @param indexedChunks 成功写入索引的分块数量
     * @param failedChunks 索引失败的分块数量
     */
    public RagIndexReport(int totalChunks, int indexedChunks, int failedChunks) {
        this(totalChunks, indexedChunks, failedChunks, 0, 0, null, false);
    }
}
