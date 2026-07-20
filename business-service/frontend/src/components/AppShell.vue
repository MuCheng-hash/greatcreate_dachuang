<script setup>
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Bot, LogOut, Map, NotebookPen, UserRound } from "@lucide/vue";
import { useAuthStore } from "@/stores/auth";

defineProps({ title: { type: String, required: true }, subtitle: { type: String, default: "" } });

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const navItems = [
  { to: "/map", label: "地图资源", icon: Map },
  { to: "/teaching-plans", label: "教学方案", icon: NotebookPen },
  { to: "/assistant", label: "智能问答", icon: Bot },
  { to: "/profile", label: "个人中心", icon: UserRound }
];
const initials = computed(() => auth.schoolLabel.slice(0, 1));

async function logout() {
  try {
    await auth.logout();
  } finally {
    await router.replace("/login");
  }
}
</script>

<template>
  <div class="app-layout">
    <aside class="app-sidebar">
      <RouterLink class="side-brand" to="/map" aria-label="乡村学校思政资源工作台">
        <span class="brand-symbol">乡</span>
        <span><strong>思政资源工作台</strong><small>乡村学校教师端</small></span>
      </RouterLink>
      <nav class="side-nav" aria-label="主导航">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" :class="{ active: route.path === item.to }">
          <component :is="item.icon" :size="19" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="side-account">
        <span class="account-avatar">{{ initials }}</span>
        <span><strong>{{ auth.schoolLabel }}</strong><small>{{ auth.user?.displayName || auth.user?.username }}</small></span>
      </div>
    </aside>

    <div class="app-main">
      <header class="app-topbar">
        <div>
          <h1>{{ title }}</h1>
          <p v-if="subtitle">{{ subtitle }}</p>
        </div>
        <div class="topbar-actions">
          <RouterLink class="icon-button" to="/profile" title="个人中心" aria-label="个人中心"><UserRound :size="19" /></RouterLink>
          <button class="icon-button" type="button" title="退出登录" aria-label="退出登录" @click="logout"><LogOut :size="19" /></button>
        </div>
      </header>
      <main class="page-content"><slot /></main>
    </div>

    <nav class="mobile-nav" aria-label="移动端主导航">
      <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" :class="{ active: route.path === item.to }">
        <component :is="item.icon" :size="20" /><span>{{ item.label }}</span>
      </RouterLink>
    </nav>
  </div>
</template>
