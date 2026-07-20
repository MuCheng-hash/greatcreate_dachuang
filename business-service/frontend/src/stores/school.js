import { defineStore } from "pinia";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";

export const useSchoolStore = defineStore("school", {
  state: () => ({ detail: null, config: null, loading: false, error: "" }),
  getters: {
    school: (state) => state.detail?.school || null,
    resources: (state) => state.detail?.resources || [],
    activityPlans: (state) => state.detail?.activityPlans || []
  },
  actions: {
    async load(force = false) {
      const auth = useAuthStore();
      if (!auth.user?.schoolId) return null;
      if (this.detail && !force) return this.detail;
      this.loading = true;
      this.error = "";
      try {
        this.detail = await api.get(`/api/school-map/schools/${auth.user.schoolId}/detail`);
        return this.detail;
      } catch (error) {
        this.error = error.message || "学校数据加载失败";
        throw error;
      } finally {
        this.loading = false;
      }
    },
    async loadConfig() {
      if (!this.config) this.config = await api.get("/api/map/client-config");
      return this.config;
    }
  }
});
