import { createRouter, createWebHistory } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const routes = [
  { path: "/", redirect: "/login" },
  { path: "/login", name: "login", component: () => import("@/views/LoginView.vue"), meta: { public: true } },
  { path: "/register", name: "register", component: () => import("@/views/RegisterView.vue"), meta: { public: true } },
  { path: "/map", name: "map", component: () => import("@/views/MapView.vue"), meta: { title: "地图资源" } },
  { path: "/teaching-plans", name: "teaching-plans", component: () => import("@/views/TeachingPlansView.vue"), meta: { title: "教学方案" } },
  { path: "/assistant", name: "assistant", component: () => import("@/views/AssistantView.vue"), meta: { title: "智能问答" } },
  { path: "/profile", name: "profile", component: () => import("@/views/ProfileView.vue"), meta: { title: "个人中心" } },
  { path: "/:pathMatch(.*)*", redirect: "/login" }
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
});

export function resolveRouteAccess(to, auth) {
  if (auth.isAdmin) return { external: "/admin.html" };
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
  if (access?.external) {
    window.location.assign(access.external);
    return false;
  }
  if (access !== true) return access;
  document.title = to.meta.title ? `${to.meta.title} | 乡村学校思政资源工作台` : "乡村学校思政资源工作台";
  return true;
});

window.addEventListener("portal:unauthorized", () => {
  const auth = useAuthStore();
  auth.clear();
  if (router.currentRoute.value.name !== "login") router.replace("/login");
});

export default router;
