import { createRouter, createWebHistory, type RouteLocationNormalized } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const routes = [
  { path: "/", redirect: "/login" },
  { path: "/login", name: "login", component: () => import("@/views/LoginView.vue"), meta: { public: true } },
  { path: "/register", name: "register", component: () => import("@/views/RegisterView.vue"), meta: { public: true } },
  { path: "/map", name: "map", component: () => import("@/views/MapView.vue"), meta: { title: "地图资源" } },
  { path: "/teaching-plans", name: "teaching-plans", component: () => import("@/views/TeachingPlansView.vue"), meta: { title: "教学方案" } },
  { path: "/assistant", name: "assistant", component: () => import("@/views/AssistantView.vue"), meta: { title: "智能问答" } },
  { path: "/agent-debug", name: "agent-debug", component: () => import("@/views/AgentDebugView.vue"), meta: { title: "Agent 调试" } },
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
}

type AccessResult = true | string | { external: string } | { path: string; query: { redirect: string } };

export function resolveRouteAccess(to: Pick<RouteLocationNormalized, "meta" | "fullPath">, auth: RouteAuthState): AccessResult {
  if (to.meta.admin && !auth.isAdmin) {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
  if (auth.isAdmin && !to.meta.admin && !to.meta.public) {
    return { external: "/admin.html" };
  }
  if (to.meta.public && auth.isAuthenticated) return "/map";
  if (!to.meta.public && !auth.isAuthenticated) {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
  return true;
}

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  await auth.ensureLoaded();
  const access = resolveRouteAccess(to, auth);
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
