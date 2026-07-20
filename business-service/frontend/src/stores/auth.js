import { defineStore } from "pinia";
import { api } from "@/services/api";

export const useAuthStore = defineStore("auth", {
  state: () => ({
    user: null,
    initialized: false,
    loading: false
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.user?.accountId),
    isAdmin: (state) => state.user?.roleCode === "platform_admin",
    schoolLabel: (state) => state.user?.schoolName || state.user?.displayName || state.user?.username || "学校账号"
  },
  actions: {
    async ensureLoaded(force = false) {
      if (this.initialized && !force) return this.user;
      this.loading = true;
      try {
        this.user = await api.get("/api/auth/me");
      } catch {
        this.user = null;
      } finally {
        this.loading = false;
        this.initialized = true;
      }
      return this.user;
    },
    async login(credentials) {
      this.loading = true;
      try {
        this.user = await api.post("/api/auth/login", credentials);
        this.initialized = true;
        return this.user;
      } finally {
        this.loading = false;
      }
    },
    async logout() {
      try {
        await api.post("/api/auth/logout", {});
      } finally {
        this.user = null;
        this.initialized = true;
      }
    },
    setUser(user) {
      this.user = user;
      this.initialized = true;
    },
    clear() {
      this.user = null;
      this.initialized = true;
    }
  }
});
