<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { BookOpenCheck, FilePlus2, Save, Sparkles } from "@lucide/vue";
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
const plans = ref([]);
const loading = ref(false);
const saving = ref(false);
const historyLoading = ref(false);
const notice = reactive({ tone: "", text: "" });

const sections = computed(() => generated.value ? [
  ["教学目标", generated.value.objectives], ["资源依据", generated.value.resourceBasis], ["活动流程", generated.value.activityFlow],
  ["课前准备", generated.value.preparation], ["现场任务", generated.value.fieldTasks], ["安全提示", generated.value.safetyNotes],
  ["课后反思", generated.value.reflection], ["评价方式", generated.value.evaluation]
].filter(([, items]) => Array.isArray(items) && items.length) : []);

onMounted(async () => {
  await schoolStore.load();
  await loadPlans();
  const theme = schoolStore.resources.find((item) => item.educationThemeSummary)?.educationThemeSummary;
  if (theme) form.theme = theme.slice(0, 40);
});

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
  try {
    generated.value = await api.post("/api/ai/teaching-plans/generate", {
      schoolId: auth.user.schoolId, grade: form.grade.trim(), theme: form.theme.trim(), activityType: form.activityType,
      durationMinutes: Number(form.durationMinutes), practiceRequired: form.practiceRequired
    });
    notice.tone = generated.value.generationStatus === "completed" ? "success" : "info";
    notice.text = generated.value.message || (generated.value.generationStatus === "completed" ? "教学方案已生成。" : "已生成可用的本地方案。 ");
  } catch (error) {
    notice.tone = "error"; notice.text = error.message || "教学方案生成失败。";
  } finally {
    loading.value = false;
  }
}

async function saveDraft() {
  if (!generated.value) return;
  saving.value = true;
  try {
    await api.post("/api/ai/teaching-plans/save-draft", {
      schoolId: auth.user.schoolId, resourceId: schoolStore.resources[0]?.resourceId || null,
      theme: generated.value.theme, activityType: generated.value.activityType || form.activityType,
      grade: generated.value.grade, durationMinutes: generated.value.durationMinutes,
      objectives: generated.value.objectives || [], activityFlow: generated.value.activityFlow || [],
      preparation: generated.value.preparation || [], safetyNotes: generated.value.safetyNotes || [],
      reflection: generated.value.reflection || [], evaluation: generated.value.evaluation || []
    });
    notice.tone = "success"; notice.text = "草稿已保存到学校方案库。";
    await loadPlans();
  } catch (error) {
    notice.tone = "error"; notice.text = error.message || "保存失败。";
  } finally {
    saving.value = false;
  }
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
          <label class="check-field"><input v-model="form.practiceRequired" type="checkbox" /><span>包含线下实践活动</span></label>
          <button class="primary-button full-button" type="submit" :disabled="loading"><Sparkles :size="18" />{{ loading ? "正在生成" : "生成教学方案" }}</button>
        </form>
      </section>

      <section class="page-panel result-panel">
        <div class="panel-header"><div><h2>生成结果</h2><p>内容可保存为草稿，由管理员继续审核完善。</p></div><button class="secondary-button" type="button" :disabled="!generated || saving" @click="saveDraft"><Save :size="17" />{{ saving ? "保存中" : "保存草稿" }}</button></div>
        <div class="panel-body result-scroll">
          <InlineNotice v-if="notice.text" :tone="notice.tone">{{ notice.text }}</InlineNotice>
          <LoadingBlock v-if="loading" />
          <div v-else-if="generated" class="generated-plan">
            <header><div><span class="badge badge-red">{{ generated.grade }}</span><span class="badge">{{ generated.durationMinutes }} 分钟</span></div><h2>{{ generated.theme }}</h2></header>
            <section v-for="([title, items]) in sections" :key="title"><h3>{{ title }}</h3><ul><li v-for="item in items" :key="item">{{ item }}</li></ul></section>
            <section v-if="generated.citations?.length"><h3>引用来源</h3><div class="citation-list"><article v-for="item in generated.citations" :key="item.citationId"><strong>{{ item.title || item.citationId }}</strong><p>{{ item.excerpt }}</p></article></div></section>
          </div>
          <div v-else class="empty-state"><BookOpenCheck :size="40" /><span>填写左侧参数后生成教学方案</span></div>
        </div>
      </section>
    </div>

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
.plan-library { margin-top: 16px; }
.plan-table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; min-width: 680px; }
th, td { padding: 13px 18px; border-bottom: 1px solid var(--line); text-align: left; font-size: 14px; }
th { color: var(--muted); background: #f7f8f6; font-size: 12px; }
@media (max-width: 1080px) { .plan-layout { grid-template-columns: 1fr; } .result-panel { min-height: 520px; } .result-scroll { max-height: none; } }
</style>
