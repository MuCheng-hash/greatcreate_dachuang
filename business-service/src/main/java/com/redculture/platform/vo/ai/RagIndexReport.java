package com.redculture.platform.vo.ai;

public record RagIndexReport(int totalChunks,
                             int indexedChunks,
                             int failedChunks,
                             int skippedChunks,
                             int deletedPoints,
                             String collectionName,
                             boolean aliasSwitched) {

    public RagIndexReport(int totalChunks, int indexedChunks, int failedChunks) {
        this(totalChunks, indexedChunks, failedChunks, 0, 0, null, false);
    }
}
