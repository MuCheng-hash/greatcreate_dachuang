<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { AlertTriangle, Bot, Check, CircleStop, Clock3, Code2, LoaderCircle, Play, RotateCcw, Wrench } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";
import { useSchoolStore } from "@/stores/school";
import type { AgentQaRequestPayload, AgentQaResponse, AgentSseEventData, AgentSseEventName, LlmModelOption } from "@/types/agent";

interface DebugEvent {
  id: number;
  name: AgentSseEventName;
  data: AgentSseEventData;
  timestamp: number;
  elapsedMs: number;
}

const auth = useAuthStore();
const school = useSchoolStore();
const models = ref<LlmModelOption[]>([]);
const selectedModelId = ref("");
const prompt = ref("请介绍本校周边适合开展思政实践教学的资源，并说明依据。");
const running = ref(false);
const error = ref("");
const events = ref<DebugEvent[]>([]);
const answer = ref("");
const finalResponse = ref<AgentQaResponse | null>(null);
const startedAt = ref(0);
const finishedAt = ref(0);
const abortController = ref<AbortController | null>(null);
const eventScroll = ref<HTMLElement | null>(null);

const durationMs = computed(() => {
  if (!startedAt.value) return 0;
  const latestEventAt = events.value.length ? events.value[events.value.length - 1].timestamp : startedAt.value;
  return (finishedAt.value || latestEventAt) - startedAt.value;
});
const toolCount = computed(() => events.value.filter(item => item.name === "tool.completed").length);
const errorCount = computed(() => events.value.filter(item => item.name === "error" || item.name === "model.failed").length);
const runId = computed(() => String(events.value.find(item => item.data.runId)?.data.runId || finalResponse.value?.runId || "—"));
const stages = computed(() => events.value.filter(item => item.name === "phase.started" || item.name === "phase.completed" || item.name.startsWith("model.") || item.name.startsWith("tool.")));

onMounted(async () => {
  await school.load();
  try {
    models.value = await api.get<LlmModelOption[]>("/api/ai/models");
    selectedModelId.value = models.value.find(item => item.isDefault)?.id || "";
  } catch {
    models.value = [];
  }
});

function reset(): void {
  if (running.value) return;
  events.value = [];
  answer.value = "";
  finalResponse.value = null;
  error.value = "";
  startedAt.value = 0;
  finishedAt.value = 0;
}

function stop(): void {
  abortController.value?.abort();
}

async function run(): Promise<void> {
  const question = prompt.value.trim();
  if (!question || running.value) return;
  reset();
  running.value = true;
  startedAt.value = Date.now();
  const controller = new AbortController();
  abortController.value = controller;
  const request: AgentQaRequestPayload = {
    question,
    scopeType: "SCHOOL",
    scopeId: school.school?.schoolId || auth.user?.schoolId || null,
    conversationId: globalThis.crypto?.randomUUID?.() || `debug-${Date.now()}`,
    ...(selectedModelId.value ? { modelId: selectedModelId.value } : {})
  };
  try {
    await api.stream("/api/ai/qa/stream", request, {
      signal: controller.signal,
      onEvent(name: AgentSseEventName, data: AgentSseEventData) {
        events.value.push({ id: events.value.length + 1, name, data, timestamp: Date.now(), elapsedMs: Date.now() - startedAt.value });
        if (name === "token") answer.value += data.delta || "";
        if (name === "final") {
          finalResponse.value = data.response || null;
          answer.value = data.response?.answer || answer.value;
        }
        if (name === "error") error.value = data.message || "Agent 运行失败";
        void nextTick(() => { if (eventScroll.value) eventScroll.value.scrollTop = eventScroll.value.scrollHeight; });
      }
    });
  } catch (runError) {
    if ((runError as Error).name !== "AbortError") error.value = (runError as Error).message;
    else error.value = "调试运行已停止";
  } finally {
    finishedAt.value = Date.now();
    running.value = false;
    abortController.value = null;
  }
}

function eventTitle(event: DebugEvent): string {
  if (event.data.label) return event.data.label;
  if (event.name.startsWith("tool.")) return `${event.data.toolName || event.data.name || "工具"} · ${event.name.endsWith("started") ? "开始" : "完成"}`;
  if (event.name.startsWith("model.")) return `${event.data.provider || "LLM"} / ${event.data.model || "模型"} · ${event.name.split(".")[1]}`;
  return event.name;
}

function eventState(event: DebugEvent): string {
  if (event.name === "error" || event.name.endsWith("failed") || event.data.status === "failed") return "failed";
  if (event.name.endsWith("started")) return "running";
  return "completed";
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2);
}
</script>

<template>
  <AppShell title="Agent 调试" subtitle="实时检查模型、工具与流式事件，不展示模型私有思维链">
    <section class="debug-layout">
      <aside class="debug-config page-panel">
        <div class="panel-title"><Bot :size="19" /><div><h2>运行配置</h2><p>范围固定为当前学校</p></div></div>
        <label>学校范围<input :value="school.school?.schoolName || auth.schoolLabel" disabled /></label>
        <label>模型<select v-model="selectedModelId" :disabled="running"><option value="">系统默认</option><option v-for="model in models" :key="model.id" :value="model.id">{{ model.displayName }} · {{ model.provider }}</option></select></label>
        <label class="prompt-field">测试问题<textarea v-model="prompt" rows="9" :disabled="running" /></label>
        <div class="debug-actions">
          <button v-if="!running" class="primary-button" type="button" :disabled="!prompt.trim()" @click="run"><Play :size="16" />开始运行</button>
          <button v-else class="stop-debug" type="button" @click="stop"><CircleStop :size="16" />停止运行</button>
          <button class="reset-debug" type="button" :disabled="running" @click="reset"><RotateCcw :size="16" />清空</button>
        </div>
      </aside>

      <main class="debug-main">
        <InlineNotice v-if="error" :tone="error.includes('停止') ? 'info' : 'error'">{{ error }}</InlineNotice>
        <section class="debug-metrics page-panel">
          <div><span>状态</span><strong :class="{ live: running }"><LoaderCircle v-if="running" class="spin" :size="16" /><Check v-else-if="finishedAt" :size="16" />{{ running ? "运行中" : finishedAt ? "已结束" : "待运行" }}</strong></div>
          <div><span>总耗时</span><strong><Clock3 :size="15" />{{ durationMs }} ms</strong></div>
          <div><span>工具调用</span><strong><Wrench :size="15" />{{ toolCount }}</strong></div>
          <div><span>异常事件</span><strong :class="{ danger: errorCount }"><AlertTriangle :size="15" />{{ errorCount }}</strong></div>
        </section>

        <section class="debug-grid">
          <div class="trace-panel page-panel">
            <header><div><h2>执行链路</h2><p>Run ID：{{ runId }}</p></div><span>{{ stages.length }} 个节点</span></header>
            <div v-if="!stages.length" class="debug-empty">运行后将在这里显示检索、模型和工具节点</div>
            <div v-else class="debug-trace">
              <div v-for="event in stages" :key="event.id" class="debug-node" :class="eventState(event)">
                <span class="node-dot"><LoaderCircle v-if="eventState(event) === 'running'" class="spin" :size="14" /><AlertTriangle v-else-if="eventState(event) === 'failed'" :size="14" /><Check v-else :size="14" /></span>
                <div><strong>{{ eventTitle(event) }}</strong><small>+{{ event.elapsedMs }} ms<span v-if="event.data.durationMs !== undefined"> · 耗时 {{ event.data.durationMs }} ms</span></small><code v-if="event.data.outputSummary">{{ event.data.outputSummary }}</code></div>
              </div>
            </div>
          </div>

          <div class="event-panel page-panel">
            <header><div><h2>原始 SSE 事件</h2><p>按接收顺序实时追加</p></div><Code2 :size="18" /></header>
            <div ref="eventScroll" class="event-stream">
              <div v-if="!events.length" class="debug-empty">暂无事件</div>
              <details v-for="event in events" v-else :key="event.id">
                <summary><span>{{ event.id }}</span><strong>{{ event.name }}</strong><time>+{{ event.elapsedMs }} ms</time></summary>
                <pre>{{ pretty(event.data) }}</pre>
              </details>
            </div>
          </div>
        </section>

        <section class="result-panel page-panel">
          <header><div><h2>模型输出</h2><p>{{ answer.length }} 个字符</p></div><span v-if="finalResponse?.model">{{ finalResponse.provider }} / {{ finalResponse.model }}</span></header>
          <div v-if="answer" class="debug-answer">{{ answer }}</div><div v-else class="debug-empty">等待模型输出</div>
          <details v-if="finalResponse" class="final-json"><summary>查看最终响应 JSON</summary><pre>{{ pretty(finalResponse) }}</pre></details>
        </section>
      </main>
    </section>
  </AppShell>
</template>

<style scoped>
.debug-layout { display: grid; grid-template-columns: 300px minmax(0,1fr); gap: 16px; align-items: start; }
.page-panel { border: 1px solid var(--line); border-radius: 8px; background: #fff; }
.debug-config { position: sticky; top: 0; display: grid; gap: 16px; padding: 18px; }
.panel-title { display: flex; gap: 10px; align-items: center; color: var(--green); }
.panel-title h2, .debug-main h2 { margin: 0; color: var(--text); font-size: 16px; }
.panel-title p, header p { margin: 3px 0 0; color: var(--muted); font-size: 11px; }
.debug-config label { display: grid; gap: 7px; color: var(--muted); font-size: 12px; }
.debug-config textarea { resize: vertical; min-height: 150px; line-height: 1.6; }
.debug-actions { display: grid; grid-template-columns: 1fr auto; gap: 7px; }
.debug-actions button { display: flex; min-height: 40px; align-items: center; justify-content: center; gap: 6px; border-radius: 6px; cursor: pointer; }
.debug-actions .reset-debug { grid-column: 1 / -1; border: 1px solid var(--line); background: #fff; color: var(--muted); }
.stop-debug { border: 1px solid #d4a5a0; background: var(--red-soft); color: var(--red); }
.debug-main { display: grid; gap: 14px; min-width: 0; }
.debug-metrics { display: grid; grid-template-columns: repeat(4,1fr); overflow: hidden; }
.debug-metrics > div { display: grid; gap: 7px; padding: 14px 16px; border-right: 1px solid var(--line); }
.debug-metrics > div:last-child { border-right: 0; }
.debug-metrics span { color: var(--muted); font-size: 11px; }
.debug-metrics strong { display: flex; align-items: center; gap: 6px; font-size: 15px; }
.debug-metrics strong.live { color: #9b711b; }.debug-metrics strong.danger { color: var(--red); }
.debug-grid { display: grid; grid-template-columns: minmax(0,1fr) minmax(340px,.85fr); gap: 14px; min-height: 420px; }
.trace-panel, .event-panel, .result-panel { min-width: 0; overflow: hidden; }
header { display: flex; min-height: 58px; align-items: center; justify-content: space-between; gap: 12px; padding: 0 16px; border-bottom: 1px solid var(--line); }
header > span, header > svg { color: var(--muted); font-size: 11px; }
.debug-empty { display: grid; min-height: 120px; place-items: center; padding: 24px; color: var(--muted); font-size: 12px; text-align: center; }
.debug-trace { display: grid; padding: 18px; }
.debug-node { position: relative; display: grid; grid-template-columns: 28px minmax(0,1fr); gap: 8px; min-height: 64px; }
.debug-node:not(:last-child)::after { content: ""; position: absolute; left: 9px; top: 23px; bottom: 0; width: 2px; background: var(--line); }
.node-dot { z-index: 1; display: grid; width: 20px; height: 20px; place-items: center; border-radius: 50%; background: #e7f0e9; color: var(--green); }
.debug-node.running .node-dot { background: #fff4d8; color: #9b711b; }.debug-node.failed .node-dot { background: var(--red-soft); color: var(--red); }
.debug-node strong, .debug-node small, .debug-node code { display: block; }
.debug-node strong { font-size: 13px; }.debug-node small { margin-top: 3px; color: var(--muted); font-size: 10px; }
.debug-node code { margin-top: 6px; padding: 5px 7px; border-radius: 4px; background: var(--surface-muted); color: #536158; font-size: 10px; white-space: normal; overflow-wrap: anywhere; }
.event-stream { height: 360px; overflow: auto; background: #17201a; color: #d8e2da; }
.event-stream .debug-empty { color: #85968a; }
.event-stream details { border-bottom: 1px solid #2e3931; }
.event-stream summary { display: grid; grid-template-columns: 28px minmax(0,1fr) auto; gap: 7px; padding: 9px 12px; cursor: pointer; font-size: 11px; }
.event-stream summary span { color: #728077; }.event-stream summary strong { color: #b7d7bf; }.event-stream time { color: #829188; }
.event-stream pre, .final-json pre { margin: 0; padding: 10px 14px; overflow: auto; font-size: 10px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; }
.result-panel header span { color: var(--green); }
.debug-answer { padding: 18px; line-height: 1.8; white-space: pre-wrap; }
.final-json { margin: 0 18px 18px; border: 1px solid var(--line); border-radius: 5px; }
.final-json summary { padding: 8px 10px; color: var(--muted); font-size: 11px; cursor: pointer; }
.final-json pre { max-height: 300px; background: #f7f9f7; }
.spin { animation: spin 900ms linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1100px) { .debug-layout { grid-template-columns: 1fr; }.debug-config { position: static; }.debug-grid { grid-template-columns: 1fr; }.debug-metrics { grid-template-columns: repeat(2,1fr); }.debug-metrics > div:nth-child(2) { border-right: 0; }.debug-metrics > div:nth-child(-n+2) { border-bottom: 1px solid var(--line); } }
@media (max-width: 620px) { .debug-metrics { grid-template-columns: 1fr 1fr; }.debug-config { padding: 14px; }.debug-grid { min-height: 0; } }
</style>
