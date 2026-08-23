<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { BookOpenCheck, Copy, FilePlus2, Save, Sparkles, Square, RefreshCw } from "@lucide/vue";
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

const visiblePlan = computed(() => loading.value ? draftPlan.value : generated.value);
const sections = computed(() => visiblePlan.value ? [
  ["教学目标", visiblePlan.value.objectives], ["资源依据", visiblePlan.value.resourceBasis], ["活动流程", visiblePlan.value.activityFlow],
  ["课前准备", visiblePlan.value.preparation], ["现场任务", visiblePlan.value.fieldTasks], ["安全提示", visiblePlan.value.safetyNotes],
  ["课后反思", visiblePlan.value.reflection], ["评价方式", visiblePlan.value.evaluation]
].filter(([, items]) => Array.isArray(items) && items.length) : []);

onMounted(async () => {
  threadId.value = sessionStorage.getItem(threadStorageKey()) || "";
  await Promise.all([schoolStore.load(), loadModels()]);
  await loadPlans();
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
      objectives: generated.value.objectives || [], activityFlow: generated.value.activityFlow || [],
      preparation: generated.value.preparation || [], safetyNotes: generated.value.safetyNotes || [],
      reflection: generated.value.reflection || [], evaluation: generated.value.evaluation || [],
      resourceBasis: generated.value.resourceBasis || [], fieldTasks: generated.value.fieldTasks || [],
      relatedResources: generated.value.relatedResources || [], citations: generated.value.citations || []
    });
    notice.tone = "success"; notice.text = "草稿已保存到学校方案库。";
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
          <label>教学主题<input v-model="form.theme" maxlength="200" placeholder="例如：敬老志愿服务" /></label>
          <label>教学目标<textarea v-model="form.objectives" maxlength="2000" rows="3" placeholder="可填写知识、能力与情感目标；支持换行填写多个目标" /></label>
          <label>活动类型<select v-model="form.activityType"><option value="VOLUNTEER_SERVICE">志愿服务</option><option value="FIELD_TRIP">实地研学</option><option value="CLASSROOM">课堂教学</option><option value="LABOR_PRACTICE">劳动实践</option><option value="SCHOOL_BASED_COURSE">校本课程</option></select></label>
          <label>活动时长（分钟）<input v-model.number="form.durationMinutes" type="number" min="20" step="10" /></label>
          <label>生成模型<select v-model="selectedModelId" :disabled="loading"><option value="">系统默认</option><option v-for="item in structuredModels" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.provider }}</option></select></label>
          <fieldset class="resource-picker"><legend>关联思政资源（最多 20 个）</legend><label class="resource-option"><input type="checkbox" :checked="form.resourceIds.length === 0" @change="form.resourceIds = []" />不指定资源</label><label v-for="item in schoolStore.resources" :key="item.resourceId" class="resource-option"><input type="checkbox" :checked="resourceSelected(item.resourceId)" @change="toggleResource(item.resourceId)" /><span>{{ item.resource?.resourceName || '未命名资源' }}</span><small>{{ item.distanceMeters ? `${item.distanceMeters} 米` : '' }}</small></label><p v-if="form.resourceIds.length" class="selected-summary">已选：{{ form.resourceIds.map(resourceName).join('、') }}</p></fieldset>
          <label class="check-field"><input v-model="form.practiceRequired" type="checkbox" /><span>包含线下实践活动</span></label>
          <button v-if="!loading" class="primary-button full-button" type="submit"><Sparkles :size="18" />生成教学方案</button>
          <button v-else class="secondary-button full-button" type="button" @click="stopGeneration"><Square :size="16" />停止生成</button>
        </form>
      </section>

      <section class="page-panel result-panel">
        <div class="panel-header"><div><h2>生成结果</h2><p>内容可保存为个人草稿，后续编辑和复用。</p></div><div class="result-actions"><button class="secondary-button" type="button" :disabled="!generated || saving" @click="editing ? updatePlan() : saveDraft()"><Save :size="17" />{{ saving ? "保存中" : (editing ? "更新方案" : "保存草稿") }}</button><button v-if="editing" class="ghost-button" type="button" @click="editing = false; selectedPlanId = null"><RefreshCw :size="16" />新建</button></div></div>
        <div class="panel-body result-scroll">
          <InlineNotice v-if="notice.text" :tone="notice.tone">{{ notice.text }}</InlineNotice>
          <div v-if="loading || generated" :class="{ 'streaming-plan': loading }" aria-live="polite">
            <div v-if="loading" class="streaming-status"><span class="streaming-dot"></span>{{ streamStage }}</div>
            <div v-if="modelStatusVisible && effectiveModel" class="streaming-status">实际模型：{{ effectiveModel }}</div>
            <div v-if="visiblePlan" class="generated-plan">
              <header><div><span class="badge badge-red">{{ visiblePlan.grade }}</span><span class="badge">{{ visiblePlan.durationMinutes }} 分钟</span><span v-if="visiblePlan.practiceRequired" class="badge">实践活动</span></div><h2>{{ visiblePlan.theme }}</h2><p v-if="visiblePlan.selectedResources?.length" class="resource-meta">资源：{{ visiblePlan.selectedResources.map(item => item.resource?.resourceName || item.resourceId).join('、') }}</p></header>
              <section v-for="([title, items]) in sections" :key="title"><h3>{{ title }}</h3><textarea v-if="editing" class="plan-edit-textarea" :value="sectionText(items)" @input="updateSection({ '教学目标':'objectives', '资源依据':'resourceBasis', '活动流程':'activityFlow', '课前准备':'preparation', '现场任务':'fieldTasks', '安全提示':'safetyNotes', '课后反思':'reflection', '评价方式':'evaluation' }[title], $event)" /><ul v-else><li v-for="item in items" :key="item">{{ item }}</li></ul></section>
              <section v-if="visiblePlan.citations?.length"><h3>引用来源</h3><div class="citation-list"><article v-for="item in visiblePlan.citations" :key="item.citationId"><strong>{{ item.title || item.citationId }}</strong><p>{{ item.excerpt }}</p></article></div></section>
            </div>
            <div v-else-if="loading" class="streaming-copy">正在等待结构化内容<span class="streaming-caret"></span></div>
          </div>
          <div v-else class="empty-state"><BookOpenCheck :size="40" /><span>填写左侧参数后生成教学方案</span></div>
        </div>
      </section>
    </div>

    <section class="page-panel plan-library">
      <div class="panel-header"><div><h2>学校方案库</h2><p>包含已保存草稿与经过审核的教学方案。</p></div><span class="badge">{{ plans.length }} 条</span></div>
      <LoadingBlock v-if="historyLoading" />
      <div v-else-if="plans.length" class="plan-table-wrap"><table><thead><tr><th>主题</th><th>年级</th><th>类型</th><th>时长</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="plan in plans" :key="plan.planId"><td><strong>{{ plan.theme }}</strong></td><td>{{ plan.suitableGrade || "-" }}</td><td>{{ plan.activityType || "-" }}</td><td>{{ plan.durationMinutes ? `${plan.durationMinutes} 分钟` : "-" }}</td><td><span class="badge" :class="plan.reviewStatus?.toLowerCase() === 'approved' ? 'badge-green' : ''">{{ statusLabel(plan.reviewStatus) }}</span></td><td><button class="icon-button" title="查看并编辑" @click="loadPlan(plan.planId)"><FilePlus2 :size="15" /></button><button class="icon-button" title="复制方案" @click="copyPlan(plan.planId)"><Copy :size="15" /></button></td></tr></tbody></table></div>
      <div v-else class="empty-state">尚未保存教学方案</div>
    </section>
  </AppShell>
</template>

<style scoped>
.plan-layout { display: grid; grid-template-columns: minmax(280px,360px) minmax(0,1fr); gap: 16px; }
.panel-header > svg { color: var(--green); }
.check-field { display: flex; align-items: center; gap: 9px; }
.check-field input { width: 17px; min-height: 17px; }
.resource-picker { border: 1px solid var(--line); padding: 10px; display: grid; gap: 7px; max-height: 190px; overflow-y: auto; }
.resource-picker legend { font-size: 12px; color: var(--muted); padding: 0 4px; }
.resource-option { display: flex; align-items: center; gap: 7px; font-size: 12px; }
.resource-option small { margin-left: auto; color: var(--muted); }
.selected-summary { margin: 3px 0 0; color: var(--green); font-size: 11px; line-height: 1.5; }
.result-actions { display: flex; gap: 7px; }
.icon-button { border: 1px solid var(--line); background: #fff; padding: 5px 7px; margin-right: 5px; cursor: pointer; }
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
.plan-edit-textarea { width: 100%; min-height: 96px; border: 1px solid var(--line); padding: 9px; line-height: 1.6; resize: vertical; }
.citation-list { display: grid; gap: 8px; }
.citation-list article { padding: 12px; border-left: 3px solid var(--red); background: #f8f9f7; }
.citation-list p { margin: 6px 0 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.plan-library { margin-top: 16px; }
.plan-table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; min-width: 680px; }
th, td { padding: 13px 18px; border-bottom: 1px solid var(--line); text-align: left; font-size: 14px; }
th { color: var(--muted); background: #f7f8f6; font-size: 12px; }
@media (max-width: 1080px) { .plan-layout { grid-template-columns: 1fr; } .result-panel { min-height: 520px; } .result-scroll { max-height: none; } }
</style>
