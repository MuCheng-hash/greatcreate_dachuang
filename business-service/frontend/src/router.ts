import { createRouter, createWebHistory, type RouteLocationNormalized } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const routes = [
  { path: "/", name: "project-home", component: () => import("@/views/ProjectHomeView.vue"), meta: { title: "红启乡智", public: true, landing: true } },
  { path: "/login", name: "login", component: () => import("@/views/LoginView.vue"), meta: { public: true } },
  { path: "/register", name: "register", component: () => import("@/views/RegisterView.vue"), meta: { public: true } },
  { path: "/student", name: "student-root", component: () => import("@/views/StudentHomeView.vue"), meta: { title: "学生首页", student: true } },
  { path: "/student/home", name: "student-home-prefixed", component: () => import("@/views/StudentHomeView.vue"), meta: { title: "学生首页", student: true } },
  { path: "/student/learning-footprint", name: "student-learning-footprint", component: () => import("@/views/LearningFootprintView.vue"), meta: { title: "学习足迹", student: true } },
  { path: "/student/tasks", name: "student-tasks", component: () => import("@/views/TaskCenterView.vue"), meta: { title: "学习任务", student: true } },
  { path: "/student/resource-discovery", name: "student-resource-discovery", component: () => import("@/views/ResourceDiscoveryView.vue"), meta: { title: "资源发现", student: true } },
  { path: "/student/assistant", name: "student-assistant", component: () => import("@/views/AssistantView.vue"), meta: { title: "智能问答", student: true } },
  { path: "/student/map", name: "student-map", component: () => import("@/views/MapView.vue"), meta: { title: "地图资源", student: true } },
  { path: "/student/profile", name: "student-profile", component: () => import("@/views/ProfileView.vue"), meta: { title: "个人中心", student: true } },
  { path: "/teacher", name: "teacher-root", component: () => import("@/views/MapView.vue"), meta: { title: "地图资源", teacher: true } },
  { path: "/teacher/map", name: "teacher-map", component: () => import("@/views/MapView.vue"), meta: { title: "地图资源", teacher: true } },
  { path: "/teacher/teaching-plans", name: "teacher-teaching-plans", component: () => import("@/views/TeachingPlansView.vue"), meta: { title: "教学方案", teacher: true } },
  { path: "/teacher/classes", name: "teacher-classes", component: () => import("@/views/ClassManagementView.vue"), meta: { title: "班级管理", teacher: true } },
  { path: "/teacher/tasks", name: "teacher-tasks", component: () => import("@/views/TaskCenterView.vue"), meta: { title: "学习任务", teacher: true } },
  { path: "/teacher/resource-discovery", name: "teacher-resource-discovery", component: () => import("@/views/ResourceDiscoveryView.vue"), meta: { title: "资源发现", teacher: true } },
  { path: "/teacher/assistant", name: "teacher-assistant", component: () => import("@/views/AssistantView.vue"), meta: { title: "智能问答", teacher: true } },
  { path: "/teacher/profile", name: "teacher-profile", component: () => import("@/views/ProfileView.vue"), meta: { title: "个人中心", teacher: true } },
  { path: "/map", name: "map", component: () => import("@/views/MapView.vue"), meta: { title: "地图资源" } },
  { path: "/student-home", name: "student-home", component: () => import("@/views/StudentHomeView.vue"), meta: { title: "学生首页", student: true } },
  { path: "/learning-footprint", name: "learning-footprint", component: () => import("@/views/LearningFootprintView.vue"), meta: { title: "学习足迹", student: true } },
  { path: "/teaching-plans", name: "teaching-plans", component: () => import("@/views/TeachingPlansView.vue"), meta: { title: "教学方案" } },
  { path: "/classes", name: "classes", component: () => import("@/views/ClassManagementView.vue"), meta: { title: "班级管理" } },
  { path: "/tasks", name: "tasks", component: () => import("@/views/TaskCenterView.vue"), meta: { title: "学习任务" } },
  { path: "/resource-discovery", name: "resource-discovery", component: () => import("@/views/ResourceDiscoveryView.vue"), meta: { title: "资源发现" } },
  { path: "/assistant", name: "assistant", component: () => import("@/views/AssistantView.vue"), meta: { title: "智能问答" } },
  { path: "/profile", name: "profile", component: () => import("@/views/ProfileView.vue"), meta: { title: "个人中心" } },
  // ---- 管理后台页面 ----
  {
    path: "/admin/knowledge-base",
    name: "admin-knowledge-base",
    component: () => import("@/views/admin/KnowledgeBaseView.vue"),
    meta: { title: "知识库管理", admin: true },
  },
  {
    path: "/admin/conversations",
    name: "admin-conversations",
    component: () => import("@/views/admin/ConversationHistoryView.vue"),
    meta: { title: "会话历史", admin: true },
  },
  {
    path: "/admin/segments",
    name: "admin-segments",
    component: () => import("@/views/admin/SegmentPreviewView.vue"),
    meta: { title: "分段预览", admin: true },
  },
  {
    path: "/admin/logs",
    name: "admin-logs",
    component: () => import("@/views/admin/LogDashboardView.vue"),
    meta: { title: "日志面板", admin: true },
  },
  { path: "/:pathMatch(.*)*", redirect: "/login" }
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
});

interface RouteAuthState {
  isAdmin: boolean;
  isAuthenticated: boolean;
  isStudent?: boolean;
  isTeacher?: boolean;
}

type AccessResult = true | string | { external: string } | { path: string; query: { redirect: string } };

export function resolveRouteAccess(to: Pick<RouteLocationNormalized, "meta" | "fullPath">, auth: RouteAuthState): AccessResult {
  if (to.meta.admin && !auth.isAdmin) {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
  if (to.meta.student && !auth.isStudent) return auth.isAuthenticated ? "/teacher/map" : { path: "/login", query: { redirect: to.fullPath } };
  if (to.meta.teacher && !auth.isTeacher) return auth.isAuthenticated ? (auth.isAdmin ? { external: "/admin.html" } : "/student/home") : { path: "/login", query: { redirect: to.fullPath } };
  if (to.meta.landing && auth.isAuthenticated) {
    if (auth.isAdmin) return { external: "/admin.html" };
    return auth.isStudent ? "/student/home" : "/teacher/map";
  }
  if (auth.isAdmin && !to.meta.admin && !to.meta.public) {
    return { external: "/admin.html" };
  }
  if (to.meta.public && auth.isAuthenticated) return auth.isStudent ? "/student/home" : "/teacher/map";
  if (!to.meta.public && !auth.isAuthenticated) {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
  return true;
}

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  await auth.ensureLoaded();
  const access = resolveRouteAccess(to, { isAdmin: auth.isAdmin, isAuthenticated: auth.isAuthenticated, isStudent: auth.user?.roleCode === "student", isTeacher: auth.isAuthenticated && !auth.isAdmin && auth.user?.roleCode !== "student" });
  if (typeof access === "object" && "external" in access) {
    window.location.assign(access.external);
    return false;
  }
  if (access !== true) return access;
  document.title = to.meta.title ? `${String(to.meta.title)} | 乡村学校思政资源工作台` : "乡村学校思政资源工作台";
  return true;
});

window.addEventListener("portal:unauthorized", () => {
  const auth = useAuthStore();
  auth.clear();
  if (router.currentRoute.value.name !== "login") void router.replace("/login");
});

window.addEventListener("app:unauthorized", () => {
  const auth = useAuthStore();
  auth.clear();
  if (router.currentRoute.value.name !== "login") void router.replace("/login");
});

export default router;
