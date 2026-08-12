<script setup lang="ts">
// ============================================================
// 会话历史查询页面（管理后台）
// 对接 AgentAdminController: /api/admin/agent/observability/traces
// ============================================================
import { computed, onMounted, ref } from "vue";
import {
  MessageSquare, Search, LoaderCircle,
  AlertTriangle, Clock, User, Bot, ExternalLink,
} from "@lucide/vue";
import { api, ApiError, withQuery } from "@/services/api";

// ---- 类型 ----
interface AgentTrace {
  traceId?: string;
  runId?: string;
  threadId?: string;
  ownerId?: string;
  scopeType?: string;
  scopeId?: number | string;
  taskType?: string;
  status?: string;
  durationMs?: number;
  modelProvider?: string;
  modelName?: string;
  createdAt?: string;
  errorMessage?: string;
  [key: string]: unknown;
}

// ---- 状态 ----
const traces = ref<AgentTrace[]>([]);
const loading = ref(false);
const error = ref("");
const filters = ref<Record<string, string>>({
  limit: "50",
});

// ---- 计算 ----
const hasTraces = computed(() => traces.value.length > 0);

// ---- 方法 ----
onMounted(() => { void fetchTraces(); });

async function fetchTraces(): Promise<void> {
  loading.value = true;
  error.value = "";
  try {
    // 后端 AgentAdminController 的 traces 接口接受 filters Map
    const result = await api.get<AgentTrace[]>(withQuery(
      "/api/admin/agent/observability/traces",
      filters.value,
    ));
    traces.value = Array.isArray(result) ? result : [];
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : "加载会话记录失败";
    traces.value = [];
  } finally {
    loading.value = false;
  }
}

function onSearch(): void {
  void fetchTraces();
}

function statusLabel(status?: string): string {
  const map: Record<string, string> = {
    completed: "已完成",
    running: "运行中",
    failed: "失败",
    cancelled: "已取消",
  };
  return map[status ?? ""] ?? status ?? "未知";
}

function statusClass(status?: string): string {
  return `status-${status ?? "unknown"}`;
}

function taskTypeLabel(type?: string): string {
  const map: Record<string, string> = {
    CHAT: "智能问答",
    TEACHING_PLAN: "教学方案",
  };
  return map[type ?? ""] ?? type ?? "-";
}

function durationStr(ms?: number): string {
  if (ms === undefined || ms === null) return "-";
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function dateStr(val?: string): string {
  if (!val) return "-";
  const d = new Date(val);
  if (Number.isNaN(d.getTime())) return val;
  return d.toLocaleString("zh-CN");
}

function ownerLabel(owner?: string): string {
  if (!owner) return "-";
  return owner.replace("account:", "");
}
</script>

<template>
  <div class="ch-page">
    <div class="ch-header">
      <h2><MessageSquare :size="20" /> 会话历史记录</h2>
      <div class="search-box">
        <Search :size="14" />
        <input
          v-model="filters.limit"
          placeholder="条数限制（默认 50）"
          @keyup.enter="onSearch"
          style="width: 140px"
        />
        <button class="btn btn-sm" @click="onSearch" :disabled="loading">刷新</button>
      </div>
    </div>

    <!-- 错误提示 -->
    <div v-if="error" class="notice notice-error">
      <AlertTriangle :size="16" /> {{ error }}
      <button class="btn btn-sm" @click="fetchTraces">重试</button>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state">
      <LoaderCircle :size="20" class="spinning" /> 加载中…
    </div>

    <!-- 会话列表 -->
    <div v-else-if="hasTraces" class="trace-list">
      <div v-for="trace in traces" :key="trace.traceId ?? trace.runId" class="trace-card">
        <div class="trace-header">
          <div class="trace-type">
            <span :class="['badge', statusClass(trace.status)]">
              {{ statusLabel(trace.status) }}
            </span>
            <span class="badge">{{ taskTypeLabel(trace.taskType) }}</span>
          </div>
          <div class="trace-meta">
            <span><Clock :size="12" /> {{ durationStr(trace.durationMs) }}</span>
            <span><User :size="12" /> {{ ownerLabel(trace.ownerId) }}</span>
            <span class="trace-date">{{ dateStr(trace.createdAt) }}</span>
          </div>
        </div>
        <div class="trace-body">
          <div class="trace-field" v-if="trace.threadId">
            <span class="label">会话 ID</span>
            <code>{{ trace.threadId }}</code>
          </div>
          <div class="trace-field" v-if="trace.modelName">
            <span class="label">模型</span>
            <span>{{ trace.modelProvider ?? "" }} / {{ trace.modelName }}</span>
          </div>
          <div class="trace-field" v-if="trace.scopeType">
            <span class="label">作用域</span>
            <span>{{ trace.scopeType }} #{{ trace.scopeId }}</span>
          </div>
          <div v-if="trace.errorMessage" class="trace-error">
            <AlertTriangle :size="14" />
            {{ trace.errorMessage }}
          </div>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <MessageSquare :size="36" />
      <span>暂无会话记录</span>
      <span class="hint">Agent 服务启动并产生对话后，记录将显示在此处</span>
    </div>
  </div>
</template>

<style scoped>
.ch-page { max-width: 860px; margin: 0 auto; padding: 16px 0; }
.ch-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
.ch-header h2 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 18px; }

.search-box { display: flex; align-items: center; gap: 8px; padding: 4px 10px; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
.search-box input { border: none; outline: none; font-size: 13px; width: 100px; }

.notice { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-radius: 8px; font-size: 14px; margin-bottom: 12px; }
.notice-error { background: #fef2f2; color: #991b1b; }

.loading-state { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 40px; color: var(--muted); }

.trace-list { display: flex; flex-direction: column; gap: 10px; }
.trace-card { padding: 16px; background: #fff; border: 1px solid var(--line); border-radius: 10px; }
.trace-header { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.trace-type { display: flex; gap: 6px; }
.trace-meta { display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--muted); }
.trace-meta span { display: flex; align-items: center; gap: 4px; }
.trace-date { white-space: nowrap; }

.trace-body { display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
.trace-field { display: flex; gap: 12px; }
.trace-field .label { min-width: 60px; color: var(--muted); }
.trace-field code { font-size: 12px; background: #f5f5f5; padding: 1px 6px; border-radius: 4px; }
.trace-error { display: flex; align-items: center; gap: 6px; margin-top: 6px; padding: 8px 12px; background: #fef2f2; border-radius: 6px; color: #991b1b; font-size: 12px; }

.badge { display: inline-block; padding: 3px 10px; border-radius: 99px; background: #f0f0f0; font-size: 12px; }
.status-completed { background: #ecfdf5; color: #065f46; }
.status-failed { background: #fef2f2; color: #991b1b; }
.status-running { background: #eff6ff; color: #1e40af; }
.status-cancelled { background: #f5f5f5; color: #737373; }

.btn { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border: 1px solid var(--line); border-radius: 8px; background: #fff; cursor: pointer; font-size: 12px; }
.btn-sm { padding: 4px 10px; }

.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 56px 0; color: var(--muted); }
.empty-state .hint { font-size: 12px; color: #b0b0b0; }

.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
