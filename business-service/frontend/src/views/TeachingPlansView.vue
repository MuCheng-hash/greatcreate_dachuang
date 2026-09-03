<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { BookOpenCheck, Copy, Download, FilePlus2, Save, Search, Sparkles, Square, RefreshCw, Star } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import LoadingBlock from "@/components/LoadingBlock.vue";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";
import { useSchoolStore } from "@/stores/school";

const auth = useAuthStore();
const schoolStore = useSchoolStore();
const form = reactive({ grade: "四年级", theme: "敬老志愿服务", objectives: "", activityType: "VOLUNTEER_SERVICE", durationMinutes: 120, practiceRequired: true, resourceIds: [] });
const generated = ref(null);
const draftPlan = ref(null);
const plans = ref([]);
const loading = ref(false);
const saving = ref(false);
const historyLoading = ref(false);
const notice = reactive({ tone: "", text: "" });
const streamStage = ref("");
const activeAbortController = ref(null);
const threadId = ref("");
const models = ref([]);
const structuredModels = computed(() => models.value.filter((item) => item.supportsJsonObject !== false));
const selectedModelId = ref("");
const effectiveModel = ref("");
const modelStatusVisible = ref(false);
const selectedPlanId = ref(null);
const editing = ref(false);
const filters = reactive({ grade: "", theme: "", resourceId: "", createdFrom: "", createdTo: "" });
const pageNum = ref(1);
const pageSize = ref(20);
const totalPlans = ref(0);
const exportingPlanId = ref(null);
const feedbackBusy = ref(false);
const feedbackStatus = ref("pending");
const generationRecords = ref([]);
const feedbackDraft = reactive({ adopted: true, rating: 0, reasonCodes: [], teacherNote: "" });
const feedbackReasons = [
  ["CONTENT_INCOMPLETE", "内容不完整"], ["THEME_DEVIATION", "偏离主题"], ["GRADE_MISMATCH", "年级不适配"],
  ["HARD_TO_IMPLEMENT", "活动难落地"], ["RESOURCE_MISMATCH", "资源不匹配"], ["SAFETY_RISK", "安全需完善"],
  ["DURATION_UNREASONABLE", "时长不合适"], ["UNCLEAR_EXPRESSION", "表达不清"], ["OTHER", "其他"]
];

const visiblePlan = computed(() => loading.value ? draftPlan.value : generated.value);
const sections = computed(() => visiblePlan.value ? [
  ["教学目标", visiblePlan.value.objectives], ["资源依据", visiblePlan.value.resourceBasis], ["活动流程", visiblePlan.value.activityFlow],
  ["课前准备", visiblePlan.value.preparation], ["现场任务", visiblePlan.value.fieldTasks], ["安全提示", visiblePlan.value.safetyNotes],
  ["课后反思", visiblePlan.value.reflection], ["评价方式", visiblePlan.value.evaluation]
].filter(([, items]) => Array.isArray(items) && items.length) : []);

onMounted(async () => {
  threadId.value = sessionStorage.getItem(threadStorageKey()) || "";
  await Promise.all([schoolStore.load(), loadModels()]);
  await Promise.all([loadPlans(), loadGenerationRecords()]);
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

function threadStorageKey() {
  return `school-portal-teaching-plan-thread:${auth.user?.schoolId || "unknown"}`;
}

function mergePlanPatch(patch) {
  if (!patch || typeof patch !== "object" || Array.isArray(patch)) return;
  draftPlan.value = { ...(draftPlan.value || {}), ...patch };
}

function toggleResource(resourceId) {
  const index = form.resourceIds.indexOf(resourceId);
  if (index >= 0) form.resourceIds.splice(index, 1);
  else if (form.resourceIds.length < 20) form.resourceIds.push(resourceId);
}

function resourceSelected(resourceId) { return form.resourceIds.includes(resourceId); }

function resourceName(resourceId) {
  return schoolStore.resources.find(item => item.resource?.resourceId === resourceId || item.resourceId === resourceId)?.resource?.resourceName || "未命名资源";
}

function sectionText(items) { return Array.isArray(items) ? items.join("\n") : ""; }
function updateSection(field, event) {
  if (!generated.value) return;
  generated.value[field] = event.target.value.split("\n").map(value => value.trim()).filter(Boolean);
}

async function loadPlans() {
  historyLoading.value = true;
  try {
    const params = new URLSearchParams({ pageNum: String(pageNum.value), pageSize: String(pageSize.value) });
    Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value); });
    const result = await api.get(`/api/ai/teaching-plans/mine?${params.toString()}`);
    plans.value = result?.records || [];
    totalPlans.value = result?.total || 0;
  } catch (error) {
    notice.tone = "error"; notice.text = error.message;
  } finally {
    historyLoading.value = false;
  }
}

function searchPlans() { pageNum.value = 1; loadPlans(); }
function resetPlanFilters() { Object.keys(filters).forEach(key => { filters[key] = ""; }); pageNum.value = 1; loadPlans(); }
function changePage(next) { if (next < 1 || next > Math.ceil(totalPlans.value / pageSize.value)) return; pageNum.value = next; loadPlans(); }
function formatDate(value) { return value ? String(value).replace("T", " ").slice(0, 16) : "-"; }

async function exportPlan(planId, theme) {
  if (exportingPlanId.value) return;
  exportingPlanId.value = planId;
  try {
    const blob = await api.download(`/api/ai/teaching-plans/mine/${planId}/export`);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a"); anchor.href = url; anchor.download = `教学方案-${(theme || "方案").replace(/[\\/:*?"<>|]/g, "_")}.docx`; anchor.click(); URL.revokeObjectURL(url);
    notice.tone = "success"; notice.text = "方案已导出，状态已更新为已采纳。";
    await loadPlans();
  } catch (error) { notice.tone = "error"; notice.text = error.message || "导出失败。"; }
  finally { exportingPlanId.value = null; }
}

async function loadGenerationRecords() {
  try {
    const result = await api.get(`/api/ai/teaching-plans/generations/mine?feedbackStatus=${feedbackStatus.value}&pageNum=1&pageSize=20`);
    generationRecords.value = result?.records || [];
  } catch {
    generationRecords.value = [];
  }
}

function selectFeedbackStatus(value) { feedbackStatus.value = value; loadGenerationRecords(); }
function resetFeedbackDraft() { feedbackDraft.adopted = true; feedbackDraft.rating = 0; feedbackDraft.reasonCodes = []; feedbackDraft.teacherNote = ""; }
function toggleReason(code) {
  const index = feedbackDraft.reasonCodes.indexOf(code);
  if (index >= 0) feedbackDraft.reasonCodes.splice(index, 1);
  else feedbackDraft.reasonCodes.push(code);
}
function reasonLabel(code) { return feedbackReasons.find(([value]) => value === code)?.[1] || code; }

function openGenerationFeedback(item) {
  generated.value = {
    ...(item.plan || {}), generationId: item.generationId, theme: item.theme || item.plan?.theme,
    grade: item.grade || item.plan?.grade, durationMinutes: item.durationMinutes || item.plan?.durationMinutes,
    feedback: item.feedback || null
  };
  draftPlan.value = null;
  editing.value = false;
  resetFeedbackDraft();
  notice.tone = "info";
  notice.text = "已载入该生成方案，可在预览区填写评分与反馈。";
  window.scrollTo({ top: 0, behavior: "smooth" });
}

async function submitFeedback(plan = generated.value) {
  if (!plan?.generationId || feedbackBusy.value) return;
  if (!feedbackDraft.rating) { notice.tone = "error"; notice.text = "请先选择 1–5 分评分。"; return; }
  if (!feedbackDraft.adopted && feedbackDraft.reasonCodes.includes("OTHER") && !feedbackDraft.teacherNote.trim()) {
    notice.tone = "error"; notice.text = "选择“其他”原因后，请填写教师备注。"; return;
  }
  feedbackBusy.value = true;
  try {
    const feedback = await api.put(`/api/ai/teaching-plans/generations/${plan.generationId}/feedback`, {
      adopted: feedbackDraft.adopted, rating: feedbackDraft.rating,
      reasonCodes: feedbackDraft.adopted ? [] : feedbackDraft.reasonCodes,
      teacherNote: feedbackDraft.teacherNote.trim() || null
    });
    plan.feedback = feedback;
    notice.tone = "success"; notice.text = feedback.adopted ? "已保存评分与反馈，方案已采纳。" : "已保存评分与改进反馈。";
    resetFeedbackDraft();
    await Promise.all([loadGenerationRecords(), loadPlans()]);
  } catch (error) { notice.tone = "error"; notice.text = error.message || "反馈保存失败。"; }
  finally { feedbackBusy.value = false; }
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
      objectives: form.objectives.trim(), resourceIds: [...form.resourceIds], durationMinutes: Number(form.durationMinutes), practiceRequired: form.practiceRequired,
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
          resetFeedbackDraft();
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
    await api.post("/api/ai/teaching-plans/save-draft", {
      schoolId: auth.user.schoolId, resourceId: generated.value.selectedResources?.[0]?.resourceId || form.resourceIds[0] || null,
      resourceIds: generated.value.selectedResources?.map(item => item.resourceId) || [...form.resourceIds],
      theme: generated.value.theme, activityType: generated.value.activityType || form.activityType,
      grade: generated.value.grade, durationMinutes: generated.value.durationMinutes,
      ownerAccountId: auth.user.accountId, planPayload: JSON.stringify(generated.value), generationSource: "ai",
      ...(generated.value.generationId ? { generationId: generated.value.generationId } : {}),
      objectives: generated.value.objectives || [], activityFlow: generated.value.activityFlow || [],
      preparation: generated.value.preparation || [], safetyNotes: generated.value.safetyNotes || [],
      reflection: generated.value.reflection || [], evaluation: generated.value.evaluation || [],
      resourceBasis: generated.value.resourceBasis || [], fieldTasks: generated.value.fieldTasks || [],
      relatedResources: generated.value.relatedResources || [], citations: generated.value.citations || []
    });
    notice.tone = "success"; notice.text = "草稿已保存到我的方案。";
    await loadPlans();
  } catch (error) {
    notice.tone = "error"; notice.text = error.message || "保存失败。";
  } finally {
    saving.value = false;
  }
}

async function loadPlan(planId) {
  try {
    const plan = await api.get(`/api/ai/teaching-plans/mine/${planId}`);
    selectedPlanId.value = plan.planId;
    editing.value = true;
    generated.value = plan.planPayload ? JSON.parse(plan.planPayload) : {
      theme: plan.theme, grade: plan.suitableGrade, activityType: plan.activityType, durationMinutes: plan.durationMinutes,
      objectives: plan.objectiveText?.split("\\n") || [], activityFlow: plan.activityContent?.split("\\n") || [],
      preparation: plan.preparationText?.split("\\n") || [], safetyNotes: plan.safetyText?.split("\\n") || [],
      reflection: plan.expectedOutcome?.split("\\n") || [], evaluation: [], selectedResources: []
    };
    form.grade = generated.value.grade || form.grade; form.theme = generated.value.theme || form.theme;
    form.activityType = generated.value.activityType || form.activityType; form.durationMinutes = generated.value.durationMinutes || form.durationMinutes;
    form.objectives = Array.isArray(generated.value.objectives) ? generated.value.objectives.join("\\n") : (generated.value.objectives || "");
    form.resourceIds = plan.resourceIds || [];
    notice.tone = "info"; notice.text = "已载入方案，可编辑后重新生成或保存。";
  } catch (error) { notice.tone = "error"; notice.text = error.message || "方案加载失败。"; }
}

async function copyPlan(planId) {
  try { await api.post(`/api/ai/teaching-plans/mine/${planId}/copy`); notice.tone = "success"; notice.text = "方案已复制。"; await loadPlans(); }
  catch (error) { notice.tone = "error"; notice.text = error.message || "复制失败。"; }
}

async function updatePlan() {
  if (!selectedPlanId.value || !generated.value) return saveDraft();
  saving.value = true;
  try {
    await api.put(`/api/ai/teaching-plans/mine/${selectedPlanId.value}`, {
      theme: generated.value.theme, suitableGrade: generated.value.grade, activityType: generated.value.activityType,
      durationMinutes: generated.value.durationMinutes, objectiveText: (generated.value.objectives || []).join("\\n"),
      activityContent: (generated.value.activityFlow || []).join("\\n"), preparationText: (generated.value.preparation || []).join("\\n"),
      safetyText: (generated.value.safetyNotes || []).join("\\n"), expectedOutcome: [...(generated.value.reflection || []), ...(generated.value.evaluation || [])].join("\\n"),
      resourceIds: generated.value.selectedResources?.map(item => item.resourceId) || form.resourceIds,
      planPayload: JSON.stringify(generated.value)
    });
    notice.tone = "success"; notice.text = "方案已更新。"; await loadPlans();
  } catch (error) { notice.tone = "error"; notice.text = error.message || "更新失败。"; }
  finally { saving.value = false; }
}

function statusLabel(status) {
  return { DRAFT: "草稿", PENDING: "待审核", APPROVED: "已通过", ADOPTED: "已采纳", REJECTED: "已驳回", draft: "草稿", pending: "待审核", approved: "已通过", adopted: "已采纳", rejected: "已驳回" }[status] || status || "草稿";
}
</script>

<template>
  <AppShell title="教学方案" subtitle="结合本校周边资源生成可落地的课堂与实践活动方案">
    <div class="plan-layout">
      <section class="page-panel plan-form-panel">
        <div class="panel-header plan-form-header"><div><span class="plan-kicker">方案配置</span><h2>生成一份教学方案</h2><p>{{ schoolStore.school?.schoolName || "当前学校" }} · 按教学需要选择资源与活动形式</p></div><span class="form-step">01</span></div>
        <form class="panel-body form-stack" @submit.prevent="generate">
          <section class="form-section"><div class="section-label"><span>基础信息</span><small>确定教学对象与主题</small></div><div class="form-grid compact-grid"><label>适用年级<input v-model="form.grade" placeholder="例如：四年级" /></label><label>活动时长（分钟）<input v-model.number="form.durationMinutes" type="number" min="20" step="10" /></label></div><label>教学主题<input v-model="form.theme" maxlength="200" placeholder="例如：敬老志愿服务" /></label><label>教学目标<textarea v-model="form.objectives" maxlength="2000" rows="3" placeholder="可填写知识、能力与情感目标；支持换行填写多个目标" /></label></section>
          <section class="form-section"><div class="section-label"><span>活动方式</span><small>选择实施形式与生成模型</small></div><div class="form-grid compact-grid"><label>活动类型<select v-model="form.activityType"><option value="VOLUNTEER_SERVICE">志愿服务</option><option value="FIELD_TRIP">实地研学</option><option value="CLASSROOM">课堂教学</option><option value="LABOR_PRACTICE">劳动实践</option><option value="SCHOOL_BASED_COURSE">校本课程</option></select></label><label>生成模型<select v-model="selectedModelId" :disabled="loading"><option value="">系统默认</option><option v-for="item in structuredModels" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.provider }}</option></select></label></div><label class="check-field"><input v-model="form.practiceRequired" type="checkbox" /><span>包含线下实践活动</span></label></section>
          <section class="form-section resource-section"><div class="section-label"><span>关联资源</span><small>最多选择 20 个，可不指定</small></div><fieldset class="resource-picker"><legend class="sr-only">关联思政资源</legend><label class="resource-option resource-option-none"><input type="checkbox" :checked="form.resourceIds.length === 0" @change="form.resourceIds = []" /><span>不指定资源，由系统综合推荐</span></label><div class="resource-options"><label v-for="item in schoolStore.resources" :key="item.resourceId" class="resource-option"><input type="checkbox" :checked="resourceSelected(item.resourceId)" @change="toggleResource(item.resourceId)" /><span>{{ item.resource?.resourceName || '未命名资源' }}</span><small>{{ item.distanceMeters ? `${item.distanceMeters} 米` : '校周边资源' }}</small></label></div><p v-if="form.resourceIds.length" class="selected-summary">已选 {{ form.resourceIds.length }} 项：{{ form.resourceIds.map(resourceName).join('、') }}</p></fieldset></section>
          <button v-if="!loading" class="primary-button full-button" type="submit"><Sparkles :size="18" />生成教学方案</button>
          <button v-else class="secondary-button full-button" type="button" @click="stopGeneration"><Square :size="16" />停止生成</button>
        </form>
      </section>

      <section class="page-panel result-panel">
        <div class="panel-header result-header"><div><span class="plan-kicker">方案预览</span><h2>{{ loading ? "正在生成" : (generated ? "教学方案" : "等待生成") }}</h2><p>{{ generated ? "支持保存、编辑、复制与导出。" : "生成后的课程结构、资源依据和实践安排将在这里呈现。" }}</p></div><div class="result-actions"><button class="secondary-button" type="button" :disabled="!generated || saving" @click="editing ? updatePlan() : saveDraft()"><Save :size="17" />{{ saving ? "保存中" : (editing ? "更新方案" : "保存草稿") }}</button><button v-if="editing" class="ghost-button" type="button" @click="editing = false; selectedPlanId = null"><RefreshCw :size="16" />新建</button></div></div>
        <div class="panel-body result-scroll">
          <InlineNotice v-if="notice.text" :tone="notice.tone">{{ notice.text }}</InlineNotice>
          <div v-if="loading || generated" :class="{ 'streaming-plan': loading }" aria-live="polite">
            <div v-if="loading" class="streaming-status"><span class="streaming-dot"></span>{{ streamStage }}</div>
            <div v-if="modelStatusVisible && effectiveModel" class="streaming-status">实际模型：{{ effectiveModel }}</div>
            <div v-if="visiblePlan" class="generated-plan">
              <header><div><span class="badge badge-red">{{ visiblePlan.grade }}</span><span class="badge">{{ visiblePlan.durationMinutes }} 分钟</span><span v-if="visiblePlan.practiceRequired" class="badge">实践活动</span></div><h2>{{ visiblePlan.theme }}</h2><p v-if="visiblePlan.selectedResources?.length" class="resource-meta">资源：{{ visiblePlan.selectedResources.map(item => item.resource?.resourceName || item.resourceId).join('、') }}</p></header>
              <section v-for="([title, items]) in sections" :key="title"><h3>{{ title }}</h3><textarea v-if="editing" class="plan-edit-textarea" :value="sectionText(items)" @input="updateSection({ '教学目标':'objectives', '资源依据':'resourceBasis', '活动流程':'activityFlow', '课前准备':'preparation', '现场任务':'fieldTasks', '安全提示':'safetyNotes', '课后反思':'reflection', '评价方式':'evaluation' }[title], $event)" /><ul v-else><li v-for="item in items" :key="item">{{ item }}</li></ul></section>
              <section v-if="visiblePlan.citations?.length"><h3>引用来源</h3><div class="citation-list"><article v-for="item in visiblePlan.citations" :key="item.citationId"><strong>{{ item.title || item.citationId }}</strong><p>{{ item.excerpt }}</p></article></div></section>
              <section v-if="!loading && visiblePlan.generationId" class="feedback-card">
                <h3>教师使用反馈</h3>
                <p class="feedback-hint">导出方案会自动标记为“已采纳”；请留下 1–5 分评分和文字建议，帮助持续优化生成质量。</p>
                <template v-if="visiblePlan.feedback">
                  <p class="submitted-feedback"><strong>{{ visiblePlan.feedback.adopted ? '已采纳' : '未采纳' }} · {{ visiblePlan.feedback.rating }} 分</strong><span v-if="visiblePlan.feedback.reasonCodes?.length"> · {{ visiblePlan.feedback.reasonCodes.map(reasonLabel).join('、') }}</span></p>
                  <p v-if="visiblePlan.feedback.teacherNote">{{ visiblePlan.feedback.teacherNote }}</p>
                </template>
                <template v-else>
                  <div class="feedback-choice" role="group" aria-label="是否采纳"><button type="button" :class="{ active: feedbackDraft.adopted }" @click="feedbackDraft.adopted = true; feedbackDraft.reasonCodes = []">采纳方案</button><button type="button" :class="{ active: !feedbackDraft.adopted }" @click="feedbackDraft.adopted = false">暂不采纳</button></div>
                  <div class="star-rating" role="radiogroup" aria-label="方案评分"><button v-for="score in 5" :key="score" type="button" :class="{ active: score <= feedbackDraft.rating }" :aria-label="`${score} 分`" :aria-checked="score === feedbackDraft.rating" role="radio" @click="feedbackDraft.rating = score"><Star :size="19" :fill="score <= feedbackDraft.rating ? 'currentColor' : 'none'" /></button><span>{{ feedbackDraft.rating ? `${feedbackDraft.rating} 分` : '请选择评分' }}</span></div>
                  <div v-if="!feedbackDraft.adopted" class="reason-options"><button v-for="[code, label] in feedbackReasons" :key="code" type="button" :class="{ active: feedbackDraft.reasonCodes.includes(code) }" @click="toggleReason(code)">{{ label }}</button></div>
                  <label class="feedback-note">文字反馈<textarea v-model="feedbackDraft.teacherNote" maxlength="2000" placeholder="可写下方案亮点、需要补充的内容或实际使用建议" /></label>
                  <button class="primary-button" type="button" :disabled="feedbackBusy" @click="submitFeedback(visiblePlan)">{{ feedbackBusy ? '保存中…' : '保存反馈' }}</button>
                </template>
              </section>
            </div>
            <div v-else-if="loading" class="streaming-copy">正在等待结构化内容<span class="streaming-caret"></span></div>
          </div>
          <div v-else class="empty-state plan-empty"><span class="empty-icon"><BookOpenCheck :size="28" /></span><strong>等待生成教学方案</strong><span>填写左侧基础信息，选择活动形式后即可开始生成。</span></div>
        </div>
      </section>
    </div>

    <section class="page-panel plan-library">
      <div class="panel-header"><div><h2>我的方案</h2><p>查看、编辑、复制和导出本人保存的教学方案。</p></div><span class="badge">{{ plans.length }} 条</span></div>
      <LoadingBlock v-if="historyLoading" />
      <div class="plan-filters"><input v-model="filters.grade" placeholder="年级" /><input v-model="filters.theme" placeholder="主题关键词" /><select v-model="filters.resourceId"><option value="">全部资源</option><option v-for="item in schoolStore.resources" :key="item.resourceId" :value="item.resourceId">{{ item.resource?.resourceName || '未命名资源' }}</option></select><input v-model="filters.createdFrom" type="date" /><input v-model="filters.createdTo" type="date" /><button class="secondary-button" type="button" @click="searchPlans"><Search :size="15" />查询</button><button class="ghost-button" type="button" @click="resetPlanFilters">重置</button></div>
      <div v-if="!historyLoading && plans.length" class="plan-table-wrap"><table><thead><tr><th>主题</th><th>年级</th><th>资源</th><th>创建时间</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="plan in plans" :key="plan.planId"><td><strong>{{ plan.theme }}</strong></td><td>{{ plan.suitableGrade || "-" }}</td><td>{{ plan.resourceName || (plan.resourceIds?.length ? `${plan.resourceIds.length} 个资源` : "-") }}</td><td>{{ formatDate(plan.createdAt) }}</td><td><span class="badge" :class="plan.reviewStatus?.toLowerCase() === 'approved' ? 'badge-green' : ''">{{ statusLabel(plan.reviewStatus) }}</span></td><td><button class="icon-button" title="查看并编辑" @click="loadPlan(plan.planId)"><FilePlus2 :size="15" /></button><button class="icon-button" title="复制方案" @click="copyPlan(plan.planId)"><Copy :size="15" /></button><button class="icon-button" :title="exportingPlanId === plan.planId ? '导出中' : '导出 Word'" :disabled="Boolean(exportingPlanId)" @click="exportPlan(plan.planId, plan.theme)"><Download :size="15" /></button></td></tr></tbody></table><div class="pagination"><button class="ghost-button" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button><span>{{ pageNum }} / {{ Math.max(1, Math.ceil(totalPlans / pageSize)) }}</span><button class="ghost-button" :disabled="pageNum >= Math.ceil(totalPlans / pageSize)" @click="changePage(pageNum + 1)">下一页</button></div></div>
      <div v-else-if="!historyLoading" class="empty-state">尚未保存教学方案</div>
    </section>

    <section class="page-panel feedback-library">
      <div class="panel-header"><div><h2>生成方案反馈</h2><p>已提交的评分会同步给管理员用于采纳率、评分和改进原因统计。</p></div></div>
      <div class="feedback-tabs" role="tablist"><button type="button" role="tab" :aria-selected="feedbackStatus === 'pending'" :class="{ active: feedbackStatus === 'pending' }" @click="selectFeedbackStatus('pending')">待评价</button><button type="button" role="tab" :aria-selected="feedbackStatus === 'submitted'" :class="{ active: feedbackStatus === 'submitted' }" @click="selectFeedbackStatus('submitted')">已评价</button></div>
      <div v-if="generationRecords.length" class="generation-list"><article v-for="item in generationRecords" :key="item.generationId" class="generation-card"><div><strong>{{ item.theme }}</strong><p>{{ item.grade || '未指定年级' }} · {{ formatDate(item.createdAt) }}</p></div><div v-if="item.feedback" class="submitted-feedback"><strong>{{ item.feedback.adopted ? '已采纳' : '未采纳' }} · {{ item.feedback.rating }} 分</strong><p v-if="item.feedback.reasonCodes?.length">{{ item.feedback.reasonCodes.map(reasonLabel).join('、') }}</p><p v-if="item.feedback.teacherNote">{{ item.feedback.teacherNote }}</p></div><button v-else class="secondary-button" type="button" @click="openGenerationFeedback(item)">填写评分</button></article></div>
      <div v-else class="empty-state compact-empty">当前筛选条件下暂无方案反馈。</div>
    </section>
  </AppShell>
</template>

<style scoped>
.plan-layout { display: grid; grid-template-columns: minmax(380px, 470px) minmax(0, 1fr); align-items: start; gap: 20px; }
.plan-form-panel, .result-panel { overflow: hidden; }
.plan-form-header, .result-header { min-height: 112px; }
.plan-kicker { display: block; margin-bottom: 5px; color: var(--red); font-size: 11px; font-weight: 800; letter-spacing: .12em; }
.form-step { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid #d8d1c2; border-radius: 50%; color: var(--green); font-family: var(--font-display); font-size: 14px; }
.form-stack { gap: 20px; }
.form-section { display: grid; gap: 13px; }
.form-section + .form-section { padding-top: 19px; border-top: 1px solid var(--line); }
.section-label { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.section-label span { color: var(--text); font-family: var(--font-display); font-size: 15px; font-weight: 700; }
.section-label small { color: var(--muted); font-size: 12px; }
.compact-grid { gap: 12px; }
.check-field { display: flex; align-items: center; gap: 9px; width: fit-content; padding: 9px 11px; border-radius: 7px; background: var(--green-soft); color: var(--green); font-size: 13px; }
.check-field input { width: 17px; min-height: 17px; margin: 0; accent-color: var(--green); }
.resource-section { gap: 11px; }
.resource-picker { min-width: 0; margin: 0; padding: 0; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; background: #fff; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }
.resource-options { display: grid; max-height: 178px; overflow-y: auto; border-top: 1px solid var(--line); }
.resource-option { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; align-items: center; gap: 9px; min-height: 42px; padding: 8px 11px; border-bottom: 1px solid #eee9dd; color: var(--text); font-size: 12px; font-weight: 500; }
.resource-option:last-child { border-bottom: 0; }
.resource-option:hover { background: #faf8f1; }
.resource-option input { width: 16px; min-height: 16px; margin: 0; accent-color: var(--green); }
.resource-option span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.resource-option small { color: var(--muted); font-size: 11px; white-space: nowrap; }
.resource-option-none { grid-template-columns: 18px minmax(0, 1fr); background: #faf8f1; color: #536057; }
.selected-summary { margin: 0; padding: 9px 11px; border-top: 1px solid var(--line); background: var(--green-soft); color: var(--green); font-size: 12px; line-height: 1.55; }
.result-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.icon-button { border: 1px solid var(--line); background: #fff; padding: 5px 7px; margin-right: 5px; cursor: pointer; }
.result-panel { position: sticky; top: 91px; min-height: 680px; }
.result-scroll { max-height: calc(100vh - 195px); overflow-y: auto; }
.streaming-plan { min-height: 500px; padding: 18px 0; }
.streaming-status { display: flex; align-items: center; gap: 8px; color: var(--green); font-size: 13px; font-weight: 700; }
.streaming-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--red); animation: pulse 1.1s ease-in-out infinite; }
.streaming-copy { margin-top: 18px; white-space: pre-wrap; color: var(--text); line-height: 1.85; font-size: 15px; }
.streaming-caret { display: inline-block; width: 2px; height: 1.1em; margin-left: 3px; vertical-align: -2px; background: var(--red); animation: blink .8s steps(1) infinite; }
@keyframes pulse { 50% { opacity: .35; transform: scale(.8); } }
@keyframes blink { 50% { opacity: 0; } }
.generated-plan { max-width: 850px; margin: 18px auto 0; }
.generated-plan header { padding-bottom: 18px; border-bottom: 1px solid var(--line); }
.generated-plan header > div { display: flex; gap: 7px; }
.generated-plan header h2 { margin: 12px 0 0; font-size: 25px; }
.generated-plan section { padding: 18px 0; border-bottom: 1px solid var(--line); }
.generated-plan section h3 { margin-bottom: 10px; font-size: 15px; color: var(--green); }
.generated-plan ul { display: grid; gap: 8px; margin: 0; padding-left: 20px; line-height: 1.7; }
.plan-edit-textarea { width: 100%; min-height: 96px; border: 1px solid var(--line); padding: 9px; line-height: 1.6; resize: vertical; }
.citation-list { display: grid; gap: 8px; }
.citation-list article { padding: 12px; border-left: 3px solid var(--red); background: #f8f9f7; }
.citation-list p { margin: 6px 0 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.feedback-card { display: grid; gap: 12px; padding: 18px; border: 1px solid #d5dfd6; border-radius: 9px; background: linear-gradient(135deg, #f7faf5, #fffdf8); }
.feedback-card h3 { margin: 0; }
.feedback-hint, .feedback-note { color: var(--muted); font-size: 13px; line-height: 1.6; }
.feedback-choice, .star-rating, .reason-options { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.feedback-choice button, .reason-options button, .star-rating button { border: 1px solid var(--line); background: #fff; color: var(--muted); cursor: pointer; }
.feedback-choice button { padding: 7px 11px; border-radius: 999px; }
.feedback-choice button.active, .reason-options button.active { border-color: var(--green); background: var(--green-soft); color: var(--green); }
.star-rating button { display: grid; place-items: center; padding: 4px; border: 0; color: #b3a991; }
.star-rating button.active { color: #ca8b25; }
.reason-options button { padding: 5px 8px; border-radius: 5px; font-size: 12px; }
.feedback-note { display: grid; gap: 6px; font-weight: 700; }
.feedback-note textarea { min-height: 78px; resize: vertical; }
.submitted-feedback { color: var(--green); font-size: 13px; line-height: 1.6; }
.submitted-feedback p { margin: 3px 0; color: var(--text); }
.feedback-library { margin-top: 20px; overflow: hidden; }
.feedback-tabs { display: flex; gap: 8px; padding: 0 20px 14px; border-bottom: 1px solid var(--line); }
.feedback-tabs button { padding: 7px 11px; border: 1px solid var(--line); border-radius: 999px; background: #fff; color: var(--muted); }
.feedback-tabs button.active { background: var(--green); border-color: var(--green); color: #fff; }
.generation-list { display: grid; }
.generation-card { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; padding: 15px 20px; border-bottom: 1px solid var(--line); }
.generation-card p { margin: 5px 0 0; color: var(--muted); font-size: 13px; }
.compact-empty { min-height: 100px; }
.plan-empty { min-height: 500px; }
.plan-empty strong { color: var(--text); font-family: var(--font-display); font-size: 18px; }
.plan-empty > span:last-child { max-width: 290px; font-size: 13px; line-height: 1.7; }
.empty-icon { display: grid; place-items: center; width: 58px; height: 58px; border: 1px solid #cdd8d0; border-radius: 50%; background: var(--green-soft); color: var(--green); }
.plan-library { margin-top: 20px; overflow: hidden; }
.plan-filters { display: grid; grid-template-columns: 120px minmax(180px, 1fr) minmax(180px, 1fr) 148px 148px auto auto; gap: 9px; padding: 16px 20px; border-bottom: 1px solid var(--line); background: #faf8f1; }
.plan-filters input, .plan-filters select { min-height: 34px; border: 1px solid var(--line); padding: 0 9px; background: #fff; }
.pagination { display: flex; justify-content: center; align-items: center; gap: 14px; padding: 14px; }
.plan-table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; min-width: 680px; }
th, td { padding: 13px 18px; border-bottom: 1px solid var(--line); text-align: left; font-size: 14px; }
th { color: var(--muted); background: #f7f8f6; font-size: 12px; }
@media (max-width: 1240px) { .plan-layout { grid-template-columns: minmax(350px, 410px) minmax(0, 1fr); } .plan-filters { grid-template-columns: 120px minmax(160px, 1fr) minmax(160px, 1fr) 132px 132px; } }
@media (max-width: 1080px) { .plan-layout { grid-template-columns: 1fr; } .result-panel { position: static; min-height: 540px; } .result-scroll { max-height: none; } .plan-empty { min-height: 340px; } .plan-filters { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 640px) { .plan-form-header, .result-header { min-height: auto; } .section-label { align-items: flex-start; flex-direction: column; gap: 3px; } .result-actions { justify-content: flex-start; width: 100%; } .compact-grid, .plan-filters { grid-template-columns: 1fr; } .resource-option { grid-template-columns: 18px minmax(0, 1fr); } .resource-option small { grid-column: 2; } .plan-empty { min-height: 280px; } }
</style>
