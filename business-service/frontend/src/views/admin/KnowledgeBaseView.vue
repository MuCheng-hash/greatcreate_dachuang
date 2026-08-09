<script setup lang="ts">
// ============================================================
// 知识库文档管理页面（RAG 配套）
// 对接后端 ResourceAdminController: /api/admin/resources
// ============================================================
import { computed, onMounted, reactive, ref } from "vue";
import {
  FileText, Upload, Trash2, RefreshCw, Search,
  LoaderCircle, AlertTriangle, CheckCircle2,
} from "@lucide/vue";
import { get, post, del, RequestError } from "@/services/request";

// ---- 类型定义 ----
interface KnowledgeDoc {
  resourceId: number;
  resourceName: string;
  resourceCategory: string;
  resourceSubcategory: string | null;
  intro: string | null;
  educationValue: string | null;
  targetGrade: string | null;
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

// ---- 状态 ----
const keyword = ref("");
const docs = ref<KnowledgeDoc[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = 15;
const loading = ref(false);
const notice = reactive({ tone: "" as "" | "success" | "error", text: "" });
const reindexing = ref(false);

// 新建文档表单
const showForm = ref(false);
const saving = ref(false);
const form = reactive({
  resourceName: "",
  resourceCategory: "KNOWLEDGE_BASE",
  intro: "",
  educationValue: "",
  targetGrade: "",
});

// ---- 计算属性 ----
const totalPages = computed(() => Math.ceil(total.value / pageSize) || 1);
const pages = computed(() => {
  const result: number[] = [];
  const tp = totalPages.value;
  const current = pageNum.value;
  let start = Math.max(1, current - 2);
  const end = Math.min(tp, start + 4);
  if (end - start < 4) start = Math.max(1, end - 4);
  for (let i = start; i <= end; i++) result.push(i);
  return result;
});

// ---- 方法 ----
onMounted(() => { void fetchDocs(); });

async function fetchDocs(): Promise<void> {
  loading.value = true;
  notice.text = "";
  try {
    const params: Record<string, unknown> = {
      pageNum: pageNum.value,
      pageSize,
      resourceCategory: "KNOWLEDGE_BASE",
    };
    if (keyword.value.trim()) params.keyword = keyword.value.trim();
    const result = await get<PageResult<KnowledgeDoc>>("/api/admin/resources", params);
    docs.value = result.records ?? [];
    total.value = result.total ?? 0;
  } catch (err) {
    notice.tone = "error";
    notice.text = err instanceof RequestError ? err.message : "加载文档列表失败";
  } finally {
    loading.value = false;
  }
}

function onSearch(): void {
  pageNum.value = 1;
  void fetchDocs();
}

function goPage(p: number): void {
  if (p < 1 || p > totalPages.value || p === pageNum.value) return;
  pageNum.value = p;
  void fetchDocs();
}

async function createDoc(): Promise<void> {
  if (!form.resourceName.trim()) {
    notice.tone = "error";
    notice.text = "请填写文档名称";
    return;
  }
  saving.value = true;
  notice.text = "";
  try {
    await post("/api/admin/resources", {
      resourceName: form.resourceName.trim(),
      resourceCategory: form.resourceCategory,
      intro: form.intro.trim() || null,
      educationValue: form.educationValue.trim() || null,
      targetGrade: form.targetGrade.trim() || null,
    });
    form.resourceName = "";
    form.intro = "";
    form.educationValue = "";
    form.targetGrade = "";
    showForm.value = false;
    notice.tone = "success";
    notice.text = "文档创建成功";
    pageNum.value = 1;
    await fetchDocs();
  } catch (err) {
    notice.tone = "error";
    notice.text = err instanceof RequestError ? err.message : "创建失败";
  } finally {
    saving.value = false;
  }
}

async function deleteDoc(id: number): Promise<void> {
  if (!confirm("确定要删除此文档？删除后不可恢复。")) return;
  notice.text = "";
  try {
    await del(`/api/admin/resources/${id}`);
    notice.tone = "success";
    notice.text = "已删除";
    await fetchDocs();
  } catch (err) {
    notice.tone = "error";
    notice.text = err instanceof RequestError ? err.message : "删除失败";
  }
}

async function reindex(): Promise<void> {
  if (!confirm("确定要重新构建 RAG 向量索引？此操作可能需要几分钟。")) return;
  reindexing.value = true;
  notice.text = "";
  try {
    await post("/api/admin/rag/reindex");
    notice.tone = "success";
    notice.text = "RAG 索引重建已触发";
  } catch (err) {
    notice.tone = "error";
    notice.text = err instanceof RequestError ? err.message : "索引重建失败";
  } finally {
    reindexing.value = false;
  }
}

function categoryLabel(cat: string): string {
  const map: Record<string, string> = {
    KNOWLEDGE_BASE: "知识库",
    RED_SITE: "红色遗址",
    MEMORIAL_HALL: "纪念馆",
    HERO_PERSON: "英雄人物",
    HISTORICAL_EVENT: "历史事件",
    RED_STORY: "红色故事",
  };
  return map[cat] ?? cat;
}

function statusLabel(status: string): string {
  const map: Record<string, string> = {
    DRAFT: "草稿",
    PENDING: "待审核",
    APPROVED: "已通过",
    REJECTED: "已驳回",
  };
  return map[status] ?? status;
}

function dateStr(val: string): string {
  if (!val) return "-";
  const d = new Date(val);
  return Number.isNaN(d.getTime()) ? val : d.toLocaleDateString("zh-CN");
}

function truncate(text: string | null, max = 60): string {
  if (!text) return "-";
  return text.length > max ? text.slice(0, max) + "…" : text;
}
</script>

<template>
  <div class="kb-page">
    <!-- 顶部操作栏 -->
    <div class="kb-toolbar">
      <div class="search-box">
        <Search :size="16" />
        <input
          v-model="keyword"
          placeholder="搜索文档名称…"
          @keyup.enter="onSearch"
        />
        <button class="btn btn-sm" @click="onSearch" :disabled="loading">搜索</button>
      </div>
      <div class="toolbar-actions">
        <button class="btn btn-sm btn-outline" @click="reindex" :disabled="reindexing">
          <RefreshCw :size="14" :class="{ spinning: reindexing }" />
          {{ reindexing ? "重建中…" : "重建索引" }}
        </button>
        <button class="btn btn-sm btn-primary" @click="showForm = !showForm">
          <Upload :size="14" />
          新建文档
        </button>
      </div>
    </div>

    <!-- 通知 -->
    <div v-if="notice.text" :class="['notice', `notice-${notice.tone}`]">
      <CheckCircle2 v-if="notice.tone === 'success'" :size="16" />
      <AlertTriangle v-else :size="16" />
      {{ notice.text }}
    </div>

    <!-- 新建表单 -->
    <div v-if="showForm" class="kb-form-card">
      <h3>新建知识库文档</h3>
      <div class="form-grid">
        <label>
          文档名称 <span class="required">*</span>
          <input v-model="form.resourceName" placeholder="输入文档标题" />
        </label>
        <label>
          适用年级
          <input v-model="form.targetGrade" placeholder="例：四年级" />
        </label>
        <label class="span-2">
          内容简介
          <textarea v-model="form.intro" rows="3" placeholder="文档内容摘要，将用于 RAG 检索"></textarea>
        </label>
        <label class="span-2">
          教育价值说明
          <textarea v-model="form.educationValue" rows="2" placeholder="思政教育价值描述"></textarea>
        </label>
      </div>
      <div class="form-actions">
        <button class="btn btn-sm" @click="showForm = false">取消</button>
        <button class="btn btn-sm btn-primary" @click="createDoc" :disabled="saving">
          <LoaderCircle v-if="saving" :size="14" class="spinning" />
          {{ saving ? "保存中…" : "保存" }}
        </button>
      </div>
    </div>

    <!-- 文档列表 -->
    <div class="kb-table-wrap">
      <div v-if="loading" class="loading-state">
        <LoaderCircle :size="20" class="spinning" /> 加载中…
      </div>

      <table v-else-if="docs.length">
        <thead>
          <tr>
            <th>文档名称</th>
            <th>分类</th>
            <th>适用年级</th>
            <th>内容摘要</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="doc in docs" :key="doc.resourceId">
            <td>
              <div class="doc-name">
                <FileText :size="14" />
                <strong>{{ doc.resourceName }}</strong>
              </div>
            </td>
            <td><span class="badge">{{ categoryLabel(doc.resourceCategory) }}</span></td>
            <td>{{ doc.targetGrade || "-" }}</td>
            <td class="intro-cell">{{ truncate(doc.intro) }}</td>
            <td>
              <span :class="['badge', doc.reviewStatus === 'APPROVED' ? 'badge-green' : '']">
                {{ statusLabel(doc.reviewStatus) }}
              </span>
            </td>
            <td>{{ dateStr(doc.createdAt) }}</td>
            <td>
              <button class="btn-icon" title="删除" @click="deleteDoc(doc.resourceId)">
                <Trash2 :size="14" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-else class="empty-state">
        <FileText :size="32" />
        <span>暂无知识库文档</span>
      </div>
    </div>

    <!-- 分页 -->
    <div v-if="totalPages > 1" class="pagination">
      <button :disabled="pageNum <= 1" @click="goPage(pageNum - 1)">上一页</button>
      <button
        v-for="p in pages"
        :key="p"
        :class="{ active: p === pageNum }"
        @click="goPage(p)"
      >
        {{ p }}
      </button>
      <button :disabled="pageNum >= totalPages" @click="goPage(pageNum + 1)">下一页</button>
      <span class="page-info">共 {{ total }} 条</span>
    </div>
  </div>
</template>

<style scoped>
.kb-page { max-width: 960px; margin: 0 auto; padding: 16px 0; }
.kb-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
.search-box { display: flex; align-items: center; gap: 8px; padding: 6px 12px; border: 1px solid var(--line); border-radius: 8px; background: #fff; flex: 1; min-width: 200px; }
.search-box input { border: none; outline: none; flex: 1; font-size: 14px; }
.toolbar-actions { display: flex; gap: 8px; align-items: center; }

.notice { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-radius: 8px; font-size: 14px; margin-bottom: 16px; }
.notice-success { background: #ecfdf5; color: #065f46; }
.notice-error { background: #fef2f2; color: #991b1b; }

.kb-form-card { padding: 20px; background: #fff; border-radius: 10px; border: 1px solid var(--line); margin-bottom: 16px; }
.kb-form-card h3 { margin: 0 0 16px; font-size: 16px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.span-2 { grid-column: span 2; }
.form-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--muted); }
.form-grid input, .form-grid textarea, .form-grid select { padding: 8px 12px; border: 1px solid var(--line); border-radius: 6px; font-size: 14px; }
.form-grid textarea { resize: vertical; }
.required { color: var(--red); }
.form-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }

.kb-table-wrap { background: #fff; border-radius: 10px; border: 1px solid var(--line); overflow: hidden; }
.loading-state { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 40px; color: var(--muted); }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 12px 16px; border-bottom: 1px solid var(--line); text-align: left; font-size: 13px; }
th { background: #f7f8f6; color: var(--muted); font-size: 12px; font-weight: 600; }
.doc-name { display: flex; align-items: center; gap: 8px; }
.intro-cell { max-width: 200px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--muted); }

.badge { display: inline-block; padding: 3px 10px; border-radius: 99px; background: #f0f0f0; font-size: 12px; }
.badge-green { background: #ecfdf5; color: #065f46; }

.btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; border: 1px solid var(--line); border-radius: 8px; background: #fff; cursor: pointer; font-size: 13px; }
.btn-primary { background: var(--green); color: #fff; border-color: var(--green); }
.btn-outline { border-color: var(--line); }
.btn-sm { padding: 6px 12px; font-size: 12px; }
.btn-icon { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: none; border-radius: 6px; background: transparent; color: var(--muted); cursor: pointer; }
.btn-icon:hover { background: #fef2f2; color: var(--red); }

.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 48px 0; color: var(--muted); }

.pagination { display: flex; align-items: center; gap: 6px; margin-top: 16px; }
.pagination button { padding: 6px 12px; border: 1px solid var(--line); border-radius: 6px; background: #fff; cursor: pointer; font-size: 13px; }
.pagination button.active { background: var(--green); color: #fff; border-color: var(--green); }
.pagination button:disabled { opacity: .4; cursor: default; }
.page-info { margin-left: 8px; font-size: 13px; color: var(--muted); }

.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 720px) {
  .form-grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: span 1; }
  table { font-size: 12px; }
  th, td { padding: 8px 10px; }
}
</style>
