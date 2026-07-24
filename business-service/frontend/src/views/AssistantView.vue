<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { Bot, MessageCircleQuestion, Send, Sparkles, Trash2, UserRound } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { api } from "@/services/api";
import { useSchoolStore } from "@/stores/school";
import { useAuthStore, type AuthCurrentUser } from "@/stores/auth";
import type {
  AgentCitation,
  AgentQaRequestPayload,
  AgentQaResponse,
  AgentSseEventData,
  AgentSseEventName,
} from "@/types/agent";

interface AssistantToolEvent {
  toolName?: string;
  status?: string;
}

interface AssistantMessage extends AgentQaResponse {
  role: "user" | "assistant";
  text?: string;
  answer?: string;
  citations?: Array<AgentCitation | string>;
  toolEvents?: AssistantToolEvent[];
  streamStatus?: string;
}

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

const suggestions = computed(() => {
  const resourceName = schoolStore.resources[0]?.resource?.resourceName;
  return [
    resourceName ? `怎样利用${resourceName}开展一节实践课？` : "怎样利用学校周边资源开展一节实践课？",
    "哪些资源更适合小学阶段的思政教育？",
    "请给出一次校外实践活动的安全注意事项。"
  ];
});

onMounted(async () => {
  await schoolStore.load();
  sessionStorage.setItem(conversationStorageKey(), conversationId.value);
  if (threadId.value) sessionStorage.setItem(threadStorageKey(), threadId.value);
  if (!messages.value.length) {
    messages.value.push({ role: "assistant", answer: `你好，我可以结合${schoolStore.school?.schoolName || "本校"}的周边资源，协助你进行教学讲解和活动设计。`, citations: [] });
  }
});

watch(messages, (value) => sessionStorage.setItem(storageKey(), JSON.stringify(value)), { deep: true });
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
  return {
    ok: "已结合知识检索证据",
    empty: "未检索到直接匹配的知识证据",
    degraded: "知识检索部分不可用，当前回答基于可用业务数据"
  }[status] || "知识检索状态未知";
}

function retrievalStatusClass(status?: string | null): string {
  return `retrieval-${status || "unknown"}`;
}

function generationStatusLabel(status?: string | null): string {
  return {
    completed: "已由答案生成服务整理",
    degraded: "答案生成服务不可用，当前为本地降级回答",
    skipped: "未调用答案生成服务"
  }[status] || "答案生成状态未知";
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
  await requestAssistant("请介绍本校周边可用于思政教学的资源。");
}

async function ask(text: string = question.value): Promise<void> {
  const clean = text.trim();
  if (!clean || loading.value) return;
  question.value = "";
  await requestAssistant(clean);
}

async function requestAssistant(userText: string): Promise<void> {
  error.value = "";
  messages.value.push({ role: "user", text: userText });
  const assistantMessage: AssistantMessage = {
    role: "assistant", answer: "", relatedResources: [], citations: [], followUpQuestions: [],
    toolEvents: [], streamStatus: "正在启动 Agent…"
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
          } else if (eventName === "tool.started") {
            assistantMessage.toolEvents.push({ toolName: data.toolName, status: "started" });
            assistantMessage.streamStatus = `正在调用：${toolLabel(data.toolName)}`;
          } else if (eventName === "tool.completed") {
            const previous = [...assistantMessage.toolEvents].reverse().find(item => item.toolName === data.toolName && item.status === "started");
            if (previous) previous.status = data.status || "completed";
            else assistantMessage.toolEvents.push({ toolName: data.toolName, status: data.status || "completed" });
            assistantMessage.streamStatus = data.status === "ok" ? "工具结果已返回，正在整理回答" : "部分工具不可用，正在降级处理";
          } else if (eventName === "model.fallback") {
            if (data.reset) assistantMessage.answer = "";
            assistantMessage.streamStatus = `正在切换备用模型：${data.nextModel || "轻量模型"}`;
          } else if (eventName === "token") {
            assistantMessage.answer += data.delta || "";
            assistantMessage.streamStatus = "正在生成回答";
          } else if (eventName === "final") {
            finalReceived = true;
            applyAssistantResult(assistantMessage, data.response || {});
            if (data.response?.conversationId) conversationId.value = data.response.conversationId;
            if (data.response?.threadId) threadId.value = data.response.threadId;
            assistantMessage.streamStatus = "回答完成";
          } else if (eventName === "error") {
            streamError = new Error(data.message || "Agent 流式服务异常");
          }
        }
      });
      if (streamError && !finalReceived) throw streamError;
      if (!finalReceived) throw new Error("流式服务未返回最终结果");
    }
  } catch (requestError) {
    if (requestError?.name === "AbortError") {
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
        scopeId: schoolStore.school?.schoolId || auth.user?.schoolId || null
      });
      applyAssistantResult(assistantMessage, result);
      error.value = `${requestError.message}，已切换到兼容问答接口`;
      return;
    } catch {
    const resourceCount = schoolStore.resources.length;
    assistantMessage.answer = `${schoolStore.school?.schoolName || "当前学校"}现有 ${resourceCount} 个已关联周边资源。围绕“${userText}”，建议优先选择距离近、可达性高的资源，并按课堂导入、现场观察、实践反思三个阶段组织活动。`;
    assistantMessage.citations = ["当前为本地兜底回答，智能问答服务恢复后可获得更完整的引用结果。"];
    assistantMessage.retrievalStatus = "degraded";
    assistantMessage.generationStatus = "degraded";
    error.value = requestError.message;
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
    streamStatus: result?.generationStatus === "degraded" ? "已使用降级回答" : "回答完成"
  });
  if (result?.conversationId) conversationId.value = result.conversationId;
  if (result?.threadId) threadId.value = result.threadId;
}

function toolLabel(toolName?: string): string {
  return {
    "/internal/agent/tools/school-context": "学校资源",
    "/internal/agent/tools/resource-detail": "资源详情",
    "/internal/agent/tools/knowledge-retrieve": "知识检索",
    "/internal/agent/tools/relation-query": "图谱关系"
  }[toolName] || toolName || "受控工具";
}

function stopGeneration(): void {
  activeAbortController.value?.abort();
}

async function scrollToBottom(): Promise<void> {
  await nextTick();
  if (chatScroll.value) chatScroll.value.scrollTop = chatScroll.value.scrollHeight;
}

function clearChat(): void {
  messages.value = [];
  threadId.value = "";
  sessionStorage.removeItem(storageKey());
  sessionStorage.removeItem(conversationStorageKey());
  conversationId.value = makeConversationId();
  sessionStorage.removeItem(threadStorageKey());
}
</script>

<template>
  <AppShell title="智能问答" subtitle="基于本校资源进行讲解、追问与教学活动构思">
    <section class="assistant-layout page-panel">
      <aside class="assistant-side">
        <div><span class="assistant-mark"><Bot :size="22" /></span><h2>学校资源助手</h2><p>回答会结合本校周边资源与已有教学方案。</p></div>
        <button class="primary-button full-button" type="button" :disabled="loading" @click="explain"><Sparkles :size="17" />生成学校讲解</button>
        <div class="suggestion-list"><h3>建议提问</h3><button v-for="item in suggestions" :key="item" type="button" @click="ask(item)">{{ item }}</button></div>
        <button class="text-button clear-button" type="button" @click="clearChat"><Trash2 :size="16" />清空会话</button>
      </aside>

      <div class="chat-area">
        <div ref="chatScroll" class="chat-scroll" aria-live="polite">
          <InlineNotice v-if="error" tone="info">{{ error }}，已显示本地参考回答。</InlineNotice>
          <article v-for="(message,index) in messages" :key="index" class="chat-message" :class="message.role">
            <span class="chat-avatar"><UserRound v-if="message.role === 'user'" :size="17" /><Bot v-else :size="17" /></span>
            <div>
              <p>{{ message.text || message.answer }}</p>
              <div v-if="message.streamStatus" class="agent-stream-status">{{ message.streamStatus }}</div>
              <div v-if="message.toolEvents?.length" class="agent-tool-events"><span v-for="(toolEvent,toolIndex) in message.toolEvents" :key="toolIndex">{{ toolLabel(toolEvent.toolName) }}：{{ toolEvent.status === "started" ? "进行中" : toolEvent.status === "ok" ? "完成" : "降级" }}</span></div>
              <p v-if="message.retrievalStatus" class="retrieval-status" :class="retrievalStatusClass(message.retrievalStatus)">{{ retrievalStatusLabel(message.retrievalStatus) }}</p>
              <p v-if="message.generationStatus" class="generation-status" :class="generationStatusClass(message.generationStatus)">{{ generationStatusLabel(message.generationStatus) }}</p>
              <div v-if="message.clarificationRequired" class="clarification"><strong>需要补充：</strong>{{ message.clarificationMessage || "请补充具体学校名称。" }}<span v-if="message.clarificationOptions?.length">可选：{{ message.clarificationOptions.join("、") }}</span></div>
              <p v-if="message.relatedResources?.length" class="related"><strong>关联资源：</strong>{{ message.relatedResources.join("、") }}</p>
              <div v-if="message.citations?.length" class="chat-citations"><span v-for="(citation,citationIndex) in message.citations" :key="citationIndex">{{ typeof citation === "string" ? citation : citation.title || citation.excerpt }}</span></div>
              <div v-if="message.followUpQuestions?.length" class="follow-ups"><button v-for="item in message.followUpQuestions" :key="item" type="button" @click="ask(item)">{{ item }}</button></div>
            </div>
          </article>
          <div v-if="loading" class="typing"><span></span><span></span><span></span></div>
          <div v-if="!messages.length && !loading" class="empty-state"><MessageCircleQuestion :size="42" /><span>选择建议问题或输入你想了解的内容</span></div>
        </div>
        <form class="chat-composer" @submit.prevent="ask()">
          <textarea v-model="question" rows="2" placeholder="输入关于学校资源或教学活动的问题" @keydown.ctrl.enter.prevent="ask()"></textarea>
          <button v-if="loading" class="text-button stop-button" type="button" @click="stopGeneration">停止</button>
          <button v-else class="primary-button send-button" type="submit" :disabled="!question.trim()" aria-label="发送问题"><Send :size="19" /></button>
        </form>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.assistant-layout { display: grid; grid-template-columns: 280px minmax(0,1fr); height: calc(100vh - 122px); min-height: 600px; overflow: hidden; }
.assistant-side { display: flex; flex-direction: column; gap: 20px; padding: 22px; border-right: 1px solid var(--line); background: #f8f9f7; }
.assistant-mark { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 8px; background: var(--green); color: #fff; }
.assistant-side h2 { margin: 15px 0 6px; font-size: 18px; }
.assistant-side p { color: var(--muted); font-size: 13px; line-height: 1.65; }
.suggestion-list { display: grid; gap: 7px; }
.suggestion-list h3 { margin-bottom: 3px; color: var(--muted); font-size: 12px; }
.suggestion-list button { padding: 10px; border: 1px solid var(--line); border-radius: 6px; background: #fff; color: #445047; font-size: 13px; line-height: 1.5; text-align: left; }
.suggestion-list button:hover { border-color: #a9b9ad; background: var(--green-soft); }
.clear-button { justify-content: flex-start; margin-top: auto; color: var(--muted); }
.chat-area { min-width: 0; min-height: 0; display: grid; grid-template-rows: minmax(0,1fr) auto; overflow: hidden; }
.chat-scroll { min-height: 0; overflow-y: auto; display: grid; align-content: start; gap: 18px; padding: 24px; overscroll-behavior: contain; }
.chat-message { display: grid; grid-template-columns: 34px minmax(0,1fr); gap: 10px; max-width: 820px; }
.chat-message.user { justify-self: end; grid-template-columns: minmax(0,1fr) 34px; }
.chat-message.user .chat-avatar { grid-column: 2; grid-row: 1; background: var(--red); }
.chat-message.user > div { grid-column: 1; grid-row: 1; background: var(--red-soft); }
.chat-avatar { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 50%; background: var(--green); color: #fff; }
.chat-message > div { padding: 13px 15px; border-radius: 8px; background: var(--surface-muted); }
.chat-message p { margin: 0; line-height: 1.8; white-space: pre-wrap; }
.agent-stream-status { margin-top: 8px; color: var(--muted); font-size: 12px; }
.agent-tool-events { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 7px; color: var(--muted); font-size: 11px; }
.agent-tool-events span { padding: 2px 6px; border: 1px solid var(--line); border-radius: 4px; background: #fff; }
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
.chat-composer { display: grid; grid-template-columns: minmax(0,1fr) 44px; gap: 9px; padding: 14px; border-top: 1px solid var(--line); background: #fff; }
.chat-composer textarea { min-height: 48px; max-height: 120px; resize: none; }
.send-button { width: 44px; min-height: 44px; padding: 0; align-self: end; }
.stop-button { min-height: 44px; padding: 0 10px; align-self: end; color: var(--red); }
.typing { display: flex; gap: 5px; padding-left: 44px; }
.typing span { width: 7px; height: 7px; border-radius: 50%; background: #8ca094; animation: pulse 900ms infinite alternate; }
.typing span:nth-child(2) { animation-delay: 150ms; }.typing span:nth-child(3) { animation-delay: 300ms; }
@keyframes pulse { to { opacity: .3; transform: translateY(-3px); } }
@media (max-width: 900px) {
  .assistant-layout { height: calc(100svh - 154px); min-height: 520px; grid-template-columns: 1fr; }
  .assistant-side { display: none; }
  .chat-scroll { padding: 16px 12px; }
  .chat-message { max-width: 92%; }
}
</style>
