<script setup>
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { Bot, MessageCircleQuestion, Send, Sparkles, Trash2, UserRound } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import LoadingBlock from "@/components/LoadingBlock.vue";
import { api } from "@/services/api";
import { useSchoolStore } from "@/stores/school";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const schoolStore = useSchoolStore();
const question = ref("");
const loading = ref(false);
const error = ref("");
const chatScroll = ref(null);
const messages = ref(loadMessages());
const threadId = ref(loadThreadId());

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
  if (!messages.value.length) {
    messages.value.push({ role: "assistant", answer: `你好，我可以结合${schoolStore.school?.schoolName || "本校"}的周边资源，协助你进行教学讲解和活动设计。`, citations: [] });
  }
});

watch(messages, (value) => sessionStorage.setItem(storageKey(), JSON.stringify(value)), { deep: true });
watch(threadId, (value) => {
  if (value) sessionStorage.setItem(threadStorageKey(), value);
  else sessionStorage.removeItem(threadStorageKey());
});

function storageKey() {
  return `school-portal-assistant-session:${auth.user?.schoolId || "unknown"}`;
}

function loadMessages() {
  try { return JSON.parse(sessionStorage.getItem(storageKey()) || "[]"); } catch { return []; }
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

async function ask(text = question.value) {
  const clean = text.trim();
  if (!clean || loading.value) return;
  question.value = "";
  await requestAssistant(clean);
}

async function requestAssistant(userText) {
  error.value = "";
  messages.value.push({ role: "user", text: userText });
  loading.value = true;
  await scrollToBottom();
  try {
    const payload = {
      question: userText,
      scopeType: "SCHOOL",
      scopeId: schoolStore.school?.schoolId || auth.user?.schoolId || null
    };
    if (threadId.value) payload.threadId = threadId.value;
    const result = await api.post("/api/ai/qa/ask", payload);
    if (result.threadId) threadId.value = result.threadId;
    messages.value.push({
      role: "assistant", answer: result.answer || "服务未返回回答。",
      relatedResources: result.relatedResources || [], citations: result.citations || [],
      followUpQuestions: result.followUpQuestions || []
    });
  } catch (requestError) {
    const resourceCount = schoolStore.resources.length;
    messages.value.push({
      role: "assistant",
      answer: `${schoolStore.school?.schoolName || "当前学校"}现有 ${resourceCount} 个已关联周边资源。围绕“${userText}”，建议优先选择距离近、可达性高的资源，并按课堂导入、现场观察、实践反思三个阶段组织活动。`,
      citations: ["当前为本地兜底回答，智能问答服务恢复后可获得更完整的引用结果。"]
    });
    error.value = requestError.message;
  } finally {
    loading.value = false;
    await scrollToBottom();
  }
}

async function scrollToBottom() {
  await nextTick();
  if (chatScroll.value) chatScroll.value.scrollTop = chatScroll.value.scrollHeight;
}

function clearChat() {
  messages.value = [];
  threadId.value = "";
  sessionStorage.removeItem(storageKey());
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
          <button class="primary-button send-button" type="submit" :disabled="loading || !question.trim()" aria-label="发送问题"><Send :size="19" /></button>
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
.chat-area { min-width: 0; display: grid; grid-template-rows: minmax(0,1fr) auto; }
.chat-scroll { overflow-y: auto; display: grid; align-content: start; gap: 18px; padding: 24px; }
.chat-message { display: grid; grid-template-columns: 34px minmax(0,1fr); gap: 10px; max-width: 820px; }
.chat-message.user { justify-self: end; grid-template-columns: minmax(0,1fr) 34px; }
.chat-message.user .chat-avatar { grid-column: 2; grid-row: 1; background: var(--red); }
.chat-message.user > div { grid-column: 1; grid-row: 1; background: var(--red-soft); }
.chat-avatar { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 50%; background: var(--green); color: #fff; }
.chat-message > div { padding: 13px 15px; border-radius: 8px; background: var(--surface-muted); }
.chat-message p { margin: 0; line-height: 1.8; white-space: pre-wrap; }
.related { margin-top: 10px !important; color: var(--muted); font-size: 13px; }
.chat-citations { display: grid; gap: 5px; margin-top: 12px; padding-top: 10px; border-top: 1px solid #d3dbd5; color: var(--muted); font-size: 12px; }
.follow-ups { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 11px; }
.follow-ups button { min-height: 30px; padding: 0 9px; border: 1px solid #bdd1c3; border-radius: 4px; background: #fff; color: var(--green); font-size: 12px; }
.chat-composer { display: grid; grid-template-columns: minmax(0,1fr) 44px; gap: 9px; padding: 14px; border-top: 1px solid var(--line); background: #fff; }
.chat-composer textarea { min-height: 48px; max-height: 120px; resize: none; }
.send-button { width: 44px; min-height: 44px; padding: 0; align-self: end; }
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
