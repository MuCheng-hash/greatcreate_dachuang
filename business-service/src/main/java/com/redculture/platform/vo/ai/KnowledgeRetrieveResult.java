package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 知识检索聚合结果。 */
@Data
public class KnowledgeRetrieveResult {

    /**
     * 知识检索状态。
     * 常见取值包括 {@code ok}、{@code empty} 和 {@code degraded}。
     */
    private KnowledgeRetrievalStatus retrievalStatus;

    /** 分块列表。 */
    private List<KnowledgeChunkVO> chunks = new ArrayList<>();

    /** 知识图谱事实列表。 */
    private List<KnowledgeGraphFactVO> graphFacts = new ArrayList<>();

    /** 可供生成阶段选择和展示的引用候选列表。 */
    private List<KnowledgeCitationCandidateVO> citationCandidates = new ArrayList<>();

    /** 本轮实际使用的检索方式列表。 */
    private List<String> retrievalMethods = new ArrayList<>();

    /** 本次知识检索的内部追踪信息。 */
    private KnowledgeRetrievalTraceVO retrievalTrace;

    /**
     * 创建检索正常但未命中证据的空结果。
     *
     * @return 检索状态为 {@link KnowledgeRetrievalStatus#EMPTY} 的结果
     */
    public static KnowledgeRetrieveResult empty() {
        KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
        result.setRetrievalStatus(KnowledgeRetrievalStatus.EMPTY);
        return result;
    }

    /**
     * 创建部分检索能力不可用的降级结果。
     *
     * @return 检索状态为 {@link KnowledgeRetrievalStatus#DEGRADED} 的结果
     */
    public static KnowledgeRetrieveResult degraded() {
        KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
        result.setRetrievalStatus(KnowledgeRetrievalStatus.DEGRADED);
        return result;
    }

    /**
     * 汇总内容分块、图谱事实和引用候选中的有效引用标识，并按首次出现顺序去重。
     *
     * @return 去重后的引用标识集合
     */
    public Set<String> allCitationIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (chunks != null) {
            chunks.stream().map(KnowledgeChunkVO::getCitationId).filter(this::hasText).forEach(ids::add);
        }
        if (graphFacts != null) {
            graphFacts.stream().map(KnowledgeGraphFactVO::getCitationId).filter(this::hasText).forEach(ids::add);
        }
        if (citationCandidates != null) {
            citationCandidates.stream().map(KnowledgeCitationCandidateVO::getCitationId)
                    .filter(this::hasText).forEach(ids::add);
        }
        return ids;
    }

    /**
     * 根据已有方法、内容分块和图谱事实重新汇总实际使用的检索能力。
     * 复合检索名称会展开为对应的基础召回与重排能力，并保持首次出现顺序。
     */
    public void refreshRetrievalMethods() {
        Set<String> methods = new LinkedHashSet<>();
        if (retrievalMethods != null) {
            retrievalMethods.stream()
                    .filter(this::hasText)
                    .forEach(methods::add);
        }
        if (chunks != null) {
            for (KnowledgeChunkVO chunk : chunks) {
                String method = chunk == null ? null : chunk.getRetrievalMethod();
                if (!hasText(method)) {
                    continue;
                }
                if (method.startsWith("hybrid-rrf")) {
                    methods.add("dense");
                    methods.add("lexical");
                    methods.add("rrf");
                } else if (method.startsWith("dense")) {
                    methods.add("dense");
                } else if (method.startsWith("lexical")) {
                    methods.add("lexical");
                } else if (method.startsWith("hyde")) {
                    methods.add("hyde");
                } else if (method.startsWith("cross-encoder")) {
                    methods.add("cross-encoder-rerank");
                } else {
                    methods.add(method);
                }
                if (method.endsWith("+heuristic-rerank")) {
                    methods.add("heuristic-rerank");
                }
            }
        }
        if (graphFacts != null && !graphFacts.isEmpty()) {
            methods.add("knowledge-graph");
        }
        retrievalMethods = new ArrayList<>(methods);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
