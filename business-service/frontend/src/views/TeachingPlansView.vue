<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { BookOpenCheck, FilePlus2, Save, Sparkles, Square, Star } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import LoadingBlock from "@/components/LoadingBlock.vue";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";
import { useSchoolStore } from "@/stores/school";

const auth = useAuthStore();
const schoolStore = useSchoolStore();
const form = reactive({ grade: "四年级", theme: "敬老志愿服务", activityType: "VOLUNTEER_SERVICE", durationMinutes: 120, practiceRequired: true });
const generated = ref(null);
const draftPlan = ref(null);
const plans = ref([]);
const generationGroups = reactive({
  pending: { records: [], total: 0 },
  submitted: { records: [], total: 0 }
});
const activeFeedbackTab = ref("pending");
const generations = computed(() => generationGroups[activeFeedbackTab.value].records);
const generationTotal = computed(() => generationGroups[activeFeedbackTab.value].total);
const loading = ref(false);
const saving = ref(false);
const historyLoading = ref(false);
const generationHistoryLoading = ref(false);
const feedbackSavingId = ref(null);
const currentFeedback = reactive({ adopted: null, rating: 0, reasonCodes: [], teacherNote: "" });
const historyFeedback = reactive({});
const notice = reactive({ tone: "", text: "" });
const streamStage = ref("");
const activeAbortController = ref(null);
const threadId = ref("");
const models = ref([]);
const structuredModels = computed(() => models.value.filter((item) => item.supportsJsonObject !== false));
const selectedModelId = ref("");
const effectiveModel = ref("");
const modelStatusVisible = ref(false);
const feedbackReasons = [
  ["GRADE_MISMATCH", "年级不匹配"], ["THEME_DEVIATION", "偏离主题"],
  ["RESOURCE_MISMATCH", "资源不匹配"], ["HARD_TO_IMPLEMENT", "难以实施"],
  ["DURATION_UNREASONABLE", "时长不合理"], ["SAFETY_RISK", "存在安全风险"],
  ["CONTENT_INCOMPLETE", "内容不完整"], ["UNCLEAR_EXPRESSION", "表达不清"],
  ["OTHER", "其他"]
];
const feedbackReasonLabels = Object.fromEntries(feedbackReasons);

const visiblePlan = computed(() => loading.value ? draftPlan.value : generated.value);
const sections = computed(() => visiblePlan.value ? [
  ["教学目标", visiblePlan.value.objectives], ["资源依据", visiblePlan.value.resourceBasis], ["活动流程", visiblePlan.value.activityFlow],
  ["课前准备", visiblePlan.value.preparation], ["现场任务", visiblePlan.value.fieldTasks], ["安全提示", visiblePlan.value.safetyNotes],
  ["课后反思", visiblePlan.value.reflection], ["评价方式", visiblePlan.value.evaluation]
].filter(([, items]) => Array.isArray(items) && items.length) : []);

onMounted(async () => {
  threadId.value = sessionStorage.getItem(threadStorageKey()) || "";
  await Promise.all([schoolStore.load(), loadModels()]);
  await Promise.all([loadPlans(), loadGenerationHistory()]);
  const theme = schoolStore.resources.find((item) => item.educationThemeSummary)?.educationThemeSummary;
  if (theme) form.theme = theme.slice(0, 40);
});

async function loadModels() {
  try {
    models.value = await api.get("/api/ai/models");
  } catch {
    models.value = [];
  }
}

async function loadGenerationHistory() {
  if (auth.user?.roleCode !== "teacher") return;
  generationHistoryLoading.value = true;
  try {
    const [pending, submitted] = await Promise.all([
      api.get("/api/ai/teaching-plans/generations/mine?feedbackStatus=pending&pageNum=1&pageSize=20"),
      api.get("/api/ai/teaching-plans/generations/mine?feedbackStatus=submitted&pageNum=1&pageSize=20")
    ]);
    generationGroups.pending = { records: pending?.records || [], total: pending?.total || pending?.records?.length || 0 };
    generationGroups.submitted = { records: submitted?.records || [], total: submitted?.total || submitted?.records?.length || 0 };
    generationGroups.pending.records.forEach((item) => {
      historyFeedback[item.generationId] = {
        adopted: null,
        rating: 0,
        reasonCodes: [],
        teacherNote: ""
      };
    });
  } catch (error) {
    notice.tone = "error"; notice.text = error.message || "生成记录加载失败。";
  } finally {
    generationHistoryLoading.value = false;
  }
}

function threadStorageKey() {
  return `school-portal-teaching-plan-thread:${auth.user?.schoolId || "unknown"}`;
}

function mergePlanPatch(patch) {
  if (!patch || typeof patch !== "object" || Array.isArray(patch)) return;
  draftPlan.value = { ...(draftPlan.value || {}), ...patch };
}

async function loadPlans() {
  historyLoading.value = true;
  try {
    const result = await api.get("/api/ai/teaching-plans/mine");
    plans.value = result?.records || [];
  } catch (error) {
    notice.tone = "error"; notice.text = error.message;
  } finally {
    historyLoading.value = false;
  }
}

async function generate() {
  notice.text = "";
  if (!form.grade.trim() || !form.theme.trim()) {
    notice.tone = "error"; notice.text = "请填写年级和教学主题。"; return;
  }
  loading.value = true;
  generated.value = null;
  draftPlan.value = null;
  effectiveModel.value = "";
  modelStatusVisible.value = false;
  streamStage.value = "正在准备教学依据";
  const abortController = new AbortController();
  activeAbortController.value = abortController;
  try {
    const request = {
      schoolId: auth.user.schoolId, grade: form.grade.trim(), theme: form.theme.trim(), activityType: form.activityType,
      durationMinutes: Number(form.durationMinutes), practiceRequired: form.practiceRequired,
      ...(threadId.value ? { threadId: threadId.value } : {})
    };
    if (selectedModelId.value) request.modelId = selectedModelId.value;
    let finalReceived = false;
    await api.stream("/api/ai/teaching-plans/generate/stream", request, {
      signal: abortController.signal,
      onEvent(eventName, data) {
        if (eventName === "stage") {
          streamStage.value = data.stage === "retrieval" ? "正在检索教学依据" : "正在生成教学方案";
        } else if (eventName === "plan.patch") {
          mergePlanPatch(data.patch);
          streamStage.value = "正在生成教学方案";
        } else if (eventName === "response.reset") {
          generated.value = null;
          draftPlan.value = null;
          streamStage.value = "正在切换备用生成方式";
        } else if (eventName === "fallback" || eventName === "model.failed") {
          streamStage.value = "正在切换备用生成方式";
        } else if (eventName === "model.completed") {
          effectiveModel.value = data.model ? `${data.provider || "LLM"} / ${data.model}` : "";
          modelStatusVisible.value = Boolean(effectiveModel.value);
        } else if (eventName === "run.started" || eventName === "model.started") {
          streamStage.value = "正在生成教学方案";
        } else if (eventName === "final") {
          const finalPlan = data.response?.teachingPlan || data.response || data.teachingPlan || data;
          generated.value = finalPlan;
          currentFeedback.adopted = null;
          currentFeedback.rating = 0;
          currentFeedback.reasonCodes = [];
          currentFeedback.teacherNote = "";
          mergePlanPatch(finalPlan);
          if (data.threadId || data.response?.threadId || generated.value.threadId) {
            threadId.value = data.threadId || data.response?.threadId || generated.value.threadId;
            sessionStorage.setItem(threadStorageKey(), threadId.value);
          }
          finalReceived = true;
          modelStatusVisible.value = false;
        } else if (eventName === "error") {
          modelStatusVisible.value = false;
          throw new Error("教学方案流式生成失败");
        }
      }
    });
    if (!finalReceived) throw new Error("流式服务未返回最终方案");
    const generationStatus = String(generated.value?.generationStatus || "").toLowerCase();
    const completed = generationStatus === "completed" || generationStatus === "success";
    notice.tone = completed ? "success" : "info";
    notice.text = completed ? "教学方案已生成。" : "已生成基础教学方案，部分内容可能需要人工补充";
  } catch (error) {
    modelStatusVisible.value = false;
    if (error?.name === "AbortError") {
      notice.tone = "info"; notice.text = "已停止生成。"; return;
    }
    notice.tone = "error"; notice.text = error.message || "教学方案生成失败。";
  } finally {
    modelStatusVisible.value = false;
    loading.value = false;
    activeAbortController.value = null;
  }
}

function stopGeneration() {
  modelStatusVisible.value = false;
  activeAbortController.value?.abort();
}

async function saveDraft() {
  if (!generated.value) return;
  saving.value = true;
  try {
    const savedPlan = await api.post("/api/ai/teaching-plans/save-draft", {
      generationId: generated.value.generationId || null,
      schoolId: auth.user.schoolId, resourceId: schoolStore.resources[0]?.resourceId || null,
      theme: generated.value.theme, activityType: generated.value.activityType || form.activityType,
      grade: generated.value.grade, durationMinutes: generated.value.durationMinutes,
      objectives: generated.value.objectives || [], activityFlow: generated.value.activityFlow || [],
      preparation: generated.value.preparation || [], safetyNotes: generated.value.safetyNotes || [],
      reflection: generated.value.reflection || [], evaluation: generated.value.evaluation || []
    });
    generated.value.savedPlanId = savedPlan?.planId || generated.value.savedPlanId;
    notice.tone = "success"; notice.text = "草稿已保存到学校方案库。";
    await Promise.all([loadPlans(), loadGenerationHistory()]);
  } catch (error) {
    notice.tone = "error"; notice.text = error.message || "保存失败。";
  } finally {
    saving.value = false;
  }
}

async function submitFeedback(generationId, draft, current = false) {
  if (draft.adopted === null || draft.adopted === undefined) {
    notice.tone = "error"; notice.text = "请选择采纳或不采纳。"; return;
  }
  if (!Number.isInteger(Number(draft.rating)) || Number(draft.rating) < 1 || Number(draft.rating) > 5) {
    notice.tone = "error"; notice.text = "请选择 1–5 分方案质量评分。"; return;
  }
  if (draft.reasonCodes?.includes("OTHER") && !draft.teacherNote?.trim()) {
    notice.tone = "error"; notice.text = "选择“其他”原因后，请填写教师备注。"; return;
  }
  feedbackSavingId.value = generationId;
  try {
    const feedback = await api.put(`/api/ai/teaching-plans/generations/${generationId}/feedback`, {
      adopted: Boolean(draft.adopted),
      rating: Number(draft.rating),
      reasonCodes: [...(draft.reasonCodes || [])],
      teacherNote: draft.teacherNote?.trim() || null
    });
    if (current && generated.value) {
      generated.value.feedback = feedback;
      generated.value.savedPlanId = feedback.savedPlanId || generated.value.savedPlanId;
    }
    notice.tone = "success";
    notice.text = feedback.adopted ? "反馈已提交，方案草稿已保存。" : "反馈已提交，感谢你的评价。";
    await Promise.all([loadGenerationHistory(), feedback.adopted ? loadPlans() : Promise.resolve()]);
  } catch (error) {
    notice.tone = "error"; notice.text = error.message || "反馈提交失败。";
  } finally {
    feedbackSavingId.value = null;
  }
}

function isNegativeFeedback(draft) {
  return draft?.adopted === false || (Number(draft?.rating) >= 1 && Number(draft?.rating) <= 2);
}

function selectAdoption(draft, adopted) {
  draft.adopted = adopted;
  clearReasonsIfPositive(draft);
}

function selectRating(draft, rating) {
  draft.rating = rating;
  clearReasonsIfPositive(draft);
}

function clearReasonsIfPositive(draft) {
  if (draft.adopted === true && Number(draft.rating) >= 3) draft.reasonCodes = [];
}

function toggleReason(draft, code) {
  const selected = new Set(draft.reasonCodes || []);
  selected.has(code) ? selected.delete(code) : selected.add(code);
  draft.reasonCodes = feedbackReasons.map(([reasonCode]) => reasonCode).filter((reasonCode) => selected.has(reasonCode));
}

function reasonText(code) {
  return feedbackReasonLabels[code] || code;
}

function historySections(item) {
  const plan = item?.plan || {};
  return [
    ["教学目标", plan.objectives], ["资源依据", plan.resourceBasis], ["活动流程", plan.activityFlow],
    ["课前准备", plan.preparation], ["现场任务", plan.fieldTasks], ["安全提示", plan.safetyNotes],
    ["课后反思", plan.reflection], ["评价方式", plan.evaluation]
  ].filter(([, items]) => Array.isArray(items) && items.length);
}

function feedbackStatusText(item) {
  if (!item.feedback) return "待反馈";
  return item.feedback.adopted ? `已采纳 · ${item.feedback.rating} 分` : `未采纳 · ${item.feedback.rating} 分`;
}

function statusLabel(status) {
  return { DRAFT: "草稿", PENDING: "待审核", APPROVED: "已通过", REJECTED: "已驳回", draft: "草稿", pending: "待审核", approved: "已通过", rejected: "已驳回" }[status] || status || "草稿";
}
</script>

<template>
  <AppShell title="教学方案" subtitle="结合本校周边资源生成可落地的课堂与实践活动方案">
    <div class="plan-layout">
      <section class="page-panel plan-form-panel">
        <div class="panel-header"><div><h2>方案设置</h2><p>{{ schoolStore.school?.schoolName }}</p></div><FilePlus2 :size="21" /></div>
        <form class="panel-body form-stack" @submit.prevent="generate">
          <label>适用年级<input v-model="form.grade" placeholder="例如：四年级" /></label>
          <label>教学主题<input v-model="form.theme" placeholder="例如：敬老志愿服务" /></label>
          <label>活动类型<select v-model="form.activityType"><option value="VOLUNTEER_SERVICE">志愿服务</option><option value="FIELD_TRIP">实地研学</option><option value="CLASSROOM">课堂教学</option><option value="LABOR_PRACTICE">劳动实践</option><option value="SCHOOL_BASED_COURSE">校本课程</option></select></label>
          <label>活动时长（分钟）<input v-model.number="form.durationMinutes" type="number" min="20" step="10" /></label>
          <label>生成模型<select v-model="selectedModelId" :disabled="loading"><option value="">系统默认</option><option v-for="item in structuredModels" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.provider }}</option></select></label>
          <label class="check-field"><input v-model="form.practiceRequired" type="checkbox" /><span>包含线下实践活动</span></label>
          <button v-if="!loading" class="primary-button full-button" type="submit"><Sparkles :size="18" />生成教学方案</button>
          <button v-else class="secondary-button full-button" type="button" @click="stopGeneration"><Square :size="16" />停止生成</button>
        </form>
      </section>

      <section class="page-panel result-panel">
        <div class="panel-header"><div><h2>生成结果</h2><p>内容可保存为草稿，由管理员继续审核完善。</p></div><button class="secondary-button" type="button" :disabled="!generated || saving" @click="saveDraft"><Save :size="17" />{{ saving ? "保存中" : "保存草稿" }}</button></div>
        <div class="panel-body result-scroll">
          <InlineNotice v-if="notice.text" :tone="notice.tone">{{ notice.text }}</InlineNotice>
          <div v-if="loading || generated" :class="{ 'streaming-plan': loading }" aria-live="polite">
            <div v-if="loading" class="streaming-status"><span class="streaming-dot"></span>{{ streamStage }}</div>
            <div v-if="modelStatusVisible && effectiveModel" class="streaming-status">实际模型：{{ effectiveModel }}</div>
            <div v-if="visiblePlan" class="generated-plan">
              <header><div><span class="badge badge-red">{{ visiblePlan.grade }}</span><span class="badge">{{ visiblePlan.durationMinutes }} 分钟</span></div><h2>{{ visiblePlan.theme }}</h2></header>
              <section v-for="([title, items]) in sections" :key="title"><h3>{{ title }}</h3><ul><li v-for="item in items" :key="item">{{ item }}</li></ul></section>
              <section v-if="visiblePlan.citations?.length"><h3>引用来源</h3><div class="citation-list"><article v-for="item in visiblePlan.citations" :key="item.citationId"><strong>{{ item.title || item.citationId }}</strong><p>{{ item.excerpt }}</p></article></div></section>
              <section v-if="auth.user?.roleCode === 'teacher' && visiblePlan.generationId" class="feedback-card">
                <h3>教师反馈</h3>
                <template v-if="!generated?.feedback">
                  <p>请根据方案是否可实际采用完成评价。采纳后会自动保存一份学校方案草稿。</p>
                  <div class="feedback-choice" role="group" aria-label="是否采纳">
                    <button type="button" :class="{ active: currentFeedback.adopted === true }" @click="selectAdoption(currentFeedback, true)">采纳</button>
                    <button type="button" :class="{ active: currentFeedback.adopted === false }" @click="selectAdoption(currentFeedback, false)">不采纳</button>
                  </div>
                  <div class="rating-field">
                    <span class="rating-label">方案质量评分</span>
                    <div class="star-rating" role="radiogroup" aria-label="方案质量评分">
                      <button v-for="score in 5" :key="score" type="button" :class="{ active: score <= currentFeedback.rating }" role="radio" :aria-checked="currentFeedback.rating === score" :aria-label="`${score} 分`" :title="`${score} 分`" @click="selectRating(currentFeedback, score)"><Star :size="30" :stroke-width="1.8" /></button>
                      <span class="star-rating-value" aria-live="polite">{{ currentFeedback.rating ? `${currentFeedback.rating} 分` : "请选择评分" }}</span>
                    </div>
                  </div>
                  <div v-if="isNegativeFeedback(currentFeedback)" class="reason-field">
                    <span class="rating-label">问题原因（可多选）</span>
                    <div class="reason-options"><button v-for="([code, label]) in feedbackReasons" :key="code" type="button" :class="{ active: currentFeedback.reasonCodes.includes(code) }" @click="toggleReason(currentFeedback, code)">{{ label }}</button></div>
                  </div>
                  <label>教师备注（可选）<textarea v-model="currentFeedback.teacherNote" maxlength="2000" placeholder="说明方案可用之处或需要补充的数据"></textarea></label>
                  <button class="primary-button" type="button" :disabled="feedbackSavingId === visiblePlan.generationId" @click="submitFeedback(visiblePlan.generationId, currentFeedback, true)">{{ feedbackSavingId === visiblePlan.generationId ? "提交中" : "提交反馈" }}</button>
                </template>
                <div v-else class="feedback-summary">
                  <strong>{{ generated.feedback.adopted ? "已采纳" : "未采纳" }} · {{ generated.feedback.rating }} 分</strong>
                  <div class="readonly-stars" :aria-label="`方案质量评分 ${generated.feedback.rating} 分`"><Star v-for="score in 5" :key="score" :class="{ active: score <= generated.feedback.rating }" :size="24" /></div>
                  <div class="reason-summary"><span v-for="code in generated.feedback.reasonCodes || []" :key="code" class="reason-chip">{{ reasonText(code) }}</span><span v-if="isNegativeFeedback(generated.feedback) && !generated.feedback.reasonCodes?.length" class="muted-text">未记录原因</span></div>
                  <p v-if="generated.feedback.teacherNote">教师备注：{{ generated.feedback.teacherNote }}</p>
                </div>
              </section>
            </div>
            <div v-else-if="loading" class="streaming-copy">正在等待结构化内容<span class="streaming-caret"></span></div>
          </div>
          <div v-else class="empty-state"><BookOpenCheck :size="40" /><span>填写左侧参数后生成教学方案</span></div>
        </div>
      </section>
    </div>

    <section v-if="auth.user?.roleCode === 'teacher'" class="page-panel generation-history">
      <div class="panel-header"><div><h2>我的生成记录与反馈</h2><p>待反馈可提交一次评价，已反馈内容仅供查看。</p></div><span class="badge">{{ generationTotal }} 条</span></div>
      <div class="feedback-tabs" role="tablist" aria-label="反馈状态">
        <button type="button" role="tab" :aria-selected="activeFeedbackTab === 'pending'" :class="{ active: activeFeedbackTab === 'pending' }" @click="activeFeedbackTab = 'pending'">待反馈 <span>{{ generationGroups.pending.total }}</span></button>
        <button type="button" role="tab" :aria-selected="activeFeedbackTab === 'submitted'" :class="{ active: activeFeedbackTab === 'submitted' }" @click="activeFeedbackTab = 'submitted'">已反馈 <span>{{ generationGroups.submitted.total }}</span></button>
      </div>
      <LoadingBlock v-if="generationHistoryLoading" />
      <div v-else-if="generations.length" class="generation-list">
        <article v-for="item in generations" :key="item.generationId" class="generation-card">
          <header><div><strong>{{ item.theme }}</strong><span class="badge">{{ feedbackStatusText(item) }}</span></div><small>{{ item.grade || '-' }} · {{ item.durationMinutes || '-' }} 分钟 · {{ item.createdAt?.replace('T', ' ') }}</small></header>
          <details><summary>查看生成方案</summary><section v-for="([title, items]) in historySections(item)" :key="title"><h4>{{ title }}</h4><ul><li v-for="text in items" :key="text">{{ text }}</li></ul></section></details>
          <div v-if="activeFeedbackTab === 'pending'" class="history-feedback">
            <div class="feedback-choice" role="group" :aria-label="`${item.theme}是否采纳`">
              <button type="button" :class="{ active: historyFeedback[item.generationId]?.adopted === true }" @click="selectAdoption(historyFeedback[item.generationId], true)">采纳</button>
              <button type="button" :class="{ active: historyFeedback[item.generationId]?.adopted === false }" @click="selectAdoption(historyFeedback[item.generationId], false)">不采纳</button>
            </div>
            <div class="star-rating" role="radiogroup" :aria-label="`${item.theme}方案质量评分`">
              <button
                v-for="score in 5"
                :key="score"
                type="button"
                :class="{ active: score <= historyFeedback[item.generationId].rating }"
                role="radio"
                :aria-checked="historyFeedback[item.generationId].rating === score"
                :aria-label="`${score} 分`"
                :title="`${score} 分`"
                @click="selectRating(historyFeedback[item.generationId], score)"
              ><Star :size="26" :stroke-width="1.8" /></button>
              <span class="star-rating-value" aria-live="polite">{{ historyFeedback[item.generationId].rating ? `${historyFeedback[item.generationId].rating} 分` : "请选择评分" }}</span>
            </div>
            <div v-if="isNegativeFeedback(historyFeedback[item.generationId])" class="reason-field history-reasons">
              <span class="rating-label">问题原因（可多选）</span>
              <div class="reason-options"><button v-for="([code, label]) in feedbackReasons" :key="code" type="button" :class="{ active: historyFeedback[item.generationId].reasonCodes.includes(code) }" @click="toggleReason(historyFeedback[item.generationId], code)">{{ label }}</button></div>
            </div>
            <textarea v-model="historyFeedback[item.generationId].teacherNote" maxlength="2000" placeholder="教师备注（可选）"></textarea>
            <button class="secondary-button" type="button" :disabled="feedbackSavingId === item.generationId" @click="submitFeedback(item.generationId, historyFeedback[item.generationId])">{{ feedbackSavingId === item.generationId ? "保存中" : "保存反馈" }}</button>
          </div>
          <div v-else class="feedback-summary submitted-summary">
            <strong>{{ item.feedback.adopted ? "已采纳" : "未采纳" }} · {{ item.feedback.rating }} 分</strong>
            <div class="readonly-stars" :aria-label="`方案质量评分 ${item.feedback.rating} 分`"><Star v-for="score in 5" :key="score" :class="{ active: score <= item.feedback.rating }" :size="24" /></div>
            <div class="reason-summary"><span v-for="code in item.feedback.reasonCodes || []" :key="code" class="reason-chip">{{ reasonText(code) }}</span><span v-if="isNegativeFeedback(item.feedback) && !item.feedback.reasonCodes?.length" class="muted-text">未记录原因</span></div>
            <p v-if="item.feedback.teacherNote">教师备注：{{ item.feedback.teacherNote }}</p>
          </div>
        </article>
      </div>
      <div v-else class="empty-state">{{ activeFeedbackTab === 'pending' ? '暂无待反馈的教学方案' : '暂无已反馈的教学方案' }}</div>
    </section>

    <section class="page-panel plan-library">
      <div class="panel-header"><div><h2>学校方案库</h2><p>包含已保存草稿与经过审核的教学方案。</p></div><span class="badge">{{ plans.length }} 条</span></div>
      <LoadingBlock v-if="historyLoading" />
      <div v-else-if="plans.length" class="plan-table-wrap"><table><thead><tr><th>主题</th><th>年级</th><th>类型</th><th>时长</th><th>状态</th></tr></thead><tbody><tr v-for="plan in plans" :key="plan.planId"><td><strong>{{ plan.theme }}</strong></td><td>{{ plan.suitableGrade || "-" }}</td><td>{{ plan.activityType || "-" }}</td><td>{{ plan.durationMinutes ? `${plan.durationMinutes} 分钟` : "-" }}</td><td><span class="badge" :class="plan.reviewStatus?.toLowerCase() === 'approved' ? 'badge-green' : ''">{{ statusLabel(plan.reviewStatus) }}</span></td></tr></tbody></table></div>
      <div v-else class="empty-state">尚未保存教学方案</div>
    </section>
  </AppShell>
</template>

<style scoped>
.plan-layout { display: grid; grid-template-columns: minmax(280px,360px) minmax(0,1fr); gap: 16px; }
.panel-header > svg { color: var(--green); }
.check-field { display: flex; align-items: center; gap: 9px; }
.check-field input { width: 17px; min-height: 17px; }
.result-panel { min-height: 650px; }
.result-scroll { max-height: calc(100vh - 170px); overflow-y: auto; }
.streaming-plan { min-height: 460px; padding: 18px 0; }
.streaming-status { display: flex; align-items: center; gap: 8px; color: var(--green); font-size: 13px; font-weight: 700; }
.streaming-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--red); animation: pulse 1.1s ease-in-out infinite; }
.streaming-copy { margin-top: 18px; white-space: pre-wrap; color: var(--text); line-height: 1.85; font-size: 15px; }
.streaming-caret { display: inline-block; width: 2px; height: 1.1em; margin-left: 3px; vertical-align: -2px; background: var(--red); animation: blink .8s steps(1) infinite; }
@keyframes pulse { 50% { opacity: .35; transform: scale(.8); } }
@keyframes blink { 50% { opacity: 0; } }
.generated-plan { margin-top: 18px; }
.generated-plan header { padding-bottom: 18px; border-bottom: 1px solid var(--line); }
.generated-plan header > div { display: flex; gap: 7px; }
.generated-plan header h2 { margin: 12px 0 0; font-size: 24px; }
.generated-plan section { padding: 18px 0; border-bottom: 1px solid var(--line); }
.generated-plan section h3 { margin-bottom: 10px; font-size: 15px; color: var(--green); }
.generated-plan ul { display: grid; gap: 8px; margin: 0; padding-left: 20px; line-height: 1.7; }
.citation-list { display: grid; gap: 8px; }
.citation-list article { padding: 12px; border-left: 3px solid var(--red); background: #f8f9f7; }
.citation-list p { margin: 6px 0 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.feedback-card { display: grid; gap: 12px; border-bottom: 0 !important; }
.feedback-card > p { margin: 0; color: var(--muted); font-size: 13px; }
.feedback-card label { display: grid; gap: 6px; font-size: 13px; font-weight: 700; }
.feedback-card textarea { min-height: 88px; resize: vertical; }
.rating-field { display: grid; gap: 6px; }
.rating-label { font-size: 13px; font-weight: 700; }
.star-rating { display: flex; align-items: center; gap: 3px; min-width: 0; }
.star-rating button { display: inline-grid; place-items: center; flex: 0 0 auto; width: 34px; height: 34px; padding: 0; border: 0; border-radius: 7px; background: transparent; color: #a9afa9; cursor: pointer; transition: color .15s ease, background .15s ease, transform .15s ease; }
.star-rating button:hover { color: #d89414; background: #fff7e5; transform: translateY(-1px); }
.star-rating button.active { color: #d89414; }
.star-rating button.active svg { fill: currentColor; }
.star-rating button:focus-visible { outline: 2px solid var(--red); outline-offset: 2px; }
.star-rating-value { margin-left: 7px; color: var(--muted); font-size: 13px; white-space: nowrap; }
.feedback-choice { display: flex; gap: 8px; }
.feedback-choice button { padding: 8px 16px; border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--text); cursor: pointer; }
.feedback-choice button.active { border-color: var(--green); background: #eaf4ee; color: var(--green); font-weight: 700; }
.feedback-choice button:disabled { cursor: not-allowed; opacity: .5; }
.reason-field { display: grid; gap: 7px; }
.reason-options { display: flex; flex-wrap: wrap; gap: 8px; }
.reason-options button { padding: 7px 12px; border: 1px solid var(--line); border-radius: 999px; background: #fff; color: var(--text); cursor: pointer; }
.reason-options button.active { border-color: var(--red); background: #fcedea; color: var(--red); font-weight: 700; }
.feedback-summary { display: grid; gap: 9px; padding: 12px; border-radius: 10px; background: #f7f8f6; }
.feedback-summary p { margin: 0; color: var(--muted); white-space: pre-wrap; }
.readonly-stars { display: flex; gap: 3px; color: #a9afa9; }
.readonly-stars svg.active { color: #d89414; fill: currentColor; }
.reason-summary { display: flex; flex-wrap: wrap; gap: 7px; }
.reason-chip { padding: 5px 9px; border-radius: 999px; background: #fcedea; color: var(--red); font-size: 12px; }
.muted-text { color: var(--muted); font-size: 13px; }
.generation-history { margin-top: 16px; }
.feedback-tabs { display: flex; gap: 8px; padding: 14px 16px 0; }
.feedback-tabs button { padding: 8px 14px; border: 1px solid var(--line); border-radius: 9px; background: #fff; cursor: pointer; }
.feedback-tabs button.active { border-color: var(--green); background: #eaf4ee; color: var(--green); font-weight: 700; }
.feedback-tabs span { margin-left: 4px; }
.generation-list { display: grid; gap: 12px; padding: 16px; }
.generation-card { display: grid; gap: 12px; padding: 16px; border: 1px solid var(--line); border-radius: 12px; background: #fff; }
.generation-card header > div { display: flex; justify-content: space-between; gap: 12px; }
.generation-card header small { display: block; margin-top: 6px; color: var(--muted); }
.generation-card details section { padding: 10px 0 0; }
.generation-card details h4 { margin: 0 0 6px; color: var(--green); }
.generation-card details ul { margin: 0; padding-left: 20px; line-height: 1.65; }
.history-feedback { display: grid; grid-template-columns: auto minmax(230px, auto) minmax(220px, 1fr) auto; gap: 10px; align-items: center; }
.history-reasons { grid-column: 1 / -1; }
.history-feedback textarea { min-height: 64px; resize: vertical; }
.submitted-summary { border: 1px solid var(--line); }
.plan-library { margin-top: 16px; }
.plan-table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; min-width: 680px; }
th, td { padding: 13px 18px; border-bottom: 1px solid var(--line); text-align: left; font-size: 14px; }
th { color: var(--muted); background: #f7f8f6; font-size: 12px; }
@media (max-width: 1080px) { .plan-layout { grid-template-columns: 1fr; } .result-panel { min-height: 520px; } .result-scroll { max-height: none; } .history-feedback { grid-template-columns: 1fr; } }
</style>
