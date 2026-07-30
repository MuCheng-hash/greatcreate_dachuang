<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { Archive, ArchiveRestore, Bot, BrainCircuit, Check, ChevronDown, Clock3, History, ImagePlus, LoaderCircle, MessageCircleQuestion, Mic, Plus, Send, Sparkles, Trash2, UserRound, Volume2, VolumeX, Wrench, X } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { api } from "@/services/api";
import { useSchoolStore } from "@/stores/school";
import { useAuthStore, type AuthCurrentUser } from "@/stores/auth";
import type {
  AgentCitation,
  AgentAttachment,
  AgentQaRequestPayload,
  AgentQaResponse,
  AgentSseEventData,
  AgentSseEventName,
  LlmModelOption,
  AssistantConversationDetail,
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
const pendingImages = ref<AgentAttachment[]>([]);
const listening = ref(false);
const speakingIndex = ref<number | null>(null);
const recognition = ref<SpeechRecognitionLike | null>(null);

const suggestions = computed(() => {
  const resourceName = schoolStore.resources[0]?.resource?.resourceName;
  return [
    resourceName ? `怎样利用${resourceName}开展一节实践课？` : "怎样利用学校周边资源开展一节实践课？",
    "哪些资源更适合小学阶段的思政教育？",
    "请给出一次校外实践活动的安全注意事项。"
  ];
});

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
  try {
    const detail = await api.get<AssistantConversationDetail>(`/api/ai/qa/history/${selectedThreadId}`);
    messages.value = detail.messages
      .filter((item) => item.role === "user" || item.role === "assistant")
      .map((item) => item.role === "user"
        ? { role: "user", text: item.content }
        : { role: "assistant", answer: item.content, citations: [] });
    threadId.value = detail.threadId;
    readOnlyConversation.value = detail.status === "archived";
    conversationId.value = makeConversationId();
    await scrollToBottom();
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

function retrievalStatusLabel(status?: string | null): string {
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
  messages.value.push({ role: "user", text: userText, attachments });
  const assistantMessage: AssistantMessage = {
    role: "assistant", answer: "", relatedResources: [], citations: [], followUpQuestions: [],
    toolEvents: [], traceEvents: [], traceExpanded: true, streamStatus: "正在启动 Agent…"
  };
  messages.value.push(assistantMessage);
  loading.value = true;
  const abortController = new AbortController();
  activeAbortController.value = abortController;
  await scrollToBottom();
  try {
    const requestBody: AgentQaRequestPayload = {
      question: userText,
      threadId: threadId.value || null,
      scopeType: "SCHOOL",
      scopeId: schoolStore.school?.schoolId || auth.user?.schoolId || null
    };
    if (attachments.length) requestBody.attachments = attachments;
    if (selectedModelId.value) requestBody.modelId = selectedModelId.value;
    if (!threadId.value) requestBody.conversationId = conversationId.value;
    let finalReceived = false;
    let streamError: Error | null = null;

    if (typeof api.stream !== "function") {
      applyAssistantResult(assistantMessage, await api.post<AgentQaResponse>("/api/ai/qa/ask", requestBody));
    } else {
      await api.stream("/api/ai/qa/stream", requestBody, {
        signal: abortController.signal,
        onEvent(eventName: AgentSseEventName, data: AgentSseEventData) {
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
            if (data.reset) assistantMessage.answer = "";
            assistantMessage.streamStatus = `正在切换备用模型：${data.nextModel || "轻量模型"}`;
          } else if (eventName === "token") {
            assistantMessage.answer += data.delta || "";
            updateTrace(assistantMessage, "response", { kind: "response", title: "生成回答", status: "running" });
            assistantMessage.streamStatus = "正在生成回答";
          } else if (eventName === "final") {
            finalReceived = true;
            applyAssistantResult(assistantMessage, data.response || {});
            if (data.response?.conversationId) conversationId.value = data.response.conversationId;
            if (data.response?.threadId) threadId.value = data.response.threadId;
            assistantMessage.streamStatus = "回答完成";
            updateTrace(assistantMessage, "response", { kind: "response", title: "回答生成完成", status: "completed" });
            void loadHistory();
          } else if (eventName === "error") {
            updateTrace(assistantMessage, "error", { kind: "error", title: "处理失败", detail: data.message, status: "failed" });
            streamError = new Error(data.message || "Agent 流式服务异常");
          }
        }
      });
      if (streamError && !finalReceived) throw streamError;
      if (!finalReceived) throw new Error("流式服务未返回最终结果");
    }
  } catch (requestError) {
    const requestFailure = requestError instanceof Error ? requestError : new Error("请求失败");
    if (requestFailure.name === "AbortError") {
      assistantMessage.streamStatus = "已停止生成";
      if (!assistantMessage.answer) messages.value.pop();
      return;
    }
    try {
      const result = await api.post<AgentQaResponse>("/api/ai/qa/ask", {
        question: userText,
        conversationId: conversationId.value,
        threadId: threadId.value || null,
        scopeType: "SCHOOL",
        scopeId: schoolStore.school?.schoolId || auth.user?.schoolId || null,
        ...(attachments.length ? { attachments } : {}),
        ...(selectedModelId.value ? { modelId: selectedModelId.value } : {})
      });
      applyAssistantResult(assistantMessage, result);
      error.value = `${requestFailure.message}，已切换到兼容问答接口`;
      return;
    } catch {
    const resourceCount = schoolStore.resources.length;
    assistantMessage.answer = `${schoolStore.school?.schoolName || "当前学校"}现有 ${resourceCount} 个已关联周边资源。围绕“${userText}”，建议优先选择距离近、可达性高的资源，并按课堂导入、现场观察、实践反思三个阶段组织活动。`;
    assistantMessage.citations = ["当前为本地兜底回答，智能问答服务恢复后可获得更完整的引用结果。"];
    assistantMessage.retrievalStatus = "degraded";
    assistantMessage.generationStatus = "degraded";
    error.value = requestFailure.message;
    }
  } finally {
    loading.value = false;
    activeAbortController.value = null;
    await scrollToBottom();
  }
}

function applyAssistantResult(message: AssistantMessage, result: Partial<AgentQaResponse>): void {
  Object.assign(message, {
    answer: result?.answer || "服务未返回回答。",
    relatedResources: result?.relatedResources || [],
    citations: result?.citations || [],
    followUpQuestions: result?.followUpQuestions || [],
    retrievalStatus: result?.retrievalStatus || null,
    generationStatus: result?.generationStatus || null,
    clarificationRequired: Boolean(result?.clarificationRequired),
    clarificationMessage: result?.clarificationMessage || "",
    clarificationOptions: result?.clarificationOptions || [],
    conversationId: result?.conversationId || message.conversationId,
    threadId: result?.threadId || message.threadId,
    runId: result?.runId || message.runId,
    fallbackLevel: result?.fallbackLevel || null,
    effectiveModel: result?.model ? `${result.provider || "LLM"} / ${result.model}` : message.effectiveModel,
    streamStatus: result?.generationStatus === "degraded" ? "已使用降级回答" : "回答完成"
  });
  if (result?.conversationId) conversationId.value = result.conversationId;
  if (result?.threadId) threadId.value = result.threadId;
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
  activeAbortController.value?.abort();
}

async function scrollToBottom(): Promise<void> {
  await nextTick();
  if (chatScroll.value) chatScroll.value.scrollTop = chatScroll.value.scrollHeight;
}

function clearChat(): void {
  const wasArchivedMode = historyMode.value === "archived";
  recognition.value?.stop();
  window.speechSynthesis?.cancel();
  pendingImages.value = [];
  speakingIndex.value = null;
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
        <label class="model-field">回答模型
          <select v-model="selectedModelId" :disabled="loading">
            <option value="">系统默认</option>
            <option v-for="item in models" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.provider }}</option>
          </select>
        </label>
        <button class="primary-button full-button" type="button" :disabled="loading || readOnlyConversation" @click="explain"><Sparkles :size="17" />生成学校讲解</button>
        <div class="suggestion-list"><h3>建议提问</h3><button v-for="item in suggestions" :key="item" type="button" :disabled="readOnlyConversation" @click="ask(item)">{{ item }}</button></div>
        <button class="text-button clear-button" type="button" @click="clearChat"><Trash2 :size="16" />清空会话</button>
      </aside>

      <div class="chat-area">
        <div v-if="readOnlyConversation" class="archived-banner" role="status">
          <span><Archive :size="15" />这是归档对话，仅供查看。</span>
          <button class="text-button" type="button" :disabled="Boolean(historyBusyId)" @click="restoreConversation(threadId)">恢复对话</button>
        </div>
        <div ref="chatScroll" class="chat-scroll" aria-live="polite">
          <InlineNotice v-if="error" tone="info">{{ error }}，已显示本地参考回答。</InlineNotice>
          <article v-for="(message,index) in messages" :key="index" class="chat-message" :class="message.role">
            <span class="chat-avatar"><UserRound v-if="message.role === 'user'" :size="17" /><Bot v-else :size="17" /></span>
            <div>
              <p>{{ message.text || message.answer }}</p>
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
              <div v-if="message.effectiveModel" class="agent-stream-status">实际模型：{{ message.effectiveModel }}</div>
              <p v-if="message.retrievalStatus" class="retrieval-status" :class="retrievalStatusClass(message.retrievalStatus)">{{ retrievalStatusLabel(message.retrievalStatus) }}</p>
              <p v-if="message.generationStatus" class="generation-status" :class="generationStatusClass(message.generationStatus)">{{ generationStatusLabel(message.generationStatus) }}</p>
              <div v-if="message.clarificationRequired" class="clarification"><strong>需要补充：</strong>{{ message.clarificationMessage || "请补充具体学校名称。" }}<span v-if="message.clarificationOptions?.length">可选：{{ message.clarificationOptions.join("、") }}</span></div>
              <p v-if="message.relatedResources?.length" class="related"><strong>关联资源：</strong>{{ message.relatedResources.join("、") }}</p>
              <div v-if="message.citations?.length" class="chat-citations"><span v-for="(citation,citationIndex) in message.citations" :key="citationIndex">{{ typeof citation === "string" ? citation : citation.title || citation.excerpt }}</span></div>
              <div v-if="message.followUpQuestions?.length" class="follow-ups"><button v-for="item in message.followUpQuestions" :key="item" type="button" :disabled="readOnlyConversation" @click="ask(item)">{{ item }}</button></div>
              <button v-if="message.role === 'assistant' && message.answer" class="message-audio" type="button" :title="speakingIndex === index ? '停止朗读' : '朗读回答'" :aria-label="speakingIndex === index ? '停止朗读' : '朗读回答'" @click="toggleSpeech(message, index)">
                <VolumeX v-if="speakingIndex === index" :size="15" /><Volume2 v-else :size="15" />
              </button>
            </div>
          </article>
          <div v-if="loading" class="typing"><span></span><span></span><span></span></div>
          <div v-if="!messages.length && !loading" class="empty-state"><MessageCircleQuestion :size="42" /><span>选择建议问题或输入你想了解的内容</span></div>
        </div>
        <form class="chat-composer" :class="{ 'chat-composer-readonly': readOnlyConversation }" @submit.prevent="ask()">
          <div v-if="pendingImages.length" class="pending-images">
            <div v-for="(attachment,index) in pendingImages" :key="attachment.name + index">
              <img :src="attachment.dataUrl" :alt="attachment.name" />
              <button type="button" title="移除图片" aria-label="移除图片" :disabled="readOnlyConversation" @click="removeImage(index)"><X :size="14" /></button>
            </div>
          </div>
          <textarea v-model="question" rows="2" :disabled="readOnlyConversation || loading" :placeholder="readOnlyConversation ? '归档对话仅供查看，请先恢复对话' : '输入关于学校资源或教学活动的问题'" @keydown.ctrl.enter.prevent="ask()"></textarea>
          <div class="composer-actions">
            <input ref="imageInput" class="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp,image/gif" multiple @change="addImages" />
            <button class="composer-icon" type="button" title="添加图片" aria-label="添加图片" :disabled="readOnlyConversation || loading || pendingImages.length >= 3" @click="chooseImages"><ImagePlus :size="19" /></button>
            <button class="composer-icon" :class="{ active: listening }" type="button" :title="listening ? '停止语音输入' : '语音输入'" :aria-label="listening ? '停止语音输入' : '语音输入'" :disabled="readOnlyConversation || loading" @click="toggleListening"><Mic :size="19" /></button>
            <button v-if="loading" class="text-button stop-button" type="button" @click="stopGeneration">停止</button>
            <button v-else class="primary-button send-button" type="submit" :disabled="readOnlyConversation || (!question.trim() && !pendingImages.length)" aria-label="发送问题"><Send :size="19" /></button>
          </div>
        </form>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.assistant-layout { display: grid; grid-template-columns: 280px minmax(0,1fr); grid-template-rows: minmax(0,1fr); height: calc(100vh - 122px); min-height: 600px; overflow: hidden; }
.assistant-side { display: flex; min-height: 0; flex-direction: column; gap: 20px; overflow-y: auto; padding: 22px; border-right: 1px solid var(--line); background: #f8f9f7; }
.assistant-mark { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 8px; background: var(--green); color: #fff; }
.assistant-side h2 { margin: 15px 0 6px; font-size: 18px; }
.assistant-side p { color: var(--muted); font-size: 13px; line-height: 1.65; }
.model-field { display: grid; gap: 7px; color: var(--muted); font-size: 13px; }
.history-heading { display: flex; align-items: center; justify-content: space-between; }
.history-heading h3 { display: flex; align-items: center; gap: 7px; margin: 0; font-size: 13px; }
.history-heading-actions { display: flex; align-items: center; gap: 5px; }
.history-mode-toggle { display: flex; align-items: center; gap: 4px; min-height: 30px; padding: 0 7px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: var(--muted); cursor: pointer; font-size: 11px; }
.history-mode-toggle:hover { color: var(--red); background: #f4efed; }
.icon-action, .history-archive { display: grid; place-items: center; border: 0; background: transparent; color: var(--muted); cursor: pointer; }
.icon-action { width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 6px; background: #fff; }
.history-list { display: grid; align-content: start; gap: 3px; min-height: 70px; max-height: 250px; overflow-y: auto; }
.history-state { padding: 12px 4px; color: var(--muted); font-size: 12px; }
.history-row { display: grid; grid-template-columns: minmax(0,1fr) 28px; align-items: center; border-left: 3px solid transparent; }
.history-row.active { border-left-color: var(--red); background: #fff; }
.history-open { min-width: 0; padding: 9px 7px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.history-open > span { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.history-open strong, .history-open small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-open strong { color: var(--text); font-size: 12px; }
.history-open time { flex: none; color: var(--muted); font-size: 10px; }
.history-open small { margin-top: 4px; color: var(--muted); font-size: 11px; }
.history-archive { width: 28px; height: 28px; border-radius: 5px; }
.history-archive:hover, .icon-action:hover { color: var(--red); background: #f4efed; }
.history-restore { display: grid; width: 28px; height: 28px; place-items: center; border: 0; border-radius: 5px; background: transparent; color: var(--green); cursor: pointer; }
.history-restore:hover { background: var(--green-soft); }
.suggestion-list { display: grid; gap: 7px; }
.suggestion-list h3 { margin-bottom: 3px; color: var(--muted); font-size: 12px; }
.suggestion-list button { padding: 10px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: #445047; font-size: 13px; line-height: 1.5; text-align: left; }
.suggestion-list button:hover { border-color: #a9b9ad; background: var(--green-soft); }
.clear-button { justify-content: flex-start; margin-top: auto; color: var(--muted); }
.chat-area { min-width: 0; min-height: 0; display: grid; grid-template-rows: auto minmax(0,1fr) auto; overflow: hidden; }
.archived-banner { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 14px; border-bottom: 1px solid var(--line); background: #fff9e8; color: #75602a; font-size: 12px; }
.archived-banner > span { display: flex; align-items: center; gap: 6px; }
.chat-scroll { min-height: 0; overflow-y: auto; display: grid; align-content: start; gap: 18px; padding: 24px; overscroll-behavior: contain; }
.chat-message { display: grid; grid-template-columns: 34px minmax(0,1fr); gap: 10px; max-width: 820px; }
.chat-message.user { justify-self: end; grid-template-columns: minmax(0,1fr) 34px; }
.chat-message.user .chat-avatar { grid-column: 2; grid-row: 1; background: var(--red); }
.chat-message.user > div { grid-column: 1; grid-row: 1; background: var(--red-soft); }
.chat-avatar { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 50%; background: var(--green); color: #fff; }
.chat-message > div { padding: 13px 15px; border-radius: 8px; background: var(--surface-muted); }
.chat-message p { margin: 0; line-height: 1.8; white-space: pre-wrap; }
.message-images { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.message-images img { width: 132px; height: 96px; object-fit: cover; border: 1px solid var(--line); border-radius: 6px; }
.message-audio { display: grid; width: 28px; height: 28px; place-items: center; margin-top: 8px; border: 0; border-radius: 50%; background: transparent; color: var(--muted); cursor: pointer; }
.message-audio:hover { color: var(--green); background: #fff; }
.agent-stream-status { margin-top: 8px; color: var(--muted); font-size: 12px; }
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
.chat-citations { display: grid; gap: 5px; margin-top: 12px; padding-top: 10px; border-top: 1px solid #d3dbd5; color: var(--muted); font-size: 12px; }
.follow-ups { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 11px; }
.follow-ups button { min-height: 30px; padding: 0 9px; border: 1px solid #bdd1c3; border-radius: 4px; background: #fff; color: var(--green); font-size: 12px; }
.chat-composer { display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 9px; padding: 14px; border-top: 1px solid var(--line); background: #fff; }
.chat-composer-readonly { background: #f8f9f7; }
.pending-images { grid-column: 1 / -1; display: flex; gap: 8px; overflow-x: auto; }
.pending-images > div { position: relative; flex: none; width: 72px; height: 58px; }
.pending-images img { width: 100%; height: 100%; object-fit: cover; border: 1px solid var(--line); border-radius: 6px; }
.pending-images button { position: absolute; top: -5px; right: -5px; display: grid; width: 20px; height: 20px; place-items: center; padding: 0; border: 1px solid var(--line); border-radius: 50%; background: #fff; color: var(--red); cursor: pointer; }
.chat-composer textarea { min-height: 48px; max-height: 120px; resize: none; }
.chat-composer textarea:disabled { background: #f2f3f0; color: var(--muted); cursor: not-allowed; }
.composer-actions { display: flex; align-items: flex-end; gap: 5px; }
.composer-icon { display: grid; width: 36px; height: 44px; place-items: center; padding: 0; border: 0; border-radius: 6px; background: transparent; color: var(--muted); cursor: pointer; }
.composer-icon:hover:not(:disabled), .composer-icon.active { color: var(--green); background: var(--green-soft); }
.composer-icon.active { animation: mic-pulse 1s ease-in-out infinite alternate; }
.composer-icon:disabled { opacity: .4; cursor: not-allowed; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
.send-button { width: 44px; min-height: 44px; padding: 0; align-self: end; }
.stop-button { min-height: 44px; padding: 0 10px; align-self: end; color: var(--red); }
.typing { display: flex; gap: 5px; padding-left: 44px; }
.typing span { width: 7px; height: 7px; border-radius: 50%; background: #8ca094; animation: pulse 900ms infinite alternate; }
.typing span:nth-child(2) { animation-delay: 150ms; }.typing span:nth-child(3) { animation-delay: 300ms; }
@keyframes pulse { to { opacity: .3; transform: translateY(-3px); } }
@keyframes mic-pulse { to { background: #dcebe0; } }
@media (max-width: 900px) {
  .assistant-layout { height: calc(100svh - 154px); min-height: 520px; grid-template-columns: 1fr; }
  .assistant-side { max-height: 260px; overflow-y: auto; border-right: 0; border-bottom: 1px solid var(--line); padding: 14px; }
  .assistant-side > div:first-child, .assistant-side > .primary-button, .assistant-side > .suggestion-list, .assistant-side > .clear-button { display: none; }
  .history-list { max-height: 150px; }
  .chat-scroll { padding: 16px 12px; }
  .chat-message { max-width: 92%; }
  .chat-composer { padding: 10px; }
  .composer-actions { gap: 2px; }
  .composer-icon { width: 32px; }
  .message-images img { width: 108px; height: 80px; }
}
</style>
