<script setup lang="ts">
// ============================================================
// 知识库分段预览页面
// 展示文档详情内容，支持预览文本分段
// 对接 ResourceAdminController: GET /api/admin/resources/{id}
// ============================================================
import { computed, onMounted, ref } from "vue";
import {
  FileText, ChevronRight, ChevronDown,
  LoaderCircle, AlertTriangle, BookOpen,
  Search, Eye,
} from "@lucide/vue";
import { api, ApiError, withQuery } from "@/services/api";

// ---- 类型 ----
interface KnowledgeDoc {
  resourceId: number;
  resourceName: string;
  resourceCategory: string;
  resourceSubcategory: string | null;
  intro: string | null;
  educationValue: string | null;
  activitySuggestion: string | null;
  targetGrade: string | null;
  safetyNote: string | null;
  reviewStatus: string;
  createdAt: string;
  updatedAt: string;
}

interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

interface ChunkPreview {
  id: number;
  title: string;
  content: string;
  tokenCount: number;
}

// ---- 状态 ----
const docs = ref<KnowledgeDoc[]>([]);
const loading = ref(false);
const error = ref("");
const keyword = ref("");
const pageNum = ref(1);
const pageSize = 20;
const total = ref(0);

// 选中预览
const selectedDoc = ref<KnowledgeDoc | null>(null);
const detailLoading = ref(false);
const chunks = ref<ChunkPreview[]>([]);

// ---- 计算 ----
const totalPages = computed(() => Math.ceil(total.value / pageSize) || 1);

// ---- 方法 ----
onMounted(() => { void fetchDocs(); });

async function fetchDocs(): Promise<void> {
  loading.value = true;
  error.value = "";
  try {
    const params: Record<string, string | number> = {
      pageNum: pageNum.value,
      pageSize,
      resourceCategory: "KNOWLEDGE_BASE",
    };
    if (keyword.value.trim()) params.keyword = keyword.value.trim();
    const result = await api.get<PageResult<KnowledgeDoc>>(
      withQuery("/api/admin/resources", params),
    );
    docs.value = result.records ?? [];
    total.value = result.total ?? 0;
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : "加载文档失败";
  } finally {
    loading.value = false;
  }
}

function onSearch(): void {
  pageNum.value = 1;
  void fetchDocs();
}

async function selectDoc(doc: KnowledgeDoc): Promise<void> {
  if (selectedDoc.value?.resourceId === doc.resourceId) {
    selectedDoc.value = null;
    chunks.value = [];
    return;
  }
  selectedDoc.value = doc;
  detailLoading.value = true;
  chunks.value = [];
  try {
    const detail = await api.get<KnowledgeDoc>(
      `/api/admin/resources/${doc.resourceId}`,
    );
    selectedDoc.value = detail;
    // 模拟分段：将 intro 和 educationValue 按段落拆分展示
    buildChunks(detail);
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : "加载文档详情失败";
  } finally {
    detailLoading.value = false;
  }
}

function buildChunks(doc: KnowledgeDoc): void {
  const result: ChunkPreview[] = [];
  let id = 0;

  // 按双换行分段
  const splitChunks = (label: string, text: string | null): void => {
    if (!text?.trim()) return;
    const paragraphs = text.split(/\n\s*\n/).filter(Boolean);
    for (const para of paragraphs) {
      result.push({
        id: ++id,
        title: `${label} · 分段 ${id}`,
        content: para.trim(),
        tokenCount: Math.ceil(para.trim().length / 2),
      });
    }
  };

  splitChunks("内容简介", doc.intro);
  splitChunks("教育价值", doc.educationValue);
  splitChunks("活动建议", doc.activitySuggestion);

  chunks.value = result;
}

function categoryLabel(cat: string): string {
  const map: Record<string, string> = {
    KNOWLEDGE_BASE: "知识库",
    RED_SITE: "红色遗址",
    MEMORIAL_HALL: "纪念馆",
  };
  return map[cat] ?? cat;
}

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    DRAFT: "草稿", PENDING: "待审核", APPROVED: "已通过", REJECTED: "已驳回",
  };
  return map[status] ?? status;
}

function dateStr(val: string): string {
  if (!val) return "-";
  const d = new Date(val);
  return Number.isNaN(d.getTime()) ? val : d.toLocaleDateString("zh-CN");
}

function truncate(text: string | null, max = 80): string {
  if (!text) return "-";
  return text.length > max ? text.slice(0, max) + "…" : text;
}
</script>

<template>
  <div class="seg-page">
    <h2><BookOpen :size="20" /> 知识库分段预览</h2>

    <!-- 搜索 -->
    <div class="search-bar">
      <Search :size="14" />
      <input v-model="keyword" placeholder="搜索文档…" @keyup.enter="onSearch" />
      <button class="btn btn-sm" @click="onSearch" :disabled="loading">搜索</button>
    </div>

    <!-- 错误 -->
    <div v-if="error" class="notice notice-error">
      <AlertTriangle :size="14" /> {{ error }}
    </div>

    <!-- 加载 -->
    <div v-if="loading" class="loading-state">
      <LoaderCircle :size="18" class="spinning" /> 加载中…
    </div>

    <div v-else class="seg-layout">
      <!-- 左侧：文档列表 -->
      <div class="doc-list">
        <div v-if="!docs.length" class="empty-state">
          <FileText :size="28" />
          <span>暂无知识库文档</span>
        </div>
        <div
          v-for="doc in docs"
          :key="doc.resourceId"
          :class="['doc-item', { active: selectedDoc?.resourceId === doc.resourceId }]"
          @click="selectDoc(doc)"
        >
          <div class="doc-item-header">
            <FileText :size="14" />
            <strong>{{ doc.resourceName }}</strong>
            <span :class="['badge', doc.reviewStatus === 'APPROVED' ? 'badge-green' : '']">
              {{ statusLabel(doc.reviewStatus) }}
            </span>
          </div>
          <p class="doc-item-intro">{{ truncate(doc.intro) }}</p>
          <div class="doc-item-meta">
            <span>{{ categoryLabel(doc.resourceCategory) }}</span>
            <span v-if="doc.targetGrade">{{ doc.targetGrade }}</span>
            <span>{{ dateStr(doc.createdAt) }}</span>
          </div>
        </div>

        <!-- 分页 -->
        <div v-if="totalPages > 1" class="pagination">
          <button :disabled="pageNum <= 1" @click="pageNum--; fetchDocs()">上一页</button>
          <span>{{ pageNum }} / {{ totalPages }}</span>
          <button :disabled="pageNum >= totalPages" @click="pageNum++; fetchDocs()">下一页</button>
        </div>
      </div>

      <!-- 右侧：分段预览 -->
      <div class="preview-panel">
        <div v-if="!selectedDoc" class="empty-preview">
          <Eye :size="36" />
          <span>选择左侧文档查看分段预览</span>
          <span class="hint">点击文档可查看内容分段、教育价值等详情</span>
        </div>

        <div v-else-if="detailLoading" class="loading-state">
          <LoaderCircle :size="18" class="spinning" /> 加载详情…
        </div>

        <div v-else class="preview-content">
          <div class="preview-header">
            <h3>{{ selectedDoc.resourceName }}</h3>
            <div class="preview-tags">
              <span class="badge">{{ categoryLabel(selectedDoc.resourceCategory) }}</span>
              <span v-if="selectedDoc.targetGrade" class="badge">{{ selectedDoc.targetGrade }}</span>
              <span :class="['badge', selectedDoc.reviewStatus === 'APPROVED' ? 'badge-green' : '']">
                {{ statusLabel(selectedDoc.reviewStatus) }}
              </span>
            </div>
          </div>

          <!-- 原文展示 -->
          <div v-if="selectedDoc.intro" class="preview-section">
            <h4>内容简介</h4>
            <div class="content-block">{{ selectedDoc.intro }}</div>
          </div>
          <div v-if="selectedDoc.educationValue" class="preview-section">
            <h4>教育价值</h4>
            <div class="content-block">{{ selectedDoc.educationValue }}</div>
          </div>
          <div v-if="selectedDoc.activitySuggestion" class="preview-section">
            <h4>活动建议</h4>
            <div class="content-block">{{ selectedDoc.activitySuggestion }}</div>
          </div>

          <!-- 分段预览 -->
          <div v-if="chunks.length" class="preview-section">
            <h4>文本分段 <span class="chunk-count">{{ chunks.length }} 段</span></h4>
            <div v-for="chunk in chunks" :key="chunk.id" class="chunk-card">
              <div class="chunk-header">
                <span class="chunk-title">{{ chunk.title }}</span>
                <span class="chunk-tokens">≈ {{ chunk.tokenCount }} tokens</span>
              </div>
              <p class="chunk-text">{{ chunk.content }}</p>
            </div>
          </div>

          <!-- 安全提示 -->
          <div v-if="selectedDoc.safetyNote" class="preview-section">
            <h4>安全提示</h4>
            <div class="content-block">{{ selectedDoc.safetyNote }}</div>
          </div>

          <div v-if="!selectedDoc.intro && !selectedDoc.educationValue" class="empty-state">
            <FileText :size="24" />
            <span>此文档暂无内容详情</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.seg-page { max-width: 1100px; margin: 0 auto; padding: 16px 0; }
.seg-page h2 { display: flex; align-items: center; gap: 8px; margin: 0 0 16px; font-size: 18px; }

.search-bar { display: flex; align-items: center; gap: 8px; padding: 6px 12px; border: 1px solid var(--line); border-radius: 8px; background: #fff; margin-bottom: 16px; max-width: 400px; }
.search-bar input { border: none; outline: none; flex: 1; font-size: 14px; }

.notice { display: flex; align-items: center; gap: 6px; padding: 10px 14px; border-radius: 8px; font-size: 13px; margin-bottom: 12px; }
.notice-error { background: #fef2f2; color: #991b1b; }

.loading-state { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 40px; color: var(--muted); }

.seg-layout { display: grid; grid-template-columns: minmax(280px, 380px) 1fr; gap: 16px; min-height: 60vh; }

/* 左侧文档列表 */
.doc-list { display: flex; flex-direction: column; gap: 8px; max-height: 72vh; overflow-y: auto; }
.doc-item { padding: 14px; background: #fff; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; transition: border-color .2s; }
.doc-item:hover { border-color: var(--green); }
.doc-item.active { border-color: var(--green); background: #f9fdf7; }
.doc-item-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.doc-item-intro { margin: 0; font-size: 12px; color: var(--muted); line-height: 1.5; }
.doc-item-meta { display: flex; gap: 10px; margin-top: 8px; font-size: 11px; color: #b0b0b0; }

/* 右侧预览 */
.preview-panel { background: #fff; border: 1px solid var(--line); border-radius: 10px; max-height: 72vh; overflow-y: auto; }
.empty-preview { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 60px 0; color: var(--muted); }
.empty-preview .hint { font-size: 12px; color: #ccc; }

.preview-content { padding: 20px; }
.preview-header { margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.preview-header h3 { margin: 0 0 8px; font-size: 20px; }
.preview-tags { display: flex; gap: 6px; }

.preview-section { margin-bottom: 20px; }
.preview-section h4 { display: flex; align-items: center; gap: 8px; margin: 0 0 8px; font-size: 14px; color: var(--green); }
.chunk-count { font-size: 12px; color: var(--muted); font-weight: 400; }

.content-block { padding: 14px; background: #f9faf8; border-radius: 8px; font-size: 14px; line-height: 1.8; white-space: pre-wrap; }

.chunk-card { padding: 14px; margin-bottom: 8px; background: #fdfdfc; border: 1px solid #eee; border-radius: 8px; }
.chunk-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.chunk-title { font-size: 13px; font-weight: 600; color: var(--green); }
.chunk-tokens { font-size: 11px; color: var(--muted); background: #f5f5f5; padding: 2px 8px; border-radius: 99px; }
.chunk-text { margin: 0; font-size: 13px; line-height: 1.7; white-space: pre-wrap; }

.badge { display: inline-block; padding: 3px 10px; border-radius: 99px; background: #f0f0f0; font-size: 12px; }
.badge-green { background: #ecfdf5; color: #065f46; }

.btn { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border: 1px solid var(--line); border-radius: 8px; background: #fff; cursor: pointer; font-size: 12px; }
.btn-sm { padding: 4px 10px; }

.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 40px 0; color: var(--muted); }

.pagination { display: flex; align-items: center; gap: 10px; margin-top: 12px; font-size: 13px; }
.pagination button { padding: 4px 10px; border: 1px solid var(--line); border-radius: 6px; background: #fff; cursor: pointer; font-size: 12px; }
.pagination button:disabled { opacity: .4; }

.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 800px) {
  .seg-layout { grid-template-columns: 1fr; }
}
</style>
