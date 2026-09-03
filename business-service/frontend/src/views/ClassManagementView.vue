<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { CheckCircle2, ClipboardList, KeyRound, Plus, RotateCw, UserPlus, Users } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import LoadingBlock from "@/components/LoadingBlock.vue";
import { api, apiRequest } from "@/services/api";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const router = useRouter();
const isStudent = computed(() => auth.user?.roleCode === "student");
const isAdmin = computed(() => auth.isAdmin);
const classes = ref([]);
const teachers = ref([]);
const selected = ref(null);
const selectedId = ref(null);
const candidates = ref([]);
const loading = ref(true);
const saving = ref(false);
const notice = reactive({ tone: "", text: "" });
const joinCode = ref("");
const importFile = ref(null);
const taskForm = reactive({ title: "", description: "", startAt: "", dueAt: "", submissionRule: "text_required", taskType: "red_culture_learning" });
const materialFile = ref(null); const availableResources = ref([]); const selectedResourceIds = ref([]);
const classForm = reactive({ className: "", gradeName: "", classType: "administrative", headTeacherId: "", subjectTeacherIds: [] });
const studentDetail = ref(null); const studentTasks = ref([]); const activities = ref([]); const studentSelectedId = ref(null); const studentLoading = ref(false);

onMounted(load);
watch(selected, () => nextTick(moveSubmissionPanel));
function moveSubmissionPanel() {
  const panel = document.querySelector('.submission-choice-panel');
  const form = document.querySelector('.task-form');
  if (panel && form && !form.contains(panel)) form.insertBefore(panel, form.querySelector('.primary-button'));
}

async function load() {
  loading.value = true;
  try {
    if (isStudent.value) {
      classes.value = await api.get("/api/student/classes");
      if (studentSelectedId.value) await selectStudentClass(studentSelectedId.value);
      else if (classes.value.length) await selectStudentClass(classes.value[0].classId);
    } else {
      const requests = [api.get("/api/teacher/classes/mine")];
      if (isAdmin.value) requests.push(api.get("/api/teacher/classes/available-teachers"));
      const [items, teacherItems = []] = await Promise.all(requests);
      classes.value = items;
      teachers.value = teacherItems;
      if (selectedId.value) await selectClass(selectedId.value);
    }
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

async function selectStudentClass(classId) { studentSelectedId.value = classId; studentLoading.value = true; try { const [detail, taskPage, activityPage] = await Promise.all([api.get(`/api/student/classes/${classId}`), api.get(`/api/student/classes/${classId}/tasks?pageNum=1&pageSize=20`), api.get(`/api/student/classes/${classId}/activities?pageNum=1&pageSize=20`)]); studentDetail.value = detail; studentTasks.value = taskPage.records || []; activities.value = activityPage.records || []; } catch (error) { showError(error); } finally { studentLoading.value = false; } }

async function selectClass(classId) {
  selectedId.value = classId;
  try {
    selected.value = await api.get(`/api/teacher/classes/${classId}`);
    availableResources.value = await api.get("/api/teacher/tasks/task-resources").catch(() => []);
    if (selected.value.canManageStudents) candidates.value = await api.get(`/api/teacher/classes/${classId}/available-students`);
    else candidates.value = [];
  } catch (error) { showError(error); }
}

async function createClass() {
  saving.value = true;
  try {
    const created = await api.post("/api/teacher/classes", {
      ...classForm,
      schoolId: auth.user?.schoolId,
      headTeacherId: classForm.headTeacherId ? Number(classForm.headTeacherId) : null,
      subjectTeacherIds: classForm.subjectTeacherIds.map(Number)
    });
    notice.tone = "success"; notice.text = "班级已创建";
    classForm.className = ""; classForm.gradeName = ""; classForm.headTeacherId = ""; classForm.subjectTeacherIds = [];
    await load(); await selectClass(created.classId);
  } catch (error) { showError(error); } finally { saving.value = false; }
}

async function addStudent(studentId) {
  try {
    await api.post(`/api/teacher/classes/${selectedId.value}/students`, { studentId });
    notice.tone = "success"; notice.text = "学生已加入班级"; await selectClass(selectedId.value); await load();
  } catch (error) { showError(error); }
}

async function removeStudent(studentId) {
  try {
    await api.delete(`/api/teacher/classes/${selectedId.value}/students/${studentId}`);
    notice.tone = "success"; notice.text = "学生已移出班级"; await selectClass(selectedId.value); await load();
  } catch (error) { showError(error); }
}

function importFileChanged(event) { importFile.value = event.target.files?.[0] || null; }
async function importStudents() {
  if (!importFile.value) return;
  try {
    const body = new FormData(); body.append("file", importFile.value);
    const result = await apiRequest(`/api/teacher/classes/${selectedId.value}/students/import-excel`, { method: "POST", body });
    notice.tone = result.failedCount ? "info" : "success";
    notice.text = `导入完成：成功 ${result.successCount}，失败 ${result.failedCount}${result.errors?.length ? `（${result.errors.join("；")}）` : ""}`;
    importFile.value = null; await selectClass(selectedId.value); await load();
  } catch (error) { showError(error); }
}

async function rotateInvite() {
  try {
    const code = await api.post(`/api/teacher/classes/${selectedId.value}/invite-code`, {});
    selected.value.inviteCode = code; notice.tone = "success"; notice.text = "邀请码已更新";
  } catch (error) { showError(error); }
}

async function publishTask() {
  if (!taskForm.title.trim()) return;
  try {
    const body = new FormData();
    body.append("task", new Blob([JSON.stringify({ ...taskForm, startAt: taskForm.startAt ? `${taskForm.startAt}:00` : null, dueAt: taskForm.dueAt ? `${taskForm.dueAt}:00` : null, resourceIds: selectedResourceIds.value })], { type: "application/json" }));
    if (materialFile.value) body.append("material", materialFile.value);
    await apiRequest(`/api/teacher/classes/${selectedId.value}/tasks/with-material`, { method: "POST", body });
    notice.tone = "success"; notice.text = "学习任务已发布"; taskForm.title = ""; taskForm.description = ""; taskForm.startAt = ""; taskForm.dueAt = ""; materialFile.value = null; selectedResourceIds.value = [];
    await selectClass(selectedId.value); await load();
  } catch (error) { showError(error); }
}
function materialChanged(event) { materialFile.value = event.target.files?.[0] || null; }
const submissionOptions = [
  ["text", "无要求，仅提交文字"], ["image", "提交图片"], ["document", "提交 Word 文档"]
];
function selectedSubmissionParts() {
  const legacy = { text_only: "text", text_required: "text", image_required: "image", document_required: "document", image_or_document: "image+document", attachment_required: "image+document", text_and_attachment: "text+image+document" };
  return new Set((legacy[taskForm.submissionRule] || taskForm.submissionRule || "text_required").split("+").map((item) => item.trim()));
}
function submissionChecked(part) { return selectedSubmissionParts().has(part); }
function setSubmissionRule(part) {
  const selected = selectedSubmissionParts();
  if (selected.has(part)) selected.delete(part); else selected.add(part);
  if (!selected.size) selected.add("text");
  const key = ["text", "image", "document"].filter((item) => selected.has(item)).join("+");
  taskForm.submissionRule = { text: "text_required", image: "image_required", document: "document_required", "text+image": "text_and_image", "text+document": "text_and_document", "image+document": "image_and_document", "text+image+document": "text_and_image_and_document" }[key];
}

async function joinByInvite() {
  try {
    const result = await api.post("/api/student/classes/join-by-invite", { inviteCode: joinCode.value.trim() });
    notice.tone = "success"; notice.text = `已加入${result.className}`; joinCode.value = ""; await load();
  } catch (error) { showError(error); }
}

async function completeTask(taskId) {
  try { await api.post(`/api/student/class-tasks/${taskId}/complete`, {}); notice.tone = "success"; notice.text = "任务已标记完成"; await load(); } catch (error) { showError(error); }
}

function showError(error) { notice.tone = "error"; notice.text = error?.message || "操作失败"; }
function classType(type) { return type === "teaching" ? "教学班" : "行政班"; }
function taskStatus(task) { return { completed: "已完成", overdue: "已逾期", pending: "待完成" }[task.studentStatus] || task.status; }
function openTask(taskId) { router.push(`/tasks?taskId=${taskId}`); }
function askTask(taskId) { router.push(`/assistant?taskId=${taskId}`); }
</script>

<template>
  <AppShell :title="isStudent ? '我的学习任务' : '班级管理'" :subtitle="isStudent ? '完成老师发布到班级的学习任务' : '管理负责班级、学生与学习任务'">
    <InlineNotice v-if="notice.text" :tone="notice.tone">{{ notice.text }}</InlineNotice>
    <LoadingBlock v-if="loading" />

    <template v-else-if="isStudent">
      <section class="page-panel join-panel"><div class="panel-header"><div><h2>加入班级</h2><p>输入班主任提供的邀请码</p></div><KeyRound :size="20" /></div><form class="panel-body invite-form" @submit.prevent="joinByInvite"><input v-model="joinCode" placeholder="班级邀请码" maxlength="32" /><button class="primary-button" type="submit">加入</button></form></section>
      <div class="student-class-layout"><section class="page-panel"><div class="panel-header"><div><h2>我的班级</h2><p>{{ classes.length }} 个已加入班级</p></div><Users :size="20" /></div><div class="class-list"><button v-for="item in classes" :key="item.classId" type="button" class="class-card" :class="{ active: studentSelectedId === item.classId }" @click="selectStudentClass(item.classId)"><strong>{{ item.className }}</strong><span>{{ item.gradeName || '未设置年级' }}<em v-if="item.primary"> · 主班级</em></span><small>{{ item.teacherCount }} 位教师 · 待完成 {{ item.pendingTaskCount }} 项</small></button><div v-if="!classes.length" class="empty-state">暂未加入班级</div></div></section>
        <section class="page-panel student-detail"><LoadingBlock v-if="studentLoading" /><template v-else-if="studentDetail"><div class="panel-header"><div><h2>{{ studentDetail.className }}</h2><p>{{ studentDetail.gradeName || '未设置年级' }} · {{ classType(studentDetail.classType) }} · {{ studentDetail.studentCount }} 名学生</p></div><span v-if="studentDetail.primary" class="badge badge-green">主班级</span></div><div class="student-detail-body"><section><h3>任课教师</h3><div class="teacher-tags"><span v-for="teacher in studentDetail.teachers" :key="teacher.teacherId" class="badge">{{ teacher.teacherName }} · {{ teacher.teacherRole === 'head_teacher' ? '班主任' : '任课教师' }}</span><span v-if="!studentDetail.teachers?.length" class="muted">暂无教师信息</span></div></section><section><h3>学习进度</h3><div class="student-stats"><span>待完成 <strong>{{ studentDetail.taskSummary.pendingCount }}</strong></span><span>待评价 <strong>{{ studentDetail.taskSummary.submittedCount }}</strong></span><span>已完成 <strong>{{ studentDetail.taskSummary.completedCount }}</strong></span><span>已逾期 <strong>{{ studentDetail.taskSummary.overdueCount }}</strong></span></div></section></div><section class="student-tasks"><div class="task-heading"><h3>班级学习任务</h3></div><div v-if="studentTasks.length" class="task-list"><article v-for="task in studentTasks" :key="task.taskId" class="task-row"><div><span class="badge" :class="task.studentStatus === 'completed' ? 'badge-green' : task.studentStatus === 'overdue' ? 'badge-red' : ''">{{ taskStatus(task) }}</span><h3>{{ task.title }}</h3><p>{{ task.description || '暂无任务说明' }}</p><small>截止：{{ task.dueAt || '未设置' }} · 关联资源 {{ task.resourceCount || 0 }} 个</small></div><div class="task-actions"><button class="secondary-button" type="button" @click="openTask(task.taskId)">查看任务</button><button class="text-button" type="button" @click="askTask(task.taskId)">询问任务</button></div></article></div><div v-else class="empty-state">暂无已发布任务</div></section><section class="student-activities"><div class="task-heading"><h3>学习动态</h3><button class="text-button" type="button" @click="selectStudentClass(studentSelectedId)">刷新</button></div><div v-if="activities.length" class="activity-list"><article v-for="item in activities" :key="`${item.activityType}-${item.relatedTaskId}-${item.createdAt}`"><span class="activity-dot"></span><div><strong>{{ item.title }}</strong><p>{{ item.content }}</p><small>{{ item.createdAt ? String(item.createdAt).replace('T', ' ').slice(0, 16) : '-' }}</small></div></article></div><div v-else class="empty-state">暂时没有学习动态</div></section></template><div v-else class="empty-state">选择班级查看详情</div></section></div>
    </template>

    <template v-else>
      <div class="class-layout">
        <section class="page-panel class-list-panel"><div class="panel-header"><div><h2>我的班级</h2><p>{{ classes.length }} 个可访问班级</p></div><Users :size="20" /></div><div class="class-list"><button v-for="item in classes" :key="item.classId" type="button" class="class-card" :class="{ active: selectedId === item.classId }" @click="selectClass(item.classId)"><strong>{{ item.className }}</strong><span>{{ item.gradeName || '未设置年级' }} · {{ classType(item.classType) }}</span><small>{{ item.studentCount }} 名学生 · 完成率 {{ item.completionRate }}%</small></button><div v-if="!classes.length" class="empty-state">暂无负责班级</div></div></section>
        <section v-if="isAdmin" class="page-panel create-panel"><div class="panel-header"><div><h2>创建班级</h2><p>仅管理员可指定本校班主任与任课教师</p></div><Plus :size="20" /></div><form class="panel-body form-stack" @submit.prevent="createClass"><label>班级名称<input v-model="classForm.className" required /></label><label>年级<input v-model="classForm.gradeName" /></label><label>班级类型<select v-model="classForm.classType"><option value="administrative">行政班</option><option value="teaching">教学班</option></select></label><label>班主任<select v-model="classForm.headTeacherId"><option value="">不设置</option><option v-for="teacher in teachers" :key="teacher.teacherId" :value="teacher.teacherId">{{ teacher.teacherName }}</option></select></label><label>任课教师<select v-model="classForm.subjectTeacherIds" multiple><option v-for="teacher in teachers" :key="teacher.teacherId" :value="teacher.teacherId">{{ teacher.teacherName }}</option></select></label><button class="primary-button full-button" :disabled="saving" type="submit">创建班级</button></form></section>
      </div>

      <section v-if="selected" class="page-panel detail-panel"><div class="panel-header"><div><h2>{{ selected.className }}</h2><p>{{ selected.gradeName || '未设置年级' }} · {{ classType(selected.classType) }} · {{ selected.studentCount }} 名学生</p></div><span class="badge" :class="selected.headTeacher ? 'badge-green' : ''">{{ selected.headTeacher ? '班主任' : '任课教师' }}</span></div><div class="detail-grid"><section><h3>任课信息</h3><div class="teacher-tags"><span v-for="teacher in selected.teachers" :key="teacher.teacherId" class="badge">{{ teacher.teacherName }} · {{ teacher.teacherRole === 'head_teacher' ? '班主任' : '任课教师' }}</span></div><div v-if="selected.canManageStudents" class="invite-box"><strong>班级邀请码</strong><code>{{ selected.inviteCode || '尚未生成' }}</code><button class="secondary-button" type="button" @click="rotateInvite"><RotateCw :size="16" />生成/轮换</button></div></section><section><h3>学生名单</h3><div v-if="selected.canManageStudents" class="add-student"><select @change="addStudent(Number($event.target.value)); $event.target.value = ''"><option value="">添加本校学生</option><option v-for="student in candidates" :key="student.studentId" :value="student.studentId">{{ student.studentNo }} · {{ student.studentName }}</option></select></div><ul class="student-list"><li v-for="student in selected.students" :key="student.studentId"><span>{{ student.studentNo }} · {{ student.studentName }}</span><button v-if="selected.canManageStudents" class="text-button" type="button" @click="removeStudent(student.studentId)">移除</button></li><li v-if="!selected.students.length" class="muted">暂无学生</li></ul><div v-if="selected.canManageStudents" class="import-box"><label class="upload-control">选择学生 Excel 文件（首行包含“学号”列）<input type="file" accept=".xlsx" @change="importFileChanged" /></label><button class="secondary-button" type="button" :disabled="!importFile" @click="importStudents">导入学生</button></div></section></div><section class="task-section"><div class="task-heading"><h3>学习任务</h3><span class="badge">进行中 {{ selected.activeTaskCount }} · 已完成 {{ selected.completedTaskCount }} · 逾期 {{ selected.overdueTaskCount }}</span></div><form class="task-form" @submit.prevent="publishTask"><input v-model="taskForm.title" placeholder="任务标题" required /><label>开始时间<input v-model="taskForm.startAt" type="datetime-local" /></label><label>截止时间<input v-model="taskForm.dueAt" type="datetime-local" /></label><label>学生提交形式<select v-model="taskForm.submissionRule"><option value="text_required">文字感想</option><option value="image_required">仅图片</option><option value="document_required">仅 Word 文档</option><option value="image_or_document">图片或 Word 文档</option></select></label><label class="upload-control">上传 Word 任务材料<input type="file" accept=".docx" @change="materialChanged" /></label><textarea v-model="taskForm.description" placeholder="任务说明"></textarea><fieldset v-if="availableResources.length" class="resource-picker"><legend>选择地图资源点（可多选）</legend><label v-for="resource in availableResources" :key="resource.resourceId"><input v-model="selectedResourceIds" type="checkbox" :value="resource.resourceId" />{{ resource.resourceName }}<small>{{ resource.address }}</small></label></fieldset><button class="primary-button" type="submit"><UserPlus :size="17" />发布任务</button></form><div class="task-list"><article v-for="task in selected.tasks" :key="task.taskId" class="task-row"><div><h3>{{ task.title }}</h3><p>{{ task.description || '暂无任务说明' }}</p><small>完成 {{ task.completedCount }}/{{ task.totalCount }}，逾期 {{ task.overdueCount }} · 截止 {{ task.dueAt || '未设置' }}</small></div></article><div v-if="!selected.tasks.length" class="empty-state">暂无学习任务</div></div></section></section>
    </template>
  <section v-if="selected" class="submission-choice-panel" aria-label="学生提交形式">
    <h3>学生提交形式</h3>
    <div class="submission-choice-grid">
      <label v-for="[part, label] in submissionOptions" :key="part" :class="{ selected: submissionChecked(part) }"><input type="checkbox" :checked="submissionChecked(part)" @change="setSubmissionRule(part)" />{{ label }}</label>
    </div>
  </section>
  </AppShell>
</template>

<style scoped>
.class-layout { display: grid; grid-template-columns: minmax(260px, .85fr) minmax(300px, 1fr); gap: 16px; }
.class-list { display: grid; gap: 8px; padding: 12px; }
.class-card { display: grid; gap: 5px; width: 100%; padding: 14px; border: 1px solid var(--line); border-radius: 6px; background: #fff; text-align: left; color: var(--text); }
.class-card:hover, .class-card.active { border-color: var(--green); background: var(--green-soft); }
.class-card span, .class-card small { color: var(--muted); }
.detail-panel, .task-panel { margin-top: 16px; }
.task-form > label:has(select) { display: none; }
.detail-grid { display: grid; grid-template-columns: minmax(220px,.75fr) minmax(300px,1.25fr); gap: 22px; padding: 20px; }
.detail-grid h3, .task-section h3 { margin-bottom: 12px; font-size: 15px; }
.teacher-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.invite-box { display: grid; grid-template-columns: 1fr auto; gap: 10px; align-items: center; margin-top: 22px; padding: 14px; border: 1px solid var(--line); border-radius: 6px; }
.invite-box code { overflow-wrap: anywhere; color: var(--red-dark); font-size: 17px; font-weight: 700; }
.student-list { display: grid; gap: 6px; max-height: 250px; margin: 10px 0; padding: 0; overflow: auto; list-style: none; }
.student-list li { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--line); font-size: 14px; }
.import-box { display: grid; gap: 8px; margin-top: 12px; }
.task-section { padding: 0 20px 20px; }
.task-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.task-form { display: grid; grid-template-columns: 1fr 190px auto; gap: 10px; padding: 14px; border: 1px solid var(--line); border-radius: 6px; }
:global(.page-content) { position: relative; }
.task-form textarea { grid-column: 1 / -1; min-height: 70px; }
.task-form label { display: grid; gap: 5px; color: var(--muted); font-size: 13px; }
.resource-picker { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; margin: 0; padding: 12px; border: 1px solid var(--line); border-radius: 6px; }
.resource-picker legend { padding: 0 4px; color: var(--text); font-weight: 600; }
.resource-picker label { display: flex; align-items: flex-start; gap: 6px; color: var(--text); }
.resource-picker input { width: auto; min-height: auto; margin-top: 3px; }
.resource-picker small { color: var(--muted); }
.task-form select { display: none; }
.task-form > label:has(select) { display: none; }
.task-form input[type="datetime-local"] { min-height: 42px; padding: 0 12px; border-radius: 8px; background: #fff; }
.task-form input[type="file"] { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.task-form .upload-control { display: inline-flex; align-items: center; justify-content: center; min-height: 42px; padding: 0 14px; border: 1px solid var(--green); border-radius: 8px; background: var(--green-soft); color: var(--green); cursor: pointer; font-weight: 700; }
.submission-choice-panel { display: block; position: static; max-width: none; margin: 14px 0 0; padding: 14px 18px; border: 1px solid var(--line); border-radius: 10px; background: #fffdf8; box-shadow: 0 8px 18px rgba(65, 49, 31, .06); }
.task-form .submission-choice-panel { grid-column: 1 / -1; width: 100%; box-sizing: border-box; margin: 2px 0 0; }
.task-form > .primary-button { width: fit-content; min-width: 180px; justify-self: start; padding-inline: 26px; }
.submission-choice-panel h3 { margin: 0 0 12px; color: var(--ink); font-size: 16px; }
.submission-choice-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.submission-choice-grid label { display: flex; align-items: center; gap: 9px; min-height: 48px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; color: var(--muted); cursor: pointer; transition: border-color .2s, background .2s, color .2s; white-space: nowrap; }
.submission-choice-grid label.selected { border-color: var(--green); background: var(--green-soft); color: var(--green); font-weight: 700; }
.submission-choice-grid input { width: 17px; height: 17px; accent-color: var(--green); }
@media (max-width: 700px) { .submission-choice-grid { grid-template-columns: 1fr; } }
.task-list { display: grid; gap: 8px; padding: 16px 20px; }
.task-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px; border: 1px solid var(--line); border-radius: 6px; }
.task-row h3 { margin: 8px 0 5px; font-size: 15px; }.task-row p, .task-row small { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.55; }
.join-panel { margin-bottom: 16px; }.invite-form { display: grid; grid-template-columns: 1fr auto; gap: 10px; }
.student-class-layout { display:grid; grid-template-columns:minmax(250px,.72fr) minmax(0,1.5fr); gap:16px; }.student-detail-body { display:grid; grid-template-columns:1fr 1fr; gap:18px; padding:18px 20px; }.student-detail-body h3,.student-tasks h3,.student-activities h3 { margin:0 0 10px; font-size:15px; }.student-stats { display:grid; grid-template-columns:repeat(2,1fr); gap:8px; }.student-stats span { padding:9px; border:1px solid var(--line); border-radius:6px; color:var(--muted); font-size:13px; }.student-stats strong { display:block; color:var(--text); font-size:18px; }.student-tasks,.student-activities { padding:0 20px 20px; }.task-actions { display:flex; align-items:center; gap:8px; }.activity-list { display:grid; gap:10px; }.activity-list article { display:grid; grid-template-columns:14px 1fr; gap:9px; padding:10px 0; border-bottom:1px solid var(--line); }.activity-dot { width:8px; height:8px; margin-top:6px; border-radius:50%; background:var(--red); }.activity-list p,.activity-list small { margin:4px 0 0; color:var(--muted); font-size:13px; }.class-card em { font-style:normal; color:var(--red); }
@media (max-width: 900px) { .class-layout, .detail-grid, .student-class-layout, .student-detail-body { grid-template-columns: 1fr; } .task-form { grid-template-columns: 1fr; } .task-form textarea { grid-column: auto; } }
</style>
