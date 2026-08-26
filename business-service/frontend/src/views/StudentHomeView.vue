<script setup>
import { onMounted, ref } from "vue";
import { Bot, ChevronRight, ClipboardList, Compass, Map, MapPin, RefreshCw, Users } from "@lucide/vue";
import AppShell from "@/components/AppShell.vue";
import LoadingBlock from "@/components/LoadingBlock.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";
const auth = useAuthStore(); const data = ref(null); const loading = ref(true); const error = ref("");
async function load() { loading.value = true; error.value = ""; try { data.value = await api.get("/api/student/home"); } catch (e) { error.value = e.message || "首页加载失败"; } finally { loading.value = false; } }
function date(value) { return value ? String(value).replace("T", " ").slice(0, 16) : "未设置"; }
function status(value) { return value === "overdue" ? "已逾期" : "待完成"; }
onMounted(load);
</script>
<template>
  <AppShell title="学生学习首页" subtitle="从班级任务出发，发现身边的红色文化资源">
    <LoadingBlock v-if="loading" />
    <template v-else-if="error"><InlineNotice tone="error">{{ error }}</InlineNotice><button class="primary-button" type="button" @click="load"><RefreshCw :size="16" />重新加载</button></template>
    <template v-else>
      <section class="student-welcome"><div><span class="eyebrow">{{ auth.schoolLabel }}</span><h2>你好，{{ data?.student?.studentName || auth.user?.displayName || "同学" }}</h2><p>{{ data?.student?.gradeName || "继续完成今天的学习探索" }} · 学号 {{ data?.student?.studentNo || "-" }}</p></div><div class="welcome-mark">学</div></section>
      <section class="student-stats"><article><ClipboardList :size="20" /><strong>{{ data.summary.pendingTaskCount }}</strong><span>待完成任务</span></article><article><Users :size="20" /><strong>{{ data.summary.classCount }}</strong><span>所在班级</span></article><article><Compass :size="20" /><strong>{{ data.summary.recentResourceCount }}</strong><span>最近浏览</span></article></section>
      <div class="student-grid">
        <section class="page-panel student-panel"><div class="panel-header"><div><h2>我的班级</h2><p>当前加入的学习班级</p></div><Users :size="20" /></div><div class="panel-list"><RouterLink v-for="item in data.classes" :key="item.classId" class="student-row" to="/classes"><span><strong>{{ item.className }}</strong><small>{{ item.gradeName || "未设置年级" }}<em v-if="item.primary"> · 主班级</em></small></span><ChevronRight :size="17" /></RouterLink><div v-if="!data.classes.length" class="empty-state">暂未加入班级<RouterLink to="/classes">去查看班级</RouterLink></div></div></section>
        <section class="page-panel student-panel"><div class="panel-header"><div><h2>待完成任务</h2><p>按截止时间优先显示</p></div><RouterLink class="text-button" to="/tasks">查看全部</RouterLink></div><div class="panel-list"><RouterLink v-for="task in data.pendingTasks" :key="task.taskId" class="student-row" :to="`/tasks?taskId=${task.taskId}`"><span><strong>{{ task.title }}</strong><small><b :class="task.studentStatus === 'overdue' ? 'overdue' : ''">{{ status(task.studentStatus) }}</b> · 截止 {{ date(task.dueAt) }}</small></span><ChevronRight :size="17" /></RouterLink><div v-if="!data.pendingTasks.length" class="empty-state">暂无待完成任务</div></div></section>
        <section class="page-panel student-panel entry-panel"><div class="panel-header"><div><h2>学习入口</h2><p>开始你的学习探索</p></div></div><div class="entry-list"><RouterLink to="/assistant" class="entry-card assistant-entry"><Bot :size="22" /><span><strong>智能问答</strong><small>向 AI 提问，获取学习思路</small></span><ChevronRight :size="17" /></RouterLink><RouterLink to="/map" class="entry-card map-entry"><Map :size="22" /><span><strong>智慧地图</strong><small>探索学校周边教育资源</small></span><ChevronRight :size="17" /></RouterLink></div></section>
      </div>
      <section class="page-panel recent-panel"><div class="panel-header"><div><h2>最近浏览资源</h2><p>继续了解你感兴趣的本地资源</p></div><RouterLink class="text-button" to="/resource-discovery">查看全部</RouterLink></div><div class="recent-grid"><RouterLink v-for="item in data.recentResources" :key="item.resourceId" class="recent-card" :to="`/resource-discovery?resourceId=${item.resourceId}`"><span class="resource-icon"><MapPin :size="17" /></span><strong>{{ item.resourceName }}</strong><small>{{ item.resourceCategory || "思政资源" }} · {{ date(item.viewedAt) }}</small><p>{{ item.address || "地址待完善" }}</p></RouterLink><div v-if="!data.recentResources.length" class="empty-state">浏览资源后会出现在这里</div></div></section>
    </template>
  </AppShell>
</template>
<style scoped>
.student-welcome {
  position: relative; overflow: hidden; display: flex; justify-content: space-between; align-items: center;
  padding: 26px 30px; border: 1px solid var(--line); border-radius: 14px;
  background:
    radial-gradient(420px 220px at 90% -20%, rgba(169,125,47,.16), transparent 60%),
    linear-gradient(120deg, #eaf1e9, #f8f3e7);
  box-shadow: var(--shadow-sm);
}
.student-welcome::after {
  content: ""; position: absolute; inset: 0; pointer-events: none; opacity: .4;
  background-image: radial-gradient(rgba(159,58,46,.12) 1px, transparent 1.2px);
  background-size: 26px 26px;
}
.student-welcome > div { position: relative; z-index: 1; }
.student-welcome .eyebrow { color: var(--red); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.student-welcome h2 { margin: 8px 0 6px; font-size: 30px; letter-spacing: .01em; }
.student-welcome p { margin: 0; color: var(--muted); font-size: 13px; }
.welcome-mark {
  position: relative; z-index: 1; display: grid; place-items: center; width: 66px; height: 66px;
  border-radius: 16px; background: linear-gradient(145deg, #b04436, #8a2f24); color: #fff;
  font-size: 26px; font-weight: 800; font-family: var(--font-display);
  box-shadow: 0 10px 24px rgba(159,58,46,.32), inset 0 1px 0 rgba(255,255,255,.22);
}
.student-stats { display: grid; grid-template-columns: repeat(3,1fr); gap: 14px; margin: 18px 0; }
.student-stats article {
  display: grid; grid-template-columns: auto 1fr; gap: 2px 12px; padding: 18px;
  background: var(--surface); border: 1px solid var(--line); border-radius: 12px; box-shadow: var(--shadow-sm);
  transition: transform 160ms ease, box-shadow 160ms ease;
}
.student-stats article:hover { transform: translateY(-2px); box-shadow: var(--shadow); }
.student-stats svg {
  grid-row: span 2; align-self: center; width: 38px; height: 38px; padding: 8px;
  border-radius: 10px; background: var(--red-soft); color: var(--red);
}
.student-stats strong { font-size: 24px; font-family: var(--font-display); }
.student-stats span { color: var(--muted); font-size: 12px; }
.student-grid { display: grid; grid-template-columns: 1fr 1.2fr .9fr; gap: 16px; }
.student-panel { min-height: 260px; }
.panel-list, .entry-list { display: grid; gap: 8px; padding: 14px; }
.student-row, .entry-card {
  display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 13px 14px;
  border: 1px solid var(--line); border-radius: 10px; background: #fff; color: inherit; text-decoration: none;
  transition: border-color 150ms ease, background 150ms ease, transform 150ms ease, box-shadow 150ms ease;
}
.student-row:hover, .entry-card:hover { border-color: var(--green); background: var(--green-soft); transform: translateX(3px); }
.student-row > svg, .entry-card > svg { color: #9aa79d; transition: color 150ms ease, transform 150ms ease; }
.student-row:hover > svg, .entry-card:hover > svg { color: var(--red); transform: translateX(3px); }
.student-row span, .entry-card span { display: grid; gap: 4px; }
.student-row strong, .entry-card strong { font-size: 14px; }
.student-row small, .entry-card small, .recent-card small { color: var(--muted); font-size: 12px; }
.student-row em { font-style: normal; color: var(--red); }
.overdue { color: var(--red); font-weight: 700; }
.entry-card { color: #fff; border: 0; box-shadow: 0 10px 24px rgba(31,45,36,.16); }
.entry-card small { color: rgba(255,255,255,.82); }
.assistant-entry { background: linear-gradient(145deg, #a34a3b, #8a3529); }
.map-entry { background: linear-gradient(145deg, #3f7360, #2c5e46); }
.entry-card:hover { transform: translateY(-3px); filter: brightness(1.05); }
.recent-panel { margin-top: 18px; }
.recent-grid { display: grid; grid-template-columns: repeat(5,1fr); gap: 12px; padding: 16px; }
.recent-card {
  display: grid; gap: 7px; padding: 15px; border: 1px solid var(--line); border-radius: 12px;
  background: #fff; color: inherit; text-decoration: none; box-shadow: var(--shadow-sm);
  transition: transform 160ms ease, box-shadow 160ms ease, border-color 160ms ease;
}
.recent-card:hover { transform: translateY(-3px); border-color: #cdc7b8; box-shadow: var(--shadow); }
.recent-card strong { font-size: 14px; line-height: 1.45; }
.resource-icon {
  display: grid; place-items: center; width: 32px; height: 32px; border-radius: 10px;
  background: var(--green-soft); color: var(--green);
}
.recent-card p { margin: 0; color: var(--muted); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.empty-state a { display: block; margin-top: 8px; color: var(--red); }
@media (max-width: 1050px) {
  .student-grid { grid-template-columns: 1fr 1fr; }
  .entry-panel { grid-column: 1/-1; }
  .recent-grid { grid-template-columns: repeat(3,1fr); }
}
@media (max-width: 700px) {
  .student-welcome { padding: 20px; }
  .student-welcome h2 { font-size: 24px; }
  .welcome-mark { width: 50px; height: 50px; font-size: 20px; border-radius: 12px; }
  .student-stats { gap: 9px; }
  .student-stats article { padding: 13px; }
  .student-stats strong { font-size: 19px; }
  .student-grid, .recent-grid { grid-template-columns: 1fr; }
  .entry-panel { grid-column: auto; }
}
</style>
