<script setup lang="ts">
// ============================================================
// 日志展示面板 — Agent 调用耗时、失败统计
// 对接 AgentAdminController:
//   GET /api/admin/agent/observability/summary
//   GET /api/admin/agent/observability/traces
// ============================================================
import { computed, onMounted, ref } from "vue";
import {
  BarChart3, LoaderCircle, AlertTriangle,
  CheckCircle2, XCircle, Clock, Activity,
  RefreshCw, Zap,
} from "@lucide/vue";
import { api, ApiError, withQuery } from "@/services/api";

// ---- 类型 ----
interface ObservabilitySummary {
  totalTraces?: number;
  successTraces?: number;
  failedTraces?: number;
  avgDurationMs?: number;
  totalTokens?: number;
  [key: string]: unknown;
}

interface AgentTrace {
  traceId?: string;
  runId?: string;
  threadId?: string;
  taskType?: string;
  status?: string;
  durationMs?: number;
  modelName?: string;
  createdAt?: string;
  errorMessage?: string;
  [key: string]: unknown;
}

// ---- 状态 ----
const summary = ref<ObservabilitySummary | null>(null);
const traces = ref<AgentTrace[]>([]);
const loading = ref(false);
const error = ref("");
const showAll = ref(false);

const displayLimit = 20;

// ---- 计算 ----
const successRate = computed(() => {
  if (!summary.value?.totalTraces) return 0;
  return Math.round(((summary.value.successTraces ?? 0) / summary.value.totalTraces) * 100);
});

const failRate = computed(() => {
  if (!summary.value?.totalTraces) return 0;
  return Math.round(((summary.value.failedTraces ?? 0) / summary.value.totalTraces) * 100);
});

const visibleTraces = computed(() => {
  const list = traces.value;
  return showAll.value ? list : list.slice(0, displayLimit);
});

const failedTraces = computed(() =>
  traces.value.filter((t) => t.status === "failed")
);

// ---- 方法 ----
onMounted(() => { void loadAll(); });

async function loadAll(): Promise<void> {
  loading.value = true;
  error.value = "";
  await Promise.all([loadSummary(), loadTraces()]);
  loading.value = false;
}

async function loadSummary(): Promise<void> {
  try {
    summary.value = await api.get<ObservabilitySummary>(
      "/api/admin/agent/observability/summary",
    );
  } catch {
    summary.value = null;
  }
}

async function loadTraces(): Promise<void> {
  try {
    const result = await api.get<AgentTrace[]>(withQuery(
      "/api/admin/agent/observability/traces",
      { limit: "100" },
    ));
    traces.value = Array.isArray(result) ? result : [];
  } catch (err) {
    if (!error.value) {
    error.value = err instanceof ApiError ? err.message : "加载日志失败";
    }
    traces.value = [];
  }
}

function durationStr(ms?: number): string {
  if (ms === undefined || ms === null) return "-";
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function dateStr(val?: string): string {
  if (!val) return "-";
  const d = new Date(val);
  return Number.isNaN(d.getTime()) ? val : d.toLocaleString("zh-CN");
}

function statusClass(s?: string): string {
  return `s-${s ?? "unknown"}`;
}

function statusIcon(s?: string): string {
  if (s === "completed") return "check";
  if (s === "failed") return "x";
  return "clock";
}

function taskTypeLabel(t?: string): string {
  const map: Record<string, string> = { CHAT: "问答", TEACHING_PLAN: "方案" };
  return map[t ?? ""] ?? t ?? "-";
}

function barPercent(value: number, max: number): string {
  if (!max) return "0%";
  return `${Math.min(100, Math.round((value / max) * 100))}%`;
}
</script>

<template>
  <div class="log-page">
    <div class="log-header">
      <h2><BarChart3 :size="20" /> Agent 日志面板</h2>
      <button class="btn btn-sm" @click="loadAll" :disabled="loading">
        <RefreshCw :size="14" :class="{ spinning: loading }" />
        刷新
      </button>
    </div>

    <!-- 错误 -->
    <div v-if="error" class="notice notice-error">
      <AlertTriangle :size="14" /> {{ error }}
    </div>

    <!-- 统计卡片 -->
    <div v-if="summary" class="stats-row">
      <div class="stat-card">
        <div class="stat-icon s-blue"><Activity :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ summary.totalTraces ?? 0 }}</span>
          <span class="stat-label">总调用次数</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon s-green"><CheckCircle2 :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ successRate }}%</span>
          <span class="stat-label">成功率</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon s-red"><XCircle :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ summary.failedTraces ?? 0 }}</span>
          <span class="stat-label">失败次数</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon s-amber"><Clock :size="20" /></div>
        <div class="stat-body">
          <span class="stat-value">{{ durationStr(summary.avgDurationMs) }}</span>
          <span class="stat-label">平均耗时</span>
        </div>
      </div>
    </div>

    <!-- 加载 -->
    <div v-if="loading" class="loading-state">
      <LoaderCircle :size="18" class="spinning" /> 加载中…
    </div>

    <!-- 进度条 -->
    <div v-if="summary && !loading" class="progress-bar-wrap">
      <div class="progress-bar">
        <div class="bar-success" :style="{ width: barPercent(summary.successTraces ?? 0, summary.totalTraces ?? 1) }"></div>
        <div class="bar-fail" :style="{ width: barPercent(summary.failedTraces ?? 0, summary.totalTraces ?? 1) }"></div>
      </div>
      <div class="progress-legend">
        <span><span class="dot dot-green"></span>成功 {{ summary.successTraces ?? 0 }}</span>
        <span><span class="dot dot-red"></span>失败 {{ summary.failedTraces ?? 0 }}</span>
      </div>
    </div>

    <!-- 失败列表 -->
    <div v-if="failedTraces.length && !loading" class="fail-section">
      <h3><AlertTriangle :size="16" /> 最近失败记录</h3>
      <div class="fail-list">
        <div v-for="trace in failedTraces.slice(0, 5)" :key="trace.traceId ?? trace.runId" class="fail-item">
          <span class="fail-type">{{ taskTypeLabel(trace.taskType) }}</span>
          <span class="fail-model">{{ trace.modelName ?? "-" }}</span>
          <span class="fail-msg">{{ trace.errorMessage ?? "未知错误" }}</span>
          <span class="fail-time">{{ dateStr(trace.createdAt) }}</span>
        </div>
      </div>
    </div>

    <!-- 调用明细 -->
    <div v-if="visibleTraces.length && !loading" class="trace-table-wrap">
      <h3><Zap :size="16" /> 调用明细</h3>
      <table>
        <thead>
          <tr>
            <th>类型</th>
            <th>状态</th>
            <th>耗时</th>
            <th>模型</th>
            <th>时间</th>
            <th>错误信息</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="trace in visibleTraces" :key="trace.traceId ?? trace.runId">
            <td><span class="badge">{{ taskTypeLabel(trace.taskType) }}</span></td>
            <td>
              <span :class="['status-badge', statusClass(trace.status)]">
                <CheckCircle2 v-if="statusIcon(trace.status) === 'check'" :size="12" />
                <XCircle v-else-if="statusIcon(trace.status) === 'x'" :size="12" />
                <Clock v-else :size="12" />
                {{ trace.status ?? "unknown" }}
              </span>
            </td>
            <td><code>{{ durationStr(trace.durationMs) }}</code></td>
            <td>{{ trace.modelName ?? "-" }}</td>
            <td class="date-cell">{{ dateStr(trace.createdAt) }}</td>
            <td class="err-cell">{{ trace.errorMessage ?? "-" }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 展开/收起 -->
      <div v-if="traces.length > displayLimit" class="show-more">
        <button class="btn btn-sm" @click="showAll = !showAll">
          {{ showAll ? "收起" : `显示全部（${traces.length} 条）` }}
        </button>
      </div>
    </div>

    <!-- 空 -->
    <div v-if="!loading && !traces.length" class="empty-state">
      <Activity :size="36" />
      <span>暂无调用日志</span>
      <span class="hint">Agent 服务产生调用后，日志将自动显示</span>
    </div>
  </div>
</template>

<style scoped>
.log-page { max-width: 960px; margin: 0 auto; padding: 16px 0; }
.log-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.log-header h2 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 18px; }

.notice { display: flex; align-items: center; gap: 6px; padding: 10px 14px; border-radius: 8px; font-size: 13px; margin-bottom: 12px; }
.notice-error { background: #fef2f2; color: #991b1b; }

.loading-state { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 40px; color: var(--muted); }

/* 统计卡片 */
.stats-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.stat-card { display: flex; align-items: center; gap: 14px; padding: 18px; background: #fff; border: 1px solid var(--line); border-radius: 10px; }
.stat-icon { display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 10px; }
.s-blue { background: #eff6ff; color: #1e40af; }
.s-green { background: #ecfdf5; color: #065f46; }
.s-red { background: #fef2f2; color: #991b1b; }
.s-amber { background: #fffbeb; color: #92400e; }
.stat-body { display: flex; flex-direction: column; }
.stat-value { font-size: 22px; font-weight: 700; }
.stat-label { font-size: 12px; color: var(--muted); }

/* 进度条 */
.progress-bar-wrap { margin-bottom: 16px; }
.progress-bar { display: flex; height: 12px; border-radius: 6px; overflow: hidden; background: #f0f0f0; }
.bar-success { background: var(--green); transition: width .4s; }
.bar-fail { background: var(--red); transition: width .4s; }
.progress-legend { display: flex; gap: 16px; margin-top: 6px; font-size: 12px; color: var(--muted); }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
.dot-green { background: var(--green); }
.dot-red { background: var(--red); }

/* 失败列表 */
.fail-section { margin-bottom: 16px; }
.fail-section h3, .trace-table-wrap h3 { display: flex; align-items: center; gap: 6px; margin: 0 0 10px; font-size: 15px; }
.fail-list { display: flex; flex-direction: column; gap: 6px; }
.fail-item { display: flex; align-items: center; gap: 12px; padding: 10px 14px; background: #fff; border: 1px solid #fecaca; border-radius: 8px; font-size: 13px; }
.fail-type { font-weight: 600; }
.fail-model { color: var(--muted); }
.fail-msg { flex: 1; color: #991b1b; }
.fail-time { font-size: 11px; color: #b0b0b0; white-space: nowrap; }

/* 明细表 */
.trace-table-wrap { background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 16px; overflow: hidden; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 10px 14px; border-bottom: 1px solid var(--line); text-align: left; font-size: 13px; }
th { background: #f7f8f6; color: var(--muted); font-size: 12px; }
.date-cell { font-size: 12px; color: var(--muted); white-space: nowrap; }
.err-cell { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #991b1b; font-size: 12px; }

.status-badge { display: inline-flex; align-items: center; gap: 4px; padding: 3px 10px; border-radius: 99px; font-size: 12px; }
.s-completed { background: #ecfdf5; color: #065f46; }
.s-failed { background: #fef2f2; color: #991b1b; }
.s-running { background: #eff6ff; color: #1e40af; }

.badge { display: inline-block; padding: 3px 10px; border-radius: 99px; background: #f0f0f0; font-size: 12px; }

.show-more { text-align: center; margin-top: 12px; }

.btn { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border: 1px solid var(--line); border-radius: 8px; background: #fff; cursor: pointer; font-size: 12px; }
.btn-sm { padding: 4px 10px; }

.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 56px 0; color: var(--muted); }
.empty-state .hint { font-size: 12px; color: #ccc; }

.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 700px) {
  .stats-row { grid-template-columns: repeat(2, 1fr); }
  .fail-item { flex-wrap: wrap; }
  table { font-size: 11px; }
  th, td { padding: 6px 8px; }
}
</style>
