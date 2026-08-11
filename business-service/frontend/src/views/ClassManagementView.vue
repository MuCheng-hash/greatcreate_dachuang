<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { CheckCircle2, ClipboardList, KeyRound, Plus, RotateCw, UserPlus, Users } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import LoadingBlock from "@/components/LoadingBlock.vue";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const isStudent = computed(() => auth.user?.roleCode === "student");
const classes = ref([]);
const teachers = ref([]);
const selected = ref(null);
const selectedId = ref(null);
const candidates = ref([]);
const loading = ref(true);
const saving = ref(false);
const notice = reactive({ tone: "", text: "" });
const joinCode = ref("");
const importNumbers = ref("");
const taskForm = reactive({ title: "", description: "", dueAt: "" });
const classForm = reactive({ className: "", gradeName: "", classType: "administrative", headTeacherId: "", subjectTeacherIds: [] });

onMounted(load);

async function load() {
  loading.value = true;
  try {
    if (isStudent.value) {
      classes.value = await api.get("/api/student/class-tasks");
    } else {
      const [items, teacherItems] = await Promise.all([
        api.get("/api/teacher/classes/mine"), api.get("/api/teacher/classes/available-teachers")
      ]);
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

async function selectClass(classId) {
  selectedId.value = classId;
  try {
    selected.value = await api.get(`/api/teacher/classes/${classId}`);
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

async function importStudents() {
  const studentNos = importNumbers.value.split(/[\n,，\s]+/).map(item => item.trim()).filter(Boolean);
  if (!studentNos.length) return;
  try {
    const result = await api.post(`/api/teacher/classes/${selectedId.value}/students/import`, { studentNos });
    notice.tone = result.failedCount ? "info" : "success";
    notice.text = `导入完成：成功 ${result.successCount}，失败 ${result.failedCount}${result.errors?.length ? `（${result.errors.join("；")}）` : ""}`;
    importNumbers.value = ""; await selectClass(selectedId.value); await load();
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
    await api.post(`/api/teacher/classes/${selectedId.value}/tasks`, { ...taskForm, dueAt: taskForm.dueAt ? `${taskForm.dueAt}:00` : null });
    notice.tone = "success"; notice.text = "学习任务已发布"; taskForm.title = ""; taskForm.description = ""; taskForm.dueAt = "";
    await selectClass(selectedId.value); await load();
  } catch (error) { showError(error); }
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
</script>

<template>
  <AppShell :title="isStudent ? '我的学习任务' : '班级管理'" :subtitle="isStudent ? '完成老师发布到班级的学习任务' : '管理负责班级、学生与学习任务'">
    <InlineNotice v-if="notice.text" :tone="notice.tone">{{ notice.text }}</InlineNotice>
    <LoadingBlock v-if="loading" />

    <template v-else-if="isStudent">
      <section class="page-panel join-panel"><div class="panel-header"><div><h2>加入班级</h2><p>输入班主任提供的邀请码</p></div><KeyRound :size="20" /></div><form class="panel-body invite-form" @submit.prevent="joinByInvite"><input v-model="joinCode" placeholder="班级邀请码" /><button class="primary-button" type="submit">加入</button></form></section>
      <section class="page-panel task-panel"><div class="panel-header"><div><h2>学习任务</h2><p>按截止时间排序</p></div><ClipboardList :size="20" /></div><div v-if="classes.length" class="task-list"><article v-for="task in classes" :key="task.taskId" class="task-row"><div><span class="badge" :class="task.studentStatus === 'completed' ? 'badge-green' : task.studentStatus === 'overdue' ? 'badge-red' : ''">{{ taskStatus(task) }}</span><h3>{{ task.title }}</h3><p>{{ task.description || '暂无任务说明' }}</p><small>截止：{{ task.dueAt || '未设置' }}</small></div><button v-if="task.studentStatus !== 'completed'" class="primary-button" type="button" @click="completeTask(task.taskId)"><CheckCircle2 :size="17" />完成</button></article></div><div v-else class="empty-state">暂无学习任务</div></section>
    </template>

    <template v-else>
      <div class="class-layout">
        <section class="page-panel class-list-panel"><div class="panel-header"><div><h2>我的班级</h2><p>{{ classes.length }} 个可访问班级</p></div><Users :size="20" /></div><div class="class-list"><button v-for="item in classes" :key="item.classId" type="button" class="class-card" :class="{ active: selectedId === item.classId }" @click="selectClass(item.classId)"><strong>{{ item.className }}</strong><span>{{ item.gradeName || '未设置年级' }} · {{ classType(item.classType) }}</span><small>{{ item.studentCount }} 名学生 · 完成率 {{ item.completionRate }}%</small></button><div v-if="!classes.length" class="empty-state">暂无负责班级</div></div></section>
        <section class="page-panel create-panel"><div class="panel-header"><div><h2>创建班级</h2><p>可指定本校班主任与任课教师</p></div><Plus :size="20" /></div><form class="panel-body form-stack" @submit.prevent="createClass"><label>班级名称<input v-model="classForm.className" required /></label><label>年级<input v-model="classForm.gradeName" /></label><label>班级类型<select v-model="classForm.classType"><option value="administrative">行政班</option><option value="teaching">教学班</option></select></label><label>班主任<select v-model="classForm.headTeacherId"><option value="">不设置</option><option v-for="teacher in teachers" :key="teacher.teacherId" :value="teacher.teacherId">{{ teacher.teacherName }}</option></select></label><label>任课教师<select v-model="classForm.subjectTeacherIds" multiple><option v-for="teacher in teachers" :key="teacher.teacherId" :value="teacher.teacherId">{{ teacher.teacherName }}</option></select></label><button class="primary-button full-button" :disabled="saving" type="submit">创建班级</button></form></section>
      </div>

      <section v-if="selected" class="page-panel detail-panel"><div class="panel-header"><div><h2>{{ selected.className }}</h2><p>{{ selected.gradeName || '未设置年级' }} · {{ classType(selected.classType) }} · {{ selected.studentCount }} 名学生</p></div><span class="badge" :class="selected.headTeacher ? 'badge-green' : ''">{{ selected.headTeacher ? '班主任' : '任课教师' }}</span></div><div class="detail-grid"><section><h3>任课信息</h3><div class="teacher-tags"><span v-for="teacher in selected.teachers" :key="teacher.teacherId" class="badge">{{ teacher.teacherName }} · {{ teacher.teacherRole === 'head_teacher' ? '班主任' : '任课教师' }}</span></div><div v-if="selected.canManageStudents" class="invite-box"><strong>班级邀请码</strong><code>{{ selected.inviteCode || '尚未生成' }}</code><button class="secondary-button" type="button" @click="rotateInvite"><RotateCw :size="16" />生成/轮换</button></div></section><section><h3>学生名单</h3><div v-if="selected.canManageStudents" class="add-student"><select @change="addStudent(Number($event.target.value)); $event.target.value = ''"><option value="">添加本校学生</option><option v-for="student in candidates" :key="student.studentId" :value="student.studentId">{{ student.studentNo }} · {{ student.studentName }}</option></select></div><ul class="student-list"><li v-for="student in selected.students" :key="student.studentId"><span>{{ student.studentNo }} · {{ student.studentName }}</span><button v-if="selected.canManageStudents" class="text-button" type="button" @click="removeStudent(student.studentId)">移除</button></li><li v-if="!selected.students.length" class="muted">暂无学生</li></ul><div v-if="selected.canManageStudents" class="import-box"><textarea v-model="importNumbers" placeholder="输入学号，使用换行、空格或逗号分隔"></textarea><button class="secondary-button" type="button" @click="importStudents">批量导入</button></div></section></div><section class="task-section"><div class="task-heading"><h3>学习任务</h3><span class="badge">进行中 {{ selected.activeTaskCount }} · 已完成 {{ selected.completedTaskCount }} · 逾期 {{ selected.overdueTaskCount }}</span></div><form class="task-form" @submit.prevent="publishTask"><input v-model="taskForm.title" placeholder="任务标题" required /><input v-model="taskForm.dueAt" type="datetime-local" /><textarea v-model="taskForm.description" placeholder="任务说明"></textarea><button class="primary-button" type="submit"><UserPlus :size="17" />发布任务</button></form><div class="task-list"><article v-for="task in selected.tasks" :key="task.taskId" class="task-row"><div><h3>{{ task.title }}</h3><p>{{ task.description || '暂无任务说明' }}</p><small>完成 {{ task.completedCount }}/{{ task.totalCount }}，逾期 {{ task.overdueCount }} · 截止 {{ task.dueAt || '未设置' }}</small></div></article><div v-if="!selected.tasks.length" class="empty-state">暂无学习任务</div></div></section></section>
    </template>
  </AppShell>
</template>

<style scoped>
.class-layout { display: grid; grid-template-columns: minmax(260px, .85fr) minmax(300px, 1fr); gap: 16px; }
.class-list { display: grid; gap: 8px; padding: 12px; }
.class-card { display: grid; gap: 5px; width: 100%; padding: 14px; border: 1px solid var(--line); border-radius: 6px; background: #fff; text-align: left; color: var(--text); }
.class-card:hover, .class-card.active { border-color: var(--green); background: var(--green-soft); }
.class-card span, .class-card small { color: var(--muted); }
.detail-panel, .task-panel { margin-top: 16px; }
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
.task-form textarea { grid-column: 1 / -1; min-height: 70px; }
.task-list { display: grid; gap: 8px; padding: 16px 20px; }
.task-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px; border: 1px solid var(--line); border-radius: 6px; }
.task-row h3 { margin: 8px 0 5px; font-size: 15px; }.task-row p, .task-row small { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.55; }
.join-panel { margin-bottom: 16px; }.invite-form { display: grid; grid-template-columns: 1fr auto; gap: 10px; }
@media (max-width: 900px) { .class-layout, .detail-grid { grid-template-columns: 1fr; } .task-form { grid-template-columns: 1fr; } .task-form textarea { grid-column: auto; } }
</style>
