<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { Archive, ArchiveRestore, Bot, BrainCircuit, Check, ChevronDown, Clock3, Copy, History, ImagePlus, LoaderCircle, MessageCircleQuestion, Mic, Plus, Send, Sparkles, Trash2, UserRound, Volume2, VolumeX, Wrench, X } from "@lucide/vue";
import DOMPurify from "dompurify";
import { marked } from "marked";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import MemoryConflictDialog from "@/components/MemoryConflictDialog.vue";
import { api } from "@/services/api";
import { memoryApi } from "@/services/memory";
import { useSchoolStore } from "@/stores/school";
import { useAuthStore, type AuthCurrentUser } from "@/stores/auth";
import type {
  AgentCitation,
  AgentAttachment,
  AgentQaRequestPayload,
  AgentQaResponse,
  AgentSseEventData,
  AgentSseEventName,
  AgentMemoryApplied,
  AgentMemoryConflictPreview,
  AgentMemoryItem,
  AgentToolExecution,
  LlmModelOption,
  AssistantConversationDetail,
  AssistantConversationStoredMessage,
  AssistantConversationTurnRecovery,
  AssistantResponseSnapshot,
  AssistantConversationSummary,
} from "@/types/agent";

interface AssistantToolEvent {
  toolName?: string;
  status?: string;
}

interface AssistantTraceEvent {
  id: string;
  kind: "phase" | "model" | "tool" | "response" | "error";
  title: string;
  detail?: string;
  status: "running" | "completed" | "failed";
  durationMs?: number;
  startedAt: number;
}

interface AssistantMessage extends AgentQaResponse {
  role: "user" | "assistant";
  text?: string;
  answer?: string;
  citations?: Array<AgentCitation | string>;
  toolEvents?: AssistantToolEvent[];
  streamStatus?: string;
  effectiveModel?: string;
  traceEvents?: AssistantTraceEvent[];
  traceExpanded?: boolean;
  attachments?: AgentAttachment[];
  isStreaming?: boolean;
  clientTurnId?: string;
}

interface PendingMemoryCandidate {
  message: AssistantMessage;
  candidate: AgentMemoryItem;
}

interface PendingMemoryConflict extends PendingMemoryCandidate {
  preview: AgentMemoryConflictPreview;
}

interface PendingAssistantRetry {
  userText: string;
  attachments: AgentAttachment[];
}

interface StreamRenderState {
  pending: string;
  frameId: number | null;
  frameType: "raf" | "timeout" | null;
  cancelled: boolean;
}

interface SpeechRecognitionLike {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  start(): void;
  stop(): void;
  onresult: ((event: { results: ArrayLike<{ 0: { transcript: string }; isFinal: boolean }> }) => void) | null;
  onerror: (() => void) | null;
  onend: (() => void) | null;
}

type HistoryMode = "active" | "archived";

const auth = useAuthStore();
const schoolStore = useSchoolStore();
const question = ref<string>("");
const loading = ref<boolean>(false);
const error = ref<string>("");
const chatScroll = ref<HTMLElement | null>(null);
const chatAutoFollow = ref<boolean>(true);
const messages = ref<AssistantMessage[]>(loadMessages());
const conversationId = ref<string>(loadConversationId());
const activeAbortController = ref<AbortController | null>(null);
const threadId = ref<string>(loadThreadId());
const models = ref<LlmModelOption[]>([]);
const selectedModelId = ref<string>("");
const history = ref<AssistantConversationSummary[]>([]);
const historyLoading = ref<boolean>(false);
const historyError = ref<string>("");
const historyBusyId = ref<string>("");
const historyMode = ref<HistoryMode>("active");
const readOnlyConversation = ref<boolean>(false);
const imageInput = ref<HTMLInputElement | null>(null);
const composerTextarea = ref<HTMLTextAreaElement | null>(null);
const pendingImages = ref<AgentAttachment[]>([]);
const listening = ref(false);
const speakingIndex = ref<number | null>(null);
const copiedIndex = ref<number | null>(null);
const memoryBusyId = ref<string>("");
const memoryConflict = ref<PendingMemoryConflict | null>(null);
const memoryConflictBusy = ref(false);
const memoryConflictError = ref("");
const interruptedRequest = ref<PendingAssistantRetry | null>(null);
const recognition = ref<SpeechRecognitionLike | null>(null);
const streamRenderStates = new WeakMap<object, StreamRenderState>();
const composerMemoryFeedback = reactive<{
  tone: "success" | "error";
  message: string;
}>({ tone: "success", message: "" });
let composerMemoryFeedbackTimer: number | null = null;
const pendingMemoryCandidates = computed<PendingMemoryCandidate[]>(() => {
  const candidates: PendingMemoryCandidate[] = [];
  messages.value.forEach((message) => {
    if (message.role !== "assistant") return;
    (message.memoryCandidates || []).forEach((candidate) => {
      if (candidate.status === "pending") candidates.push({ message, candidate });
    });
  });
  return candidates;
});

const markdownOptions = {
  async: false,
  breaks: true,
  gfm: true,
};

function normalizeAssistantMarkdown(value: string): string {
  return value
    .replace(/\r\n?/g, "\n")
    .replace(/\s+(?=\d+[.)、]\s+)/g, "\n");
}

function renderAssistantMarkdown(value: string): string {
  const source = normalizeAssistantMarkdown(value || "");
  if (!source.trim()) return "";
  const html = marked.parse(source, markdownOptions) as string;
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ["form", "iframe", "math", "object", "script", "style", "svg"],
    FORBID_ATTR: ["style", "srcset"],
    ALLOW_DATA_ATTR: false,
  });
}

function citationLabel(citation: AgentCitation | string): string {
  if (typeof citation === "string") return citation;
  return citation.title || citation.excerpt || citation.citationId || "检索来源";
}

function citationDetail(citation: AgentCitation | string): string {
  if (typeof citation === "string") return citation;
  return citation.excerpt || citation.citationId || "已授权的检索证据";
}

onMounted(async () => {
  await Promise.all([schoolStore.load(), loadModels()]);
  await loadHistory();
  if (threadId.value) await openConversation(threadId.value, false);
  if (readOnlyConversation.value) {
    historyMode.value = "archived";
    await loadHistory("archived");
  }
  sessionStorage.setItem(conversationStorageKey(), conversationId.value);
  if (threadId.value) sessionStorage.setItem(threadStorageKey(), threadId.value);
  if (!messages.value.length) {
    messages.value.push({ role: "assistant", answer: `你好，我可以结合${schoolStore.school?.schoolName || "本校"}的周边资源，协助你进行教学讲解和活动设计。`, citations: [] });
  }
});

onBeforeUnmount(() => {
  clearComposerMemoryFeedback();
});

async function loadHistory(mode: HistoryMode = historyMode.value): Promise<void> {
  historyLoading.value = true;
  historyError.value = "";
  try {
    const path = mode === "archived"
      ? "/api/ai/qa/history?status=archived"
      : "/api/ai/qa/history";
    history.value = await api.get<AssistantConversationSummary[]>(path);
  } catch {
    history.value = [];
    historyError.value = mode === "archived" ? "归档记录暂时无法加载" : "历史记录暂时无法加载";
  } finally {
    historyLoading.value = false;
  }
}

async function switchHistoryMode(mode: HistoryMode): Promise<void> {
  if (historyBusyId.value || historyMode.value === mode) return;
  historyMode.value = mode;
  history.value = [];
  await loadHistory(mode);
}

async function openConversation(selectedThreadId: string, showError = true): Promise<void> {
  if (!selectedThreadId || historyBusyId.value) return;
  historyBusyId.value = selectedThreadId;
  clearComposerMemoryFeedback();
  try {
    const detail = await api.get<AssistantConversationDetail>(`/api/ai/qa/history/${selectedThreadId}`);
    const storedMessages = detail.messages.filter((item) => item.role === "user" || item.role === "assistant");
    messages.value = storedMessages.map((item, index) => {
      if (item.role === "user") return { role: "user", text: item.content };
      const previousUser = [...storedMessages.slice(0, index)].reverse().find((candidate) => candidate.role === "user");
      return restoreHistoricalAssistantMessage(item, previousUser?.content || "");
    });
    threadId.value = detail.threadId;
    readOnlyConversation.value = detail.status === "archived";
    conversationId.value = makeConversationId();
    await scrollToBottom(true);
  } catch {
    if (showError) historyError.value = "无法打开这条历史对话";
  } finally {
    historyBusyId.value = "";
  }
}

function startNewConversation(): void {
  const wasArchivedMode = historyMode.value === "archived";
  activeAbortController.value?.abort();
  recognition.value?.stop();
  window.speechSynthesis?.cancel();
  pendingImages.value = [];
  speakingIndex.value = null;
  threadId.value = "";
  readOnlyConversation.value = false;
  historyMode.value = "active";
  conversationId.value = makeConversationId();
  messages.value = [];
  question.value = "";
  error.value = "";
  interruptedRequest.value = null;
  clearComposerMemoryFeedback();
  if (wasArchivedMode) {
    history.value = [];
    void loadHistory("active");
  }
}

async function archiveConversation(selectedThreadId: string): Promise<void> {
  if (!selectedThreadId || historyBusyId.value) return;
  historyBusyId.value = selectedThreadId;
  try {
    await api.delete(`/api/ai/qa/history/${selectedThreadId}`);
    history.value = history.value.filter((item) => item.threadId !== selectedThreadId);
    if (threadId.value === selectedThreadId) startNewConversation();
  } catch {
    historyError.value = "归档对话失败";
  } finally {
    historyBusyId.value = "";
  }
}

async function restoreConversation(selectedThreadId: string): Promise<void> {
  if (!selectedThreadId || historyBusyId.value) return;
  historyBusyId.value = selectedThreadId;
  try {
    await api.post(`/api/ai/qa/history/${selectedThreadId}/restore`);
    history.value = history.value.filter((item) => item.threadId !== selectedThreadId);
    if (threadId.value === selectedThreadId) readOnlyConversation.value = false;
    historyMode.value = "active";
    await loadHistory("active");
  } catch {
    historyError.value = "恢复对话失败";
  } finally {
    historyBusyId.value = "";
  }
}

function historyDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(date);
}

async function loadModels(): Promise<void> {
  try {
    models.value = await api.get<LlmModelOption[]>("/api/ai/models");
  } catch {
    models.value = [];
  }
}

watch(messages, (value) => sessionStorage.setItem(storageKey(), JSON.stringify(
  value.map(({ attachments: _attachments, ...message }) => message)
)), { deep: true });
watch(conversationId, (value) => sessionStorage.setItem(conversationStorageKey(), value));
watch(threadId, (value) => {
  if (value) sessionStorage.setItem(threadStorageKey(), value);
  else sessionStorage.removeItem(threadStorageKey());
});

function storageKey() {
  return `school-portal-assistant-session:${auth.user?.schoolId || "unknown"}`;
}

function conversationStorageKey() {
  return `school-portal-assistant-conversation:${auth.user?.schoolId || "unknown"}`;
}

function makeConversationId() {
  return globalThis.crypto?.randomUUID?.() || `conversation-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function makeClientTurnId() {
  return `turn-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`}`;
}

function loadConversationId() {
  return sessionStorage.getItem(conversationStorageKey()) || makeConversationId();
}

function loadMessages(): AssistantMessage[] {
  try {
    const stored = JSON.parse(sessionStorage.getItem(storageKey()) || "[]") as unknown;
    return Array.isArray(stored) ? stored as AssistantMessage[] : [];
  } catch {
    return [];
  }
}

function retrievalStatusLabel(status?: string | null, methods: string[] = []): string {
  const methodSet = new Set(methods);
  const hasHybridRrf = methodSet.has("hybrid-rrf")
    || (methodSet.has("dense") && methodSet.has("lexical") && methodSet.has("rrf"));
  if (hasHybridRrf) {
    return status === "degraded"
      ? "混合检索已完成，部分知识组件不可用"
      : "已完成 Dense 与全文混合检索及证据校验";
  }
  if (methodSet.has("lexical")) {
    return status === "degraded"
      ? "全文检索已完成，其他知识组件部分不可用"
      : "已完成 MySQL 全文检索与证据校验";
  }
  if (methodSet.has("dense")) {
    return status === "degraded"
      ? "向量检索已完成，其他知识组件部分不可用"
      : "已完成向量检索与证据校验";
  }
  if (methodSet.has("keyword-fallback")) {
    return status === "degraded"
      ? "向量检索未启用或暂不可用，已使用关键词检索"
      : "已使用关键词检索并完成证据校验";
  }
  if (methodSet.has("vector+hybrid-rerank")) {
    return status === "degraded"
      ? "向量检索已完成，其他知识组件部分不可用"
      : "已完成向量检索与证据校验";
  }
  if (methodSet.has("knowledge-graph")) {
    return status === "degraded" ? "图谱证据可用，其他知识组件部分不可用" : "已完成图谱证据校验";
  }
  const labels: Record<string, string> = {
    ok: "已结合知识检索证据",
    empty: "未检索到直接匹配的知识证据",
    degraded: "知识检索部分不可用，当前回答基于可用业务数据"
  };
  return status ? labels[status] || "知识检索状态未知" : "知识检索状态未知";
}

function retrievalStatusClass(status?: string | null): string {
  return `retrieval-${status || "unknown"}`;
}

function generationStatusLabel(status?: string | null): string {
  const labels: Record<string, string> = {
    completed: "已由答案生成服务整理",
    degraded: "答案生成服务不可用，当前为本地降级回答",
    skipped: "未调用答案生成服务"
  };
  return status ? labels[status] || "答案生成状态未知" : "答案生成状态未知";
}

function generationStatusClass(status?: string | null): string {
  return `generation-${status || "unknown"}`;
}

function threadStorageKey() {
  return `school-portal-assistant-thread:${auth.user?.schoolId || "unknown"}`;
}

function loadThreadId() {
  return sessionStorage.getItem(threadStorageKey()) || "";
}

async function explain() {
  if (readOnlyConversation.value) return;
  await requestAssistant("请介绍本校周边可用于思政教学的资源。");
}

async function ask(text: string = question.value): Promise<void> {
  if (readOnlyConversation.value) return;
  const clean = text.trim();
  if ((!clean && !pendingImages.value.length) || loading.value) return;
  const attachments = [...pendingImages.value];
  const prompt = clean || "请分析图片内容，并结合当前学校的教学场景给出说明。";
  question.value = "";
  pendingImages.value = [];
  await requestAssistant(prompt, attachments);
}

async function requestAssistant(userText: string, attachments: AgentAttachment[] = []): Promise<void> {
  if (readOnlyConversation.value) return;
  error.value = "";
  interruptedRequest.value = null;
  clearComposerMemoryFeedback();
  const clientTurnId = makeClientTurnId();
  messages.value.push({ role: "user", text: userText, attachments });
  const assistantMessage = reactive<AssistantMessage>({
    role: "assistant", answer: "", relatedResources: [], citations: [], followUpQuestions: [],
    toolEvents: [], traceEvents: [], traceExpanded: true, streamStatus: "正在启动 Agent…", isStreaming: true,
    clientTurnId,
  });
  messages.value.push(assistantMessage);
  const streamState = getStreamRenderState(assistantMessage);
  streamState.cancelled = false;
  chatAutoFollow.value = true;
  loading.value = true;
  const abortController = new AbortController();
  activeAbortController.value = abortController;
  await scrollToBottom();
  try {
    const requestBody: AgentQaRequestPayload = {
      question: userText,
      threadId: threadId.value || null,
      clientTurnId,
      scopeType: "SCHOOL",
      scopeId: schoolStore.school?.schoolId || auth.user?.schoolId || null
    };
    if (attachments.length) requestBody.attachments = attachments;
    if (selectedModelId.value) requestBody.modelId = selectedModelId.value;
    if (!threadId.value) requestBody.conversationId = conversationId.value;
    let finalReceived = false;
    let streamError: Error | null = null;

    if (typeof api.stream !== "function") {
      const result = await api.post<AgentQaResponse>("/api/ai/qa/ask", requestBody);
      finishStreamRendering(assistantMessage);
      applyAssistantResult(assistantMessage, result, userText);
      await loadHistory();
    } else {
      await api.stream("/api/ai/qa/stream", requestBody, {
        signal: abortController.signal,
        onEvent(eventName: AgentSseEventName, data: AgentSseEventData) {
          if (streamState.cancelled) return;
          if (data?.conversationId) conversationId.value = data.conversationId;
          if (data?.threadId) threadId.value = data.threadId;
          if (eventName === "run.started") {
            assistantMessage.runId = data.runId;
            assistantMessage.streamStatus = "Agent 已启动";
          } else if (eventName === "phase.started" || eventName === "phase.completed") {
            updateTrace(assistantMessage, `phase:${data.phase || "work"}`, {
              kind: "phase", title: data.label || phaseLabel(data.phase), detail: traceDetail(data),
              status: eventName === "phase.completed" ? "completed" : "running"
            });
            assistantMessage.streamStatus = data.label || phaseLabel(data.phase);
          } else if (eventName === "model.started") {
            updateTrace(assistantMessage, "model", {
              kind: "model", title: "连接生成模型",
              detail: [data.provider, data.model].filter(Boolean).join(" / "), status: "running"
            });
          } else if (eventName === "model.completed") {
            assistantMessage.effectiveModel = data.model
              ? `${data.provider || "LLM"} / ${data.model}` : "";
            updateTrace(assistantMessage, "model", { kind: "model", title: "生成模型已响应", status: "completed" });
          } else if (eventName === "model.failed") {
            updateTrace(assistantMessage, "model", {
              kind: "model", title: "当前模型不可用，准备降级", detail: data.errorType, status: "failed"
            });
          } else if (eventName === "tool.started") {
            const toolEvents = assistantMessage.toolEvents ||= [];
            toolEvents.push({ toolName: data.toolName, status: "started" });
            updateTrace(assistantMessage, `tool:${data.toolName}`, {
              kind: "tool", title: toolLabel(data.toolName), detail: traceDetail(data), status: "running"
            });
            assistantMessage.streamStatus = `正在调用：${toolLabel(data.toolName)}`;
          } else if (eventName === "tool.completed") {
            const toolEvents = assistantMessage.toolEvents ||= [];
            const previous = [...toolEvents].reverse().find(item => item.toolName === data.toolName && item.status === "started");
            if (previous) previous.status = data.status || "completed";
            else toolEvents.push({ toolName: data.toolName, status: data.status || "completed" });
            updateTrace(assistantMessage, `tool:${data.toolName}`, {
              kind: "tool", title: toolLabel(data.toolName), detail: data.outputSummary || traceDetail(data),
              durationMs: data.durationMs, status: data.status === "ok" ? "completed" : "failed"
            });
            assistantMessage.streamStatus = data.status === "ok" ? "工具结果已返回，正在整理回答" : "部分工具不可用，正在降级处理";
          } else if (eventName === "model.fallback") {
            if (data.reset) {
              resetStreamRendering(assistantMessage);
              assistantMessage.answer = "";
            }
            assistantMessage.streamStatus = `正在切换备用模型：${data.nextModel || "轻量模型"}`;
          } else if (eventName === "token") {
            queueStreamDelta(assistantMessage, data.delta || "");
            updateTrace(assistantMessage, "response", { kind: "response", title: "生成回答", status: "running" });
            assistantMessage.streamStatus = "正在生成回答";
          } else if (eventName === "final") {
            finalReceived = true;
            flushStreamDelta(assistantMessage);
            finishStreamRendering(assistantMessage);
            applyAssistantResult(assistantMessage, data.response || {}, userText);
            if (data.response?.conversationId) conversationId.value = data.response.conversationId;
            if (data.response?.threadId) threadId.value = data.response.threadId;
            assistantMessage.streamStatus = "回答完成";
            updateTrace(assistantMessage, "response", { kind: "response", title: "回答生成完成", status: "completed" });
          } else if (eventName === "error") {
            updateTrace(assistantMessage, "error", { kind: "error", title: "处理失败", detail: data.message, status: "failed" });
            streamError = new Error(data.message || "Agent 流式服务异常");
          }
        }
      });
      if (streamError && !finalReceived) throw streamError;
      if (!finalReceived) throw new Error("流式服务未返回最终结果");
      await loadHistory();
    }
  } catch (requestError) {
    const requestFailure = requestError instanceof Error ? requestError : new Error("请求失败");
    if (requestFailure.name === "AbortError") {
      finishStreamRendering(assistantMessage);
      assistantMessage.isStreaming = false;
      assistantMessage.streamStatus = "已停止生成";
      if (!assistantMessage.answer) messages.value.pop();
      return;
    }
    flushStreamDelta(assistantMessage);
    finishStreamRendering(assistantMessage);
    if (await recoverPersistedAssistantMessage(clientTurnId, assistantMessage, userText)) {
      error.value = "流式连接异常，已从历史记录恢复完整回答。";
      await loadHistory();
      return;
    }
    assistantMessage.isStreaming = false;
    assistantMessage.streamStatus = assistantMessage.answer
      ? "连接中断，已保留已显示内容"
      : "连接中断，未收到完整回答";
    updateTrace(assistantMessage, "stream-recovery", {
      kind: "error",
      title: "流式连接中断",
      detail: "未找到可恢复的完整回答",
      status: "failed",
    });
    interruptedRequest.value = { userText, attachments: [...attachments] };
    error.value = "流式连接已中断，未找到可恢复的完整回答。";
  } finally {
    if (assistantMessage.isStreaming) {
      flushStreamDelta(assistantMessage);
      finishStreamRendering(assistantMessage);
      assistantMessage.isStreaming = false;
    }
    loading.value = false;
    activeAbortController.value = null;
    await scrollToBottom();
  }
}

async function recoverPersistedAssistantMessage(
  clientTurnId: string,
  assistantMessage: AssistantMessage,
  userText: string,
): Promise<boolean> {
  for (const delay of [0, 120, 300]) {
    if (delay) await new Promise<void>((resolve) => window.setTimeout(resolve, delay));
    try {
      const recovery = await api.get<AssistantConversationTurnRecovery>(
        `/api/ai/qa/history/recovery/${encodeURIComponent(clientTurnId)}`,
      );
      if (!recovery.found || !recovery.message || recovery.message.role !== "assistant") continue;
      if (textValue(recovery.message.metadata?.clientTurnId) !== clientTurnId) continue;
      Object.assign(assistantMessage, restoreHistoricalAssistantMessage(recovery.message, userText), {
        clientTurnId,
        traceExpanded: true,
        streamStatus: "连接中断，已从历史记录恢复完整回答",
        isStreaming: false,
      });
      if (recovery.threadId) threadId.value = recovery.threadId;
      return true;
    } catch {
      // A transient recovery-read failure is retried before exposing manual retry.
    }
  }
  return false;
}

async function retryInterruptedRequest(): Promise<void> {
  const retry = interruptedRequest.value;
  if (!retry || loading.value || readOnlyConversation.value) return;
  interruptedRequest.value = null;
  await requestAssistant(retry.userText, [...retry.attachments]);
}

function applyAssistantResult(message: AssistantMessage, result: Partial<AgentQaResponse>, userText = ""): void {
  const relatedResources = normalizeFollowUpQuestions(result?.relatedResources, 8);
  const serverFollowUps = normalizeFollowUpQuestions(result?.followUpQuestions, 4, userText);
  Object.assign(message, {
    answer: result?.answer || "服务未返回回答。",
    relatedResources,
    citations: result?.citations || [],
    followUpQuestions: serverFollowUps.length ? serverFollowUps : buildFollowUpQuestions(userText, relatedResources),
    retrievalStatus: result?.retrievalStatus || null,
    retrievalMethods: normalizeStringList(result?.retrievalMethods, 8),
    generationStatus: result?.generationStatus || null,
    clarificationRequired: Boolean(result?.clarificationRequired),
    clarificationMessage: result?.clarificationMessage || "",
    clarificationOptions: result?.clarificationOptions || [],
    conversationId: result?.conversationId || message.conversationId,
    threadId: result?.threadId || message.threadId,
    runId: result?.runId || message.runId,
    fallbackLevel: result?.fallbackLevel ?? null,
    memoryCandidates: Array.isArray(result?.memoryCandidates) ? result.memoryCandidates : [],
    memoryApplied: result?.memoryApplied || null,
    contextCompacted: Boolean(result?.contextCompacted),
    effectiveModel: result?.model ? `${result.provider || "LLM"} / ${result.model}` : message.effectiveModel,
    streamStatus: result?.generationStatus === "degraded" ? "已使用降级回答" : "回答完成",
    isStreaming: false
  });
  if (result?.conversationId) conversationId.value = result.conversationId;
  if (result?.threadId) threadId.value = result.threadId;
}

function normalizeMemoryApplied(value: unknown): AgentMemoryApplied | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as { count?: unknown; memoryIds?: unknown };
  const count = typeof candidate.count === "number" ? candidate.count : 0;
  const memoryIds = Array.isArray(candidate.memoryIds)
    ? candidate.memoryIds.filter((item): item is string => typeof item === "string")
    : [];
  return count > 0 ? { count, memoryIds } : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function textValue(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function normalizeStringList(value: unknown, limit = 8): string[] {
  if (!Array.isArray(value)) return [];
  return [...new Set(value
    .filter((item): item is string => typeof item === "string")
    .map((item) => item.trim())
    .filter(Boolean))].slice(0, limit);
}

function normalizeCitations(value: unknown): AgentCitation[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const citation: AgentCitation = {
      citationId: textValue(item.citationId) || undefined,
      title: textValue(item.title),
      excerpt: textValue(item.excerpt),
      sourceType: textValue(item.sourceType),
      score: typeof item.score === "number" && Number.isFinite(item.score) ? item.score : null,
    };
    return citation.citationId || citation.title || citation.excerpt ? [citation] : [];
  }).slice(0, 5);
}

function normalizeToolExecutions(value: unknown): AgentToolExecution[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (typeof item === "string" && item.trim()) {
      return [{ name: item.trim(), status: "completed" }];
    }
    if (!isRecord(item)) return [];
    const name = textValue(item.name) || textValue(item.toolName);
    if (!name) return [];
    const durationMs = typeof item.durationMs === "number" && Number.isFinite(item.durationMs)
      ? Math.max(0, item.durationMs) : undefined;
    return [{
      name,
      status: textValue(item.status) || "completed",
      ...(durationMs === undefined ? {} : { durationMs }),
    }];
  }).slice(0, 12);
}

function normalizeResponseSnapshot(value: unknown): AssistantResponseSnapshot | null {
  if (!isRecord(value)) return null;
  const memoryApplied = normalizeMemoryApplied(value.memoryApplied);
  return {
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : undefined,
    status: textValue(value.status),
    generationStatus: textValue(value.generationStatus),
    retrievalStatus: textValue(value.retrievalStatus),
    retrievalMethods: normalizeStringList(value.retrievalMethods, 8),
    citations: normalizeCitations(value.citations),
    relatedResources: normalizeStringList(value.relatedResources, 8),
    followUpQuestions: normalizeStringList(value.followUpQuestions, 4),
    provider: textValue(value.provider),
    model: textValue(value.model),
    fallbackLevel: typeof value.fallbackLevel === "number" || typeof value.fallbackLevel === "string"
      ? value.fallbackLevel : null,
    toolExecutions: normalizeToolExecutions(value.toolExecutions),
    contextCompacted: value.contextCompacted === true,
    memoryApplied,
  };
}

function normalizeHistoricalAnswer(value: string): string {
  const trimmed = value.trim();
  if (!trimmed.startsWith("{")) return value;
  try {
    const parsed = JSON.parse(trimmed) as unknown;
    if (isRecord(parsed) && typeof parsed.answer === "string") return parsed.answer;
  } catch {
    // Early records may contain unescaped quotation marks inside answer.
  }
  return extractMalformedLegacyAnswer(value) || value;
}

function extractMalformedLegacyAnswer(value: string): string | null {
  const prefix = /^\s*\{\s*"answer"\s*:\s*"/.exec(value);
  if (!prefix) return null;
  const boundary = /",\s*"(?:intent|status|taskType|retrievalStatus|generationStatus|retrievalMethods|citationIds|citations|relatedResources|followUpQuestions|memoryCandidates|memoryApplied)"\s*:/g;
  const marker = boundary.exec(value);
  if (!marker || marker.index < prefix[0].length) return null;
  try {
    const reconstructedEnvelope = JSON.parse(`{"answer":""${value.slice(marker.index + 1)}`) as unknown;
    if (!isRecord(reconstructedEnvelope)) return null;
  } catch {
    return null;
  }
  return decodeLegacyJsonString(value.slice(prefix[0].length, marker.index));
}

function decodeLegacyJsonString(value: string): string {
  const escapes: Record<string, string> = {
    '"': '"',
    "\\": "\\",
    "/": "/",
    b: "\b",
    f: "\f",
    n: "\n",
    r: "\r",
    t: "\t",
  };
  return value.replace(/\\(u[0-9a-fA-F]{4}|["\\/bfnrt])/g, (matched, escaped: string) => {
    if (escaped.startsWith("u")) return String.fromCharCode(Number.parseInt(escaped.slice(1), 16));
    return escapes[escaped] ?? matched;
  });
}

function restoreHistoricalAssistantMessage(
  item: AssistantConversationStoredMessage,
  previousUserText = "",
): AssistantMessage {
  const metadata = item.metadata || {};
  const snapshot = normalizeResponseSnapshot(metadata.responseSnapshot);
  const storedFollowUps = snapshot
    ? normalizeFollowUpQuestions(snapshot.followUpQuestions, 4, previousUserText)
    : normalizeFollowUpQuestions(metadata.followUpQuestions, 4, previousUserText);
  const toolExecutions = snapshot?.toolExecutions || normalizeToolExecutions(metadata.toolExecutions);
  const traceEvents = buildHistoricalTrace(snapshot, toolExecutions);
  return {
    role: "assistant",
    answer: normalizeHistoricalAnswer(item.content),
    status: snapshot?.status || textValue(metadata.status),
    citations: snapshot?.citations || normalizeCitations(metadata.citations),
    relatedResources: snapshot?.relatedResources || normalizeStringList(metadata.relatedResources, 8),
    retrievalStatus: snapshot?.retrievalStatus || textValue(metadata.retrievalStatus),
    retrievalMethods: snapshot?.retrievalMethods || normalizeStringList(metadata.retrievalMethods, 8),
    generationStatus: snapshot?.generationStatus || textValue(metadata.generationStatus),
    fallbackLevel: snapshot?.fallbackLevel ?? null,
    memoryApplied: snapshot?.memoryApplied || normalizeMemoryApplied(metadata.memoryApplied),
    followUpQuestions: snapshot
      ? storedFollowUps
      : storedFollowUps.length
      ? storedFollowUps
      : previousUserText ? buildFollowUpQuestions(previousUserText) : [],
    toolExecutions,
    contextCompacted: snapshot?.contextCompacted || false,
    effectiveModel: snapshot?.model
      ? `${snapshot.provider || "LLM"} / ${snapshot.model}` : undefined,
    traceEvents,
    traceExpanded: false,
    streamStatus: traceEvents.length ? "历史执行摘要" : undefined,
    clientTurnId: textValue(metadata.clientTurnId) || undefined,
    isStreaming: false,
  };
}

function buildHistoricalTrace(
  snapshot: AssistantResponseSnapshot | null,
  toolExecutions: AgentToolExecution[],
): AssistantTraceEvent[] {
  if (!snapshot && !toolExecutions.length) return [];
  const startedAt = Date.now();
  const traces: AssistantTraceEvent[] = [];
  if (snapshot?.contextCompacted) {
    traces.push({
      id: "history-context",
      kind: "phase",
      title: "会话上下文已压缩",
      status: "completed",
      startedAt,
    });
  }
  toolExecutions.forEach((execution, index) => {
    traces.push({
      id: `history-tool-${index}`,
      kind: "tool",
      title: toolLabel(execution.name || execution.toolName),
      status: execution.status === "failed" ? "failed" : "completed",
      durationMs: execution.durationMs,
      startedAt,
    });
  });
  if (snapshot?.model) {
    traces.push({
      id: "history-model",
      kind: "model",
      title: "生成模型已响应",
      detail: `${snapshot.provider || "LLM"} / ${snapshot.model}`,
      status: "completed",
      startedAt,
    });
  }
  if (snapshot) {
    traces.push({
      id: "history-response",
      kind: "response",
      title: "回答生成完成",
      status: snapshot.status === "incomplete" ? "failed" : "completed",
      startedAt,
    });
  }
  return traces;
}

async function confirmMemoryCandidate(
  message: AssistantMessage,
  candidate: AgentMemoryItem,
): Promise<void> {
  if (memoryBusyId.value || memoryConflict.value) return;
  memoryBusyId.value = candidate.id;
  clearComposerMemoryFeedback();
  try {
    const preview = await memoryApi.confirmationPreview(candidate.id);
    if (preview.duplicate) {
      const result = await memoryApi.confirm(candidate.id);
      await completeMemoryCandidateConfirmation(message, candidate, result);
      return;
    }
    if (preview.conflicts.length) {
      memoryConflictError.value = "";
      memoryConflict.value = { message, candidate, preview };
      return;
    }
    const result = await memoryApi.confirm(candidate.id);
    await completeMemoryCandidateConfirmation(message, candidate, result);
  } catch (candidateError) {
    if (await reopenMemoryConflictIfNeeded(message, candidate)) return;
    showComposerMemoryFeedback(
      "error",
      candidateError instanceof Error ? candidateError.message : "确认记忆失败，请稍后重试。",
    );
  } finally {
    memoryBusyId.value = "";
  }
}

async function ignoreMemoryCandidate(
  message: AssistantMessage,
  candidate: AgentMemoryItem,
): Promise<void> {
  if (memoryBusyId.value || memoryConflict.value) return;
  memoryBusyId.value = candidate.id;
  clearComposerMemoryFeedback();
  try {
    await memoryApi.recycle(candidate.id);
    removeMemoryCandidate(message, candidate.id);
    showComposerMemoryFeedback("success", "已忽略这条候选记忆，可在个人中心回收站恢复。");
    await focusComposerAfterLastMemoryCandidate();
  } catch (candidateError) {
    showComposerMemoryFeedback(
      "error",
      candidateError instanceof Error ? candidateError.message : "忽略记忆失败，请稍后重试。",
    );
  } finally {
    memoryBusyId.value = "";
  }
}

async function completeMemoryCandidateConfirmation(
  message: AssistantMessage,
  candidate: AgentMemoryItem,
  result: AgentMemoryItem,
): Promise<void> {
  removeMemoryCandidate(message, candidate.id);
  showComposerMemoryFeedback(
    "success",
    result.status === "deleted"
      ? "该记忆已存在，已保留已有值并将候选移入回收站。"
      : "已确认并保存这条记忆。",
  );
  await focusComposerAfterLastMemoryCandidate();
}

async function reopenMemoryConflictIfNeeded(
  message: AssistantMessage,
  candidate: AgentMemoryItem,
): Promise<boolean> {
  try {
    const preview = await memoryApi.confirmationPreview(candidate.id);
    if (!preview.duplicate && preview.conflicts.length) {
      memoryConflictError.value = "";
      memoryConflict.value = { message, candidate, preview };
      return true;
    }
  } catch {
    // 保留原始请求错误，避免二次预检错误覆盖可读的失败原因。
  }
  return false;
}

function closeMemoryConflict(): void {
  if (memoryConflictBusy.value) return;
  memoryConflictError.value = "";
  memoryConflict.value = null;
}

async function keepExistingMemoryConflict(): Promise<void> {
  const conflict = memoryConflict.value;
  if (!conflict || memoryConflictBusy.value) return;
  memoryConflictBusy.value = true;
  memoryConflictError.value = "";
  try {
    await memoryApi.recycle(conflict.candidate.id);
    removeMemoryCandidate(conflict.message, conflict.candidate.id);
    memoryConflict.value = null;
    showComposerMemoryFeedback("success", "已保留了原有记忆，这条候选已移入回收站。");
    await focusComposerAfterLastMemoryCandidate();
  } catch (candidateError) {
    memoryConflictError.value = candidateError instanceof Error ? candidateError.message : "保留旧值失败，请稍后重试。";
  } finally {
    memoryConflictBusy.value = false;
  }
}

async function replaceMemoryConflict(): Promise<void> {
  const conflict = memoryConflict.value;
  if (!conflict || memoryConflictBusy.value) return;
  memoryConflictBusy.value = true;
  memoryConflictError.value = "";
  try {
    const result = await memoryApi.confirm(conflict.candidate.id, true);
    removeMemoryCandidate(conflict.message, conflict.candidate.id);
    memoryConflict.value = null;
    showComposerMemoryFeedback(
      "success",
      result.status === "deleted"
        ? "该记忆已存在，已保留已有值并将候选移入回收站。"
        : "已用新值替换旧记忆，旧值已移入回收站。",
    );
    await focusComposerAfterLastMemoryCandidate();
  } catch (candidateError) {
    memoryConflictError.value = candidateError instanceof Error ? candidateError.message : "替换旧值失败，请稍后重试。";
  } finally {
    memoryConflictBusy.value = false;
  }
}

function removeMemoryCandidate(message: AssistantMessage, id: string): void {
  message.memoryCandidates = (message.memoryCandidates || []).filter((item) => item.id !== id);
}

function clearComposerMemoryFeedback(): void {
  if (composerMemoryFeedbackTimer !== null) {
    window.clearTimeout(composerMemoryFeedbackTimer);
    composerMemoryFeedbackTimer = null;
  }
  composerMemoryFeedback.message = "";
}

function showComposerMemoryFeedback(tone: "success" | "error", message: string): void {
  clearComposerMemoryFeedback();
  composerMemoryFeedback.tone = tone;
  composerMemoryFeedback.message = message;
  if (tone === "success") {
    composerMemoryFeedbackTimer = window.setTimeout(() => {
      composerMemoryFeedbackTimer = null;
      if (composerMemoryFeedback.tone === "success" && composerMemoryFeedback.message === message) {
        composerMemoryFeedback.message = "";
      }
    }, 5_000);
  }
}

async function focusComposerAfterLastMemoryCandidate(): Promise<void> {
  if (pendingMemoryCandidates.value.length) return;
  await nextTick();
  composerTextarea.value?.focus();
}

function getStreamRenderState(message: AssistantMessage): StreamRenderState {
  const key = message as object;
  const existing = streamRenderStates.get(key);
  if (existing) return existing;
  const state: StreamRenderState = {
    pending: "",
    frameId: null,
    frameType: null,
    cancelled: false
  };
  streamRenderStates.set(key, state);
  return state;
}

function clearScheduledStreamFrame(state: StreamRenderState): void {
  if (state.frameId === null) return;
  if (state.frameType === "raf" && typeof window.cancelAnimationFrame === "function") {
    window.cancelAnimationFrame(state.frameId);
  } else {
    window.clearTimeout(state.frameId);
  }
  state.frameId = null;
  state.frameType = null;
}

function queueStreamDelta(message: AssistantMessage, delta: string): void {
  if (!delta) return;
  const state = getStreamRenderState(message);
  if (state.cancelled) return;
  state.pending += delta;
  if (state.frameId !== null) return;
  const flush = () => {
    state.frameId = null;
    state.frameType = null;
    if (state.cancelled) {
      state.pending = "";
      return;
    }
    const pending = state.pending;
    state.pending = "";
    if (pending) {
      message.answer = `${message.answer || ""}${pending}`;
      void scrollToBottom();
    }
  };
  if (typeof window.requestAnimationFrame === "function") {
    state.frameType = "raf";
    state.frameId = window.requestAnimationFrame(flush);
  } else {
    state.frameType = "timeout";
    state.frameId = window.setTimeout(flush, 16);
  }
}

function flushStreamDelta(message: AssistantMessage): void {
  const state = getStreamRenderState(message);
  clearScheduledStreamFrame(state);
  if (state.cancelled) {
    state.pending = "";
    return;
  }
  if (state.pending) {
    message.answer = `${message.answer || ""}${state.pending}`;
    state.pending = "";
  }
}

function resetStreamRendering(message: AssistantMessage): void {
  const state = getStreamRenderState(message);
  clearScheduledStreamFrame(state);
  state.pending = "";
  state.cancelled = false;
}

function finishStreamRendering(message: AssistantMessage): void {
  const state = getStreamRenderState(message);
  clearScheduledStreamFrame(state);
  state.pending = "";
  state.cancelled = true;
}

const invalidFollowUpMarkers = [
  "您需要", "你需要", "您是否需要", "你是否需要", "您想", "你想",
  "请问您", "请问你", "你可以告诉我", "您可以告诉我", "需要查询哪些"
];

function isActionableFollowUp(value: string, currentQuestion = ""): boolean {
  const normalized = value.trim();
  if (!normalized || normalized.length > 120 || normalized === currentQuestion.trim()) return false;
  return !invalidFollowUpMarkers.some((marker) => normalized.includes(marker));
}

function normalizeFollowUpQuestions(value: unknown, limit = 4, currentQuestion = ""): string[] {
  if (!Array.isArray(value)) return [];
  return [...new Set(value
    .filter((item): item is string => typeof item === "string")
    .map((item) => item.trim())
    .filter((item) => isActionableFollowUp(item, currentQuestion)))].slice(0, limit);
}

function buildFollowUpQuestions(userText = "", relatedResources: string[] = []): string[] {
  const resourceName = relatedResources[0] || schoolStore.resources[0]?.resource?.resourceName;
  return normalizeFollowUpQuestions([
    resourceName ? `请说明“${resourceName}”适合哪些年级。` : "请介绍适合当前年级的本土思政教育资源。",
    resourceName ? `请设计一节利用“${resourceName}”开展的实践课。` : "请结合学校周边资源设计一节思政实践课。",
    "请列出一次校外实践活动的安全注意事项。",
    userText ? `请说明如何将“${userText}”转化为课堂活动。` : "请说明如何将当前问题转化为课堂活动。"
  ], 4, userText);
}

function phaseLabel(phase?: string): string {
  return {
    retrieval: "检索可信知识与业务数据",
    context: "准备会话上下文",
    reasoning: "分析问题并规划步骤",
    response: "生成回答"
  }[phase || ""] || "处理中";
}

function traceDetail(data: AgentSseEventData): string {
  if (data.arguments && Object.keys(data.arguments).length) {
    return Object.entries(data.arguments).map(([key, value]) => `${key}: ${String(value)}`).join(" · ");
  }
  if (Array.isArray(data.recommendedTools) && data.recommendedTools.length) {
    return `计划调用 ${data.recommendedTools.map(item => toolLabel(String(item))).join("、")}`;
  }
  return "";
}

function updateTrace(
  message: AssistantMessage,
  id: string,
  update: Omit<AssistantTraceEvent, "id" | "startedAt"> & { startedAt?: number }
): void {
  message.traceEvents ||= [];
  const existing = message.traceEvents.find(item => item.id === id);
  if (existing) {
    Object.assign(existing, update);
    return;
  }
  message.traceEvents.push({ id, startedAt: Date.now(), ...update });
}

function traceIcon(kind: AssistantTraceEvent["kind"]) {
  if (kind === "tool") return Wrench;
  if (kind === "response") return Sparkles;
  return BrainCircuit;
}

function toolLabel(toolName?: string): string {
  const labels: Record<string, string> = {
    "get_scope_context": "查看学校上下文",
    "get_school_context": "查看学校上下文",
    "search_approved_resources": "检索已审核资源",
    "retrieve_knowledge": "检索知识库",
    "query_graph_relations": "查询知识关系",
    "/internal/agent/tools/school-context": "学校资源",
    "/internal/agent/tools/resource-detail": "资源详情",
    "/internal/agent/tools/knowledge-retrieve": "知识检索",
    "/internal/agent/tools/relation-query": "图谱关系"
  };
  return toolName ? labels[toolName] || toolName : "受控工具";
}

function chooseImages(): void {
  imageInput.value?.click();
}

async function addImages(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = "";
  for (const file of files) {
    if (pendingImages.value.length >= 3) {
      error.value = "每次最多上传 3 张图片";
      break;
    }
    if (!(["image/jpeg", "image/png", "image/webp", "image/gif"] as string[]).includes(file.type)) {
      error.value = `不支持 ${file.name} 的图片格式`;
      continue;
    }
    if (file.size > 5 * 1024 * 1024) {
      error.value = `${file.name} 超过 5MB`;
      continue;
    }
    pendingImages.value.push({
      type: "image",
      name: file.name.slice(0, 180),
      mediaType: file.type as AgentAttachment["mediaType"],
      dataUrl: await readAsDataUrl(file)
    });
  }
}

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(reader.error || new Error("图片读取失败"));
    reader.readAsDataURL(file);
  });
}

function removeImage(index: number): void {
  pendingImages.value.splice(index, 1);
}

function toggleListening(): void {
  if (listening.value) {
    recognition.value?.stop();
    return;
  }
  const speechWindow = window as unknown as {
    SpeechRecognition?: new () => SpeechRecognitionLike;
    webkitSpeechRecognition?: new () => SpeechRecognitionLike;
  };
  const Recognition = speechWindow.SpeechRecognition || speechWindow.webkitSpeechRecognition;
  if (!Recognition) {
    error.value = "当前浏览器不支持语音输入，请使用最新版 Chrome 或 Edge";
    return;
  }
  const instance = new Recognition();
  const original = question.value.trim();
  instance.lang = "zh-CN";
  instance.interimResults = true;
  instance.continuous = false;
  instance.onresult = (event) => {
    let transcript = "";
    for (let index = 0; index < event.results.length; index += 1) {
      transcript += event.results[index][0]?.transcript || "";
    }
    question.value = [original, transcript.trim()].filter(Boolean).join(" ");
  };
  instance.onerror = () => {
    error.value = "语音识别未成功，请检查麦克风权限后重试";
    listening.value = false;
  };
  instance.onend = () => {
    listening.value = false;
    recognition.value = null;
  };
  recognition.value = instance;
  listening.value = true;
  instance.start();
}

async function copyAnswer(answer: string | undefined, index: number): Promise<void> {
  if (!answer || !navigator.clipboard?.writeText) {
    error.value = "当前浏览器不支持复制，请手动选择回答内容";
    return;
  }
  try {
    await navigator.clipboard.writeText(answer);
    copiedIndex.value = index;
    window.setTimeout(() => {
      if (copiedIndex.value === index) copiedIndex.value = null;
    }, 1800);
  } catch {
    error.value = "复制失败，请手动选择回答内容";
  }
}

function toggleSpeech(message: AssistantMessage, index: number): void {
  if (!("speechSynthesis" in window) || !message.answer) return;
  if (speakingIndex.value === index) {
    window.speechSynthesis.cancel();
    speakingIndex.value = null;
    return;
  }
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(message.answer);
  utterance.lang = "zh-CN";
  utterance.rate = 1;
  utterance.onend = () => { speakingIndex.value = null; };
  utterance.onerror = () => { speakingIndex.value = null; };
  speakingIndex.value = index;
  window.speechSynthesis.speak(utterance);
}

function stopGeneration(): void {
  const streamMessage = [...messages.value].reverse().find(item => item.role === "assistant" && item.isStreaming);
  if (streamMessage) {
    finishStreamRendering(streamMessage);
    streamMessage.isStreaming = false;
    streamMessage.streamStatus = "已停止生成";
  }
  activeAbortController.value?.abort();
}

function handleChatScroll(): void {
  const element = chatScroll.value;
  if (!element) return;
  chatAutoFollow.value = element.scrollHeight - element.scrollTop - element.clientHeight < 96;
}

async function scrollToBottom(force = false): Promise<void> {
  await nextTick();
  if (chatScroll.value && (force || chatAutoFollow.value)) {
    chatScroll.value.scrollTop = chatScroll.value.scrollHeight;
  }
}

function clearChat(): void {
  const wasArchivedMode = historyMode.value === "archived";
  recognition.value?.stop();
  window.speechSynthesis?.cancel();
  pendingImages.value = [];
  speakingIndex.value = null;
  copiedIndex.value = null;
  interruptedRequest.value = null;
  error.value = "";
  clearComposerMemoryFeedback();
  messages.value = [];
  threadId.value = "";
  readOnlyConversation.value = false;
  historyMode.value = "active";
  sessionStorage.removeItem(storageKey());
  sessionStorage.removeItem(conversationStorageKey());
  conversationId.value = makeConversationId();
  sessionStorage.removeItem(threadStorageKey());
  if (wasArchivedMode) {
    history.value = [];
    void loadHistory("active");
  }
}
</script>

<template>
  <AppShell title="智能问答" subtitle="基于本校资源进行讲解、追问与教学活动构思">
    <section class="assistant-layout page-panel">
      <aside class="assistant-side">
        <div><span class="assistant-mark"><Bot :size="22" /></span><h2>学校资源助手</h2><p>回答会结合本校周边资源与已有教学方案。</p></div>
        <div class="history-heading">
          <h3><History :size="16" />{{ historyMode === "archived" ? "已归档对话" : "历史对话" }}</h3>
          <div class="history-heading-actions">
            <button class="history-mode-toggle" type="button" :title="historyMode === 'active' ? '查看已归档对话' : '返回历史对话'" @click="switchHistoryMode(historyMode === 'active' ? 'archived' : 'active')">
              <Archive :size="14" />{{ historyMode === "active" ? "已归档" : "返回历史" }}
            </button>
            <button class="icon-action" type="button" title="新对话" aria-label="新对话" @click="startNewConversation"><Plus :size="17" /></button>
          </div>
        </div>
        <div class="history-list" aria-label="历史对话列表">
          <span v-if="historyLoading" class="history-state">正在加载...</span>
          <span v-else-if="historyError" class="history-state">{{ historyError }}</span>
          <span v-else-if="!history.length" class="history-state">{{ historyMode === "archived" ? "暂无归档对话" : "暂无历史对话" }}</span>
          <div v-for="item in history" v-else :key="item.threadId" class="history-row" :class="{ active: item.threadId === threadId, archived: historyMode === 'archived' }">
            <button class="history-open" type="button" :disabled="Boolean(historyBusyId)" @click="openConversation(item.threadId)">
              <span><strong>{{ item.title }}</strong><time>{{ historyDate(item.updatedAt) }}</time></span>
              <small>{{ item.preview }}</small>
            </button>
            <button v-if="historyMode === 'active'" class="history-archive" type="button" title="归档对话" :aria-label="`归档对话：${item.title}`" :disabled="Boolean(historyBusyId)" @click="archiveConversation(item.threadId)"><Archive :size="14" /></button>
            <button v-else class="history-restore" type="button" title="恢复对话" :aria-label="`恢复对话：${item.title}`" :disabled="Boolean(historyBusyId)" @click="restoreConversation(item.threadId)"><ArchiveRestore :size="14" /></button>
          </div>
        </div>
        <div class="assistant-side-actions">
          <button class="primary-button full-button" type="button" :disabled="loading || readOnlyConversation" @click="explain"><Sparkles :size="17" />生成学校讲解</button>
          <button class="text-button clear-button" type="button" @click="clearChat"><Trash2 :size="16" />清空会话</button>
        </div>
      </aside>

      <div class="chat-area">
        <div class="chat-main">
          <div v-if="readOnlyConversation" class="archived-banner" role="status">
            <span><Archive :size="15" />这是归档对话，仅供查看。</span>
            <button class="text-button" type="button" :disabled="Boolean(historyBusyId)" @click="restoreConversation(threadId)">恢复对话</button>
          </div>
          <div ref="chatScroll" class="chat-scroll" aria-live="polite" @scroll="handleChatScroll">
          <InlineNotice v-if="error" tone="info">
            <span>{{ error }}</span>
            <button
              v-if="interruptedRequest"
              class="stream-retry-button"
              type="button"
              :disabled="loading || readOnlyConversation"
              @click="retryInterruptedRequest"
            >重新生成</button>
          </InlineNotice>
          <article v-for="(message,index) in messages" :key="index" class="chat-message" :class="message.role">
            <span class="chat-avatar"><UserRound v-if="message.role === 'user'" :size="17" /><Bot v-else :size="17" /></span>
            <div>
              <div v-if="message.role === 'assistant'" class="assistant-answer markdown-body" v-html="renderAssistantMarkdown(message.answer || '')"></div>
              <p v-else>{{ message.text }}</p>
              <span v-if="message.role === 'assistant' && message.isStreaming" class="stream-cursor" aria-hidden="true"></span>
              <div v-if="message.attachments?.length" class="message-images">
                <img v-for="attachment in message.attachments" :key="attachment.name" :src="attachment.dataUrl" :alt="attachment.name" />
              </div>
              <section v-if="message.role === 'assistant' && message.traceEvents?.length" class="agent-trace" aria-label="Agent 执行过程">
                <button class="agent-trace-toggle" type="button" :aria-expanded="message.traceExpanded !== false" @click="message.traceExpanded = message.traceExpanded === false">
                  <span>
                    <LoaderCircle v-if="message.traceEvents.some(item => item.status === 'running')" class="trace-spinner" :size="15" />
                    <Check v-else :size="15" />
                    {{ message.streamStatus || "执行过程" }}
                  </span>
                  <ChevronDown :size="15" :class="{ rotated: message.traceExpanded !== false }" />
                </button>
                <div v-if="message.traceExpanded !== false" class="agent-trace-list">
                  <div v-for="trace in message.traceEvents" :key="trace.id" class="agent-trace-row" :class="`trace-${trace.status}`">
                    <span class="trace-icon"><LoaderCircle v-if="trace.status === 'running'" class="trace-spinner" :size="14" /><Check v-else-if="trace.status === 'completed'" :size="14" /><component :is="traceIcon(trace.kind)" v-else :size="14" /></span>
                    <div><strong>{{ trace.title }}</strong><small v-if="trace.detail">{{ trace.detail }}</small></div>
                    <span v-if="trace.durationMs !== undefined" class="trace-duration"><Clock3 :size="12" />{{ trace.durationMs }} ms</span>
                  </div>
                </div>
              </section>
              <div v-else-if="message.streamStatus" class="agent-stream-status">{{ message.streamStatus }}</div>
              <div v-if="message.isStreaming && message.effectiveModel" class="agent-stream-status">实际模型：{{ message.effectiveModel }}</div>
              <p v-if="message.memoryApplied?.count" class="memory-applied">
                <BrainCircuit :size="14" />本次参考 {{ message.memoryApplied.count }} 条记忆
              </p>
              <p v-if="message.retrievalStatus" class="retrieval-status" :class="retrievalStatusClass(message.retrievalStatus)">{{ retrievalStatusLabel(message.retrievalStatus, message.retrievalMethods) }}</p>
              <p v-if="message.generationStatus" class="generation-status" :class="generationStatusClass(message.generationStatus)">{{ generationStatusLabel(message.generationStatus) }}</p>
              <div v-if="message.clarificationRequired" class="clarification"><strong>需要补充：</strong>{{ message.clarificationMessage || "请补充具体学校名称。" }}<span v-if="message.clarificationOptions?.length">可选：{{ message.clarificationOptions.join("、") }}</span></div>
              <p v-if="message.relatedResources?.length" class="related"><strong>关联资源：</strong>{{ message.relatedResources.join("、") }}</p>
              <div v-if="message.citations?.length" class="chat-citations" aria-label="引用来源">
                <span class="chat-citations-label">来源</span>
                <div class="chat-citation-list">
                  <span v-for="(citation,citationIndex) in message.citations" :key="citationIndex" class="citation-chip" :title="citationDetail(citation)">{{ citationLabel(citation) }}</span>
                </div>
              </div>
              <div v-if="message.role === 'assistant' && message.followUpQuestions?.length" class="follow-ups">
                <span class="follow-ups-label">你还可以问</span>
                <div class="follow-up-actions"><button v-for="item in message.followUpQuestions" :key="item" type="button" :disabled="readOnlyConversation" @click="ask(item)">{{ item }}</button></div>
              </div>
              <div v-if="message.role === 'assistant' && message.answer" class="message-actions">
                <button class="message-action" :class="{ copied: copiedIndex === index }" type="button" :title="copiedIndex === index ? '已复制' : '复制回答'" :aria-label="copiedIndex === index ? '已复制' : '复制回答'" @click="copyAnswer(message.answer, index)">
                  <Check v-if="copiedIndex === index" :size="15" /><Copy v-else :size="15" />
                </button>
                <button class="message-action message-audio" type="button" :title="speakingIndex === index ? '停止朗读' : '朗读回答'" :aria-label="speakingIndex === index ? '停止朗读' : '朗读回答'" @click="toggleSpeech(message, index)">
                  <VolumeX v-if="speakingIndex === index" :size="15" /><Volume2 v-else :size="15" />
                </button>
              </div>
            </div>
          </article>
          <div v-if="loading" class="typing"><span></span><span></span><span></span></div>
          <div v-if="!messages.length && !loading" class="empty-state"><MessageCircleQuestion :size="42" /><span>选择建议问题或输入你想了解的内容</span></div>
          </div>
        </div>
        <section
          v-if="pendingMemoryCandidates.length || composerMemoryFeedback.message"
          class="composer-memory-suggestions"
          :class="{ 'composer-memory-suggestions-error': composerMemoryFeedback.tone === 'error' }"
          aria-label="待确认的记忆建议"
        >
          <div v-if="pendingMemoryCandidates.length" class="composer-memory-heading">
            <div>
              <strong><BrainCircuit :size="17" />待确认的记忆建议（{{ pendingMemoryCandidates.length }}）</strong>
              <p>确认后才会作为跨会话记忆使用。</p>
            </div>
          </div>
          <div v-if="pendingMemoryCandidates.length" class="composer-memory-candidates">
            <article
              v-for="item in pendingMemoryCandidates"
              :key="item.candidate.id"
              class="composer-memory-candidate-card"
              :data-memory-id="item.candidate.id"
            >
              <div class="composer-memory-candidate-content">
                <span class="composer-memory-type">{{ item.candidate.memoryType === "PROFILE" ? "用户画像" : "阶段任务" }}</span>
                <p>{{ item.candidate.content }}</p>
              </div>
              <div class="composer-memory-candidate-actions">
                <button data-action="confirm" type="button" :disabled="Boolean(memoryBusyId) || Boolean(memoryConflict) || readOnlyConversation" @click="confirmMemoryCandidate(item.message, item.candidate)">
                  <Check :size="14" />确认
                </button>
                <button data-action="ignore" type="button" :disabled="Boolean(memoryBusyId) || Boolean(memoryConflict) || readOnlyConversation" @click="ignoreMemoryCandidate(item.message, item.candidate)">
                  <X :size="14" />忽略
                </button>
              </div>
            </article>
          </div>
          <p
            v-if="composerMemoryFeedback.message"
            class="composer-memory-feedback"
            :class="`composer-memory-feedback-${composerMemoryFeedback.tone}`"
            :role="composerMemoryFeedback.tone === 'error' ? 'alert' : 'status'"
          >{{ composerMemoryFeedback.message }}</p>
        </section>
        <form class="chat-composer" :class="{ 'chat-composer-readonly': readOnlyConversation }" @submit.prevent="ask()">
          <div v-if="pendingImages.length" class="pending-images">
            <div v-for="(attachment,index) in pendingImages" :key="attachment.name + index">
              <img :src="attachment.dataUrl" :alt="attachment.name" />
              <button type="button" title="移除图片" aria-label="移除图片" :disabled="readOnlyConversation" @click="removeImage(index)"><X :size="14" /></button>
            </div>
          </div>
          <textarea ref="composerTextarea" v-model="question" rows="2" :disabled="readOnlyConversation || loading" :placeholder="readOnlyConversation ? '归档对话仅供查看，请先恢复对话' : '输入关于学校资源或教学活动的问题'" @keydown.ctrl.enter.prevent="ask()"></textarea>
          <div class="composer-toolbar">
            <div class="composer-tools">
              <input ref="imageInput" class="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp,image/gif" multiple @change="addImages" />
              <button class="composer-icon" type="button" title="添加图片" aria-label="添加图片" :disabled="readOnlyConversation || loading || pendingImages.length >= 3" @click="chooseImages"><ImagePlus :size="19" /></button>
              <span class="composer-divider" aria-hidden="true"></span>
              <label class="composer-model">
                <span class="visually-hidden">回答模型</span>
                <select v-model="selectedModelId" class="composer-model-select" aria-label="回答模型" :disabled="readOnlyConversation || loading">
                  <option value="">系统默认</option>
                  <option v-for="item in models" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.provider }}</option>
                </select>
              </label>
            </div>
            <div class="composer-actions">
            <button class="composer-icon" :class="{ active: listening }" type="button" :title="listening ? '停止语音输入' : '语音输入'" :aria-label="listening ? '停止语音输入' : '语音输入'" :disabled="readOnlyConversation || loading" @click="toggleListening"><Mic :size="19" /></button>
            <button v-if="loading" class="text-button stop-button" type="button" @click="stopGeneration">停止</button>
            <button v-else class="primary-button send-button" type="submit" :disabled="readOnlyConversation || (!question.trim() && !pendingImages.length)" aria-label="发送问题"><Send :size="19" /></button>
            </div>
          </div>
        </form>
      </div>
    </section>
  </AppShell>
  <MemoryConflictDialog
    :open="Boolean(memoryConflict)"
    :candidate="memoryConflict?.candidate || null"
    :conflicts="memoryConflict?.preview.conflicts || []"
    :busy="memoryConflictBusy"
    :error-message="memoryConflictError"
    @cancel="closeMemoryConflict"
    @keep="keepExistingMemoryConflict"
    @replace="replaceMemoryConflict"
  />
</template>

<style scoped>
.assistant-layout { display: grid; grid-template-columns: 280px minmax(0,1fr); grid-template-rows: minmax(0,1fr); height: calc(100vh - 122px); min-height: 600px; overflow: hidden; }
.assistant-side { display: flex; min-height: 0; flex-direction: column; gap: 20px; overflow: hidden; padding: 22px; border-right: 1px solid var(--line); background: #f8f9f7; }
.assistant-mark { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 8px; background: var(--green); color: #fff; }
.assistant-side h2 { margin: 15px 0 6px; font-size: 18px; }
.assistant-side p { color: var(--muted); font-size: 13px; line-height: 1.65; }
.history-heading { display: flex; align-items: center; justify-content: space-between; }
.history-heading h3 { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 13px; }
.history-heading-actions { display: flex; align-items: center; gap: 5px; }
.history-mode-toggle { display: flex; align-items: center; gap: 4px; min-height: 30px; padding: 0 7px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: var(--muted); cursor: pointer; font-size: 11px; }
.history-mode-toggle:hover { color: var(--red); background: #f4efed; }
.icon-action, .history-archive { display: grid; place-items: center; border: 0; background: transparent; color: var(--muted); cursor: pointer; }
.icon-action { width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 6px; background: #fff; }
.history-list { display: grid; flex: 1 1 auto; align-content: start; gap: 3px; height: auto; min-height: 240px; max-height: none; overflow-y: auto; scrollbar-color: #98afa0 transparent; scrollbar-width: thin; }
.history-list::-webkit-scrollbar { width: 6px; }
.history-list::-webkit-scrollbar-track { background: transparent; }
.history-list::-webkit-scrollbar-thumb { border: 1px solid transparent; border-radius: 999px; background: #98afa0; background-clip: padding-box; }
.history-list::-webkit-scrollbar-button { display: none; height: 0; }
.history-state { padding: 12px 4px; color: var(--muted); font-size: 12px; }
.history-row { display: grid; grid-template-columns: minmax(0,1fr) 28px; align-items: center; border-left: 3px solid transparent; }
.history-row.active { border-left-color: var(--red); background: #fff; }
.history-open { min-width: 0; padding: 7px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.history-open > span { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.history-open strong, .history-open small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-open strong { color: var(--text); font-size: 12px; }
.history-open time { flex: none; color: var(--muted); font-size: 10px; }
.history-open small { margin-top: 4px; color: var(--muted); font-size: 11px; }
.history-archive { width: 28px; height: 28px; border-radius: 5px; }
.history-archive:hover, .icon-action:hover { color: var(--red); background: #f4efed; }
.history-restore { display: grid; width: 28px; height: 28px; place-items: center; border: 0; border-radius: 5px; background: transparent; color: var(--green); cursor: pointer; }
.history-restore:hover { background: var(--green-soft); }
.assistant-side-actions { display: flex; flex: 0 0 auto; flex-direction: column; gap: 8px; margin-top: auto; }
.clear-button { justify-content: flex-start; margin-top: 0; color: var(--muted); }
.chat-area { min-width: 0; min-height: 0; display: grid; grid-template-rows: minmax(0,1fr) auto auto; overflow: hidden; }
.chat-main { min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.archived-banner { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 14px; border-bottom: 1px solid var(--line); background: #fff9e8; color: #75602a; font-size: 12px; }
.archived-banner > span { display: flex; align-items: center; gap: 6px; }
.chat-scroll { min-height: 0; flex: 1 1 auto; overflow-y: auto; display: grid; align-content: start; gap: 18px; padding: 24px; overscroll-behavior: contain; }
.chat-message { display: grid; grid-template-columns: 34px minmax(0,1fr); gap: 10px; max-width: 820px; }
.chat-message.user { justify-self: end; grid-template-columns: minmax(0,1fr) 34px; }
.chat-message.user .chat-avatar { grid-column: 2; grid-row: 1; background: var(--red); }
.chat-message.user > div { grid-column: 1; grid-row: 1; background: var(--red-soft); }
.chat-avatar { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 50%; background: var(--green); color: #fff; }
.chat-message > div { padding: 13px 15px; border-radius: 8px; background: var(--surface-muted); }
.chat-message p { margin: 0; line-height: 1.8; white-space: pre-wrap; }
.assistant-answer { color: var(--text); overflow-wrap: anywhere; }
.assistant-answer p { margin: 0 0 13px; line-height: 1.8; white-space: normal; }
.assistant-answer p:last-child { margin-bottom: 0; }
.assistant-answer h1, .assistant-answer h2, .assistant-answer h3, .assistant-answer h4 { margin: 18px 0 9px; color: #2c4739; line-height: 1.35; }
.assistant-answer h1:first-child, .assistant-answer h2:first-child, .assistant-answer h3:first-child, .assistant-answer h4:first-child { margin-top: 0; }
.assistant-answer h1 { font-size: 21px; }
.assistant-answer h2 { font-size: 18px; }
.assistant-answer h3 { font-size: 16px; }
.assistant-answer h4 { font-size: 14px; }
.assistant-answer ul, .assistant-answer ol { margin: 9px 0 14px; padding-left: 25px; }
.assistant-answer li { padding-left: 3px; line-height: 1.75; }
.assistant-answer li + li { margin-top: 5px; }
.assistant-answer blockquote { margin: 12px 0; padding: 8px 12px; border-left: 3px solid #7da88c; background: #f4f8f4; color: #526158; }
.assistant-answer blockquote p { margin-bottom: 0; }
.assistant-answer code { padding: 2px 5px; border-radius: 4px; background: #e1e9e3; color: #315f47; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: .9em; }
.assistant-answer pre { max-width: 100%; margin: 13px 0; padding: 12px 13px; overflow-x: auto; border: 1px solid #d7e1d9; border-radius: 7px; background: #eef3ef; }
.assistant-answer pre code { padding: 0; background: transparent; color: #2f4437; font-size: 12px; line-height: 1.65; white-space: pre; }
.assistant-answer a { color: #287453; text-decoration: underline; text-underline-offset: 2px; }
.assistant-answer hr { margin: 17px 0; border: 0; border-top: 1px solid #d3dbd5; }
.assistant-answer table { display: block; max-width: 100%; margin: 13px 0; overflow-x: auto; border-collapse: collapse; }
.assistant-answer th, .assistant-answer td { min-width: 100px; padding: 7px 9px; border: 1px solid #cfdcd2; text-align: left; vertical-align: top; }
.assistant-answer th { background: #e6efe8; color: #315f47; font-weight: 700; }
.stream-cursor { display: inline-block; width: 2px; height: 1.05em; margin-left: 3px; vertical-align: -0.15em; border-radius: 1px; background: var(--green); animation: stream-cursor-blink 850ms steps(1,end) infinite; }
@keyframes stream-cursor-blink { 50% { opacity: 0; } }
.message-images { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.message-images img { width: 132px; height: 96px; object-fit: cover; border: 1px solid var(--line); border-radius: 6px; }
.message-actions { display: flex; align-items: center; gap: 3px; margin-top: 9px; }
.message-action { display: grid; width: 28px; height: 28px; place-items: center; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--muted); cursor: pointer; }
.message-action:hover, .message-action.copied { color: var(--green); background: #fff; }
.message-audio { margin-top: 0; }
.agent-stream-status { margin-top: 8px; color: var(--muted); font-size: 12px; }
.memory-applied { display: flex; align-items: center; gap: 5px; margin-top: 9px !important; color: var(--green); font-size: 12px; font-weight: 650; }
.agent-trace { margin-top: 10px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.agent-trace-toggle { display: flex; width: 100%; min-height: 38px; align-items: center; justify-content: space-between; gap: 10px; padding: 0; border: 0; background: transparent; color: #526158; font-size: 12px; cursor: pointer; }
.agent-trace-toggle > span { display: flex; min-width: 0; align-items: center; gap: 7px; overflow-wrap: anywhere; }
.agent-trace-toggle svg { flex: none; transition: transform 160ms ease; }
.agent-trace-toggle svg.rotated { transform: rotate(180deg); }
.agent-trace-list { display: grid; padding: 2px 0 10px 6px; }
.agent-trace-row { position: relative; display: grid; grid-template-columns: 24px minmax(0,1fr) auto; gap: 7px; min-height: 38px; align-items: start; color: var(--muted); font-size: 12px; }
.agent-trace-row:not(:last-child)::after { content: ""; position: absolute; left: 7px; top: 21px; bottom: -4px; width: 1px; background: var(--line); }
.trace-icon { z-index: 1; display: grid; width: 16px; height: 16px; place-items: center; margin-top: 1px; border-radius: 50%; background: var(--surface-muted); color: var(--green); }
.trace-running .trace-icon { color: #9b711b; }
.trace-failed .trace-icon { color: var(--red); }
.agent-trace-row strong, .agent-trace-row small { display: block; letter-spacing: 0; }
.agent-trace-row strong { color: #435047; font-size: 12px; font-weight: 650; line-height: 1.45; }
.agent-trace-row small { margin-top: 2px; color: var(--muted); line-height: 1.45; overflow-wrap: anywhere; }
.trace-duration { display: flex; align-items: center; gap: 3px; color: var(--muted); font-size: 10px; white-space: nowrap; }
.trace-spinner { animation: trace-spin 900ms linear infinite; }
@keyframes trace-spin { to { transform: rotate(360deg); } }
.retrieval-status { margin-top: 8px !important; font-size: 12px; }
.retrieval-ok { color: var(--green); }
.retrieval-empty { color: var(--muted); }
.retrieval-degraded { color: var(--red); }
.retrieval-unknown { color: var(--muted); }
.generation-status { margin-top: 5px !important; font-size: 12px; }
.generation-completed { color: var(--green); }
.generation-degraded { color: var(--red); }
.generation-skipped { color: var(--muted); }
.generation-unknown { color: var(--muted); }
.clarification { display: grid; gap: 3px; margin-top: 10px; padding: 8px 10px; border-left: 3px solid #c9a24b; background: #fff9e8; color: #75602a; font-size: 12px; line-height: 1.6; }
.related { margin-top: 10px !important; color: var(--muted); font-size: 13px; }
.chat-citations { display: grid; gap: 7px; margin-top: 14px; padding-top: 11px; border-top: 1px solid #d3dbd5; color: var(--muted); font-size: 12px; }
.chat-citations-label { color: #526158; font-size: 12px; font-weight: 650; }
.chat-citation-list { display: flex; flex-wrap: wrap; gap: 6px; }
.citation-chip { max-width: 100%; padding: 5px 9px; overflow: hidden; border: 1px solid #c7d7cb; border-radius: 999px; background: #f8fbf8; color: #397257; text-overflow: ellipsis; white-space: nowrap; }
.follow-ups { display: grid; gap: 7px; margin-top: 12px; }
.follow-ups-label { color: var(--muted); font-size: 12px; }
.follow-up-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.follow-ups button { min-height: 30px; padding: 0 9px; border: 1px solid #bdd1c3; border-radius: 4px; background: #fff; color: var(--green); cursor: pointer; font-size: 12px; }
.follow-ups button:hover:not(:disabled) { border-color: var(--green); background: var(--green-soft); }
.follow-ups button:disabled { cursor: not-allowed; opacity: .58; }
.composer-memory-suggestions { display: grid; gap: 9px; padding: 12px 14px; border-top: 1px solid #bbd8c2; border-bottom: 1px solid #cfe0d3; background: linear-gradient(90deg, #edf7ef 0%, #f8fbf8 68%); }
.composer-memory-suggestions-error { border-color: #efc7c2; background: linear-gradient(90deg, #fff4f2 0%, #fffafa 68%); }
.composer-memory-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.composer-memory-heading strong { display: flex; align-items: center; gap: 7px; color: #275f44; font-size: 14px; }
.composer-memory-heading p { margin: 4px 0 0; color: #587060; font-size: 12px; line-height: 1.5; }
.composer-memory-candidates { display: grid; gap: 7px; }
.composer-memory-candidate-card { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 10px 11px; border: 1px solid #b8d5bf; border-radius: 8px; background: #fff; box-shadow: 0 1px 2px rgba(31, 75, 51, .05); }
.composer-memory-candidate-content { min-width: 0; }
.composer-memory-type { display: inline-flex; align-items: center; min-height: 20px; padding: 0 7px; border-radius: 999px; background: #e4f1e7; color: #2b704c; font-size: 11px; font-weight: 700; }
.composer-memory-candidate-content p { margin: 5px 0 0; color: var(--text); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.composer-memory-candidate-actions { display: flex; flex: none; gap: 6px; }
.composer-memory-candidate-actions button { display: inline-flex; min-height: 32px; align-items: center; justify-content: center; gap: 5px; padding: 0 10px; border: 1px solid #2e7650; border-radius: 6px; background: var(--green); color: #fff; cursor: pointer; font-size: 12px; font-weight: 650; }
.composer-memory-candidate-actions button:last-child { border-color: #c7d3c9; background: #fff; color: #5d6a61; }
.composer-memory-candidate-actions button:hover:not(:disabled) { filter: brightness(.96); }
.composer-memory-candidate-actions button:focus-visible { outline: 3px solid rgba(46, 118, 80, .25); outline-offset: 2px; }
.composer-memory-candidate-actions button:disabled { cursor: not-allowed; opacity: .52; }
.composer-memory-feedback { margin: 0; font-size: 12px; line-height: 1.5; }
.composer-memory-feedback-success { color: #28704b; }
.composer-memory-feedback-error { color: #ad3e35; }
.chat-composer { display: grid; grid-template-columns: minmax(0,1fr); gap: 8px; padding: 10px 14px 12px; border-top: 1px solid var(--line); background: #fff; }
.chat-composer-readonly { background: #f8f9f7; }
.pending-images { grid-column: 1 / -1; display: flex; gap: 8px; overflow-x: auto; }
.pending-images > div { position: relative; flex: none; width: 72px; height: 58px; }
.pending-images img { width: 100%; height: 100%; object-fit: cover; border: 1px solid var(--line); border-radius: 6px; }
.pending-images button { position: absolute; top: -5px; right: -5px; display: grid; width: 20px; height: 20px; place-items: center; padding: 0; border: 1px solid var(--line); border-radius: 50%; background: #fff; color: var(--red); cursor: pointer; }
.chat-composer textarea { width: 100%; min-height: 48px; max-height: 120px; resize: none; }
.chat-composer textarea:disabled { background: #f2f3f0; color: var(--muted); cursor: not-allowed; }
.composer-toolbar { display: flex; min-width: 0; align-items: center; justify-content: flex-end; gap: 12px; }
.composer-tools, .composer-actions { display: flex; min-width: 0; align-items: center; gap: 5px; }
.composer-tools { overflow: hidden; }
.composer-divider { width: 1px; height: 22px; flex: none; background: var(--line); }
.composer-model { display: block; min-width: 0; }
.composer-model-select { width: min(220px, 28vw); min-height: 34px; padding: 0 28px 0 9px; border: 1px solid transparent; border-radius: 7px; background: transparent; color: var(--text); font-size: 12px; font-weight: 600; }
.composer-model-select:hover:not(:disabled), .composer-model-select:focus-visible { border-color: var(--line); background: #f8f9f7; outline: none; }
.composer-model-select:disabled { color: var(--muted); cursor: not-allowed; }
.composer-icon { display: grid; width: 36px; height: 36px; place-items: center; flex: none; padding: 0; border: 0; border-radius: 6px; background: transparent; color: var(--muted); cursor: pointer; }
.composer-icon:hover:not(:disabled), .composer-icon.active { color: var(--green); background: var(--green-soft); }
.composer-icon.active { animation: mic-pulse 1s ease-in-out infinite alternate; }
.composer-icon:disabled { opacity: .4; cursor: not-allowed; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
.send-button { width: 44px; min-height: 44px; padding: 0; align-self: center; }
.stop-button { min-height: 44px; padding: 0 10px; align-self: center; color: var(--red); }
.stream-retry-button { margin-left: 8px; border: 0; border-radius: 5px; padding: 4px 8px; color: #fff; background: var(--green); cursor: pointer; }
.stream-retry-button:disabled { cursor: not-allowed; opacity: .55; }
.typing { display: flex; gap: 5px; padding-left: 44px; }
.typing span { width: 7px; height: 7px; border-radius: 50%; background: #8ca094; animation: pulse 900ms infinite alternate; }
.typing span:nth-child(2) { animation-delay: 150ms; }.typing span:nth-child(3) { animation-delay: 300ms; }
@keyframes pulse { to { opacity: .3; transform: translateY(-3px); } }
@keyframes mic-pulse { to { background: #dcebe0; } }
@media (max-width: 900px) {
  .assistant-layout { height: calc(100svh - 154px); min-height: 520px; grid-template-columns: 1fr; }
  .assistant-side { max-height: 260px; overflow-y: auto; border-right: 0; border-bottom: 1px solid var(--line); padding: 14px; }
  .assistant-side > div:first-child, .assistant-side-actions { display: none; }
  .history-list { flex: 0 0 150px; height: 150px; min-height: 70px; max-height: 150px; }
  .chat-scroll { padding: 16px 12px; }
  .chat-message { max-width: 92%; }
  .chat-composer { padding: 8px 10px 10px; }
  .composer-toolbar { gap: 8px; }
  .composer-model-select { width: min(180px, 48vw); font-size: 11px; }
  .composer-actions { gap: 2px; }
  .composer-icon { width: 32px; height: 36px; }
  .send-button { width: 40px; min-height: 40px; }
  .message-images img { width: 108px; height: 80px; }
  .composer-memory-suggestions { padding: 10px; }
  .composer-memory-candidate-card { align-items: stretch; flex-direction: column; }
  .composer-memory-candidate-actions { width: 100%; }
  .composer-memory-candidate-actions button { flex: 1 1 0; }
}
</style>
