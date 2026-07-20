import { defineStore } from "pinia";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";

export const useSchoolStore = defineStore("school", {
  state: () => ({
    detail: null, config: null, loading: false, error: "",
    discoveryRun: null, discoveryLoading: false, discoveryError: "", discoveryRadiusKm: 5
  }),
  getters: {
    school: (state) => state.detail?.school || null,
    resources: (state) => state.detail?.resources || [],
    activityPlans: (state) => state.detail?.activityPlans || [],
    discoveryCandidates: (state) => state.discoveryRun?.candidates || []
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
    },
    async startDiscovery(radiusKm = 5) {
      const auth = useAuthStore();
      if (!auth.user?.schoolId) return null;
      this.discoveryRadiusKm = radiusKm;
      this.discoveryLoading = true;
      this.discoveryError = "";
      try {
        this.discoveryRun = await api.post(
          `/api/school-map/schools/${auth.user.schoolId}/discovery-runs`,
          { radiusKm }
        );
        return this.discoveryRun;
      } catch (error) {
        this.discoveryError = error.message || "周边场所发现失败";
        throw error;
      } finally {
        this.discoveryLoading = false;
      }
    },
    async loadDiscoveryRun(runId) {
      const auth = useAuthStore();
      if (!auth.user?.schoolId || !runId) return null;
      this.discoveryRun = await api.get(
        `/api/school-map/schools/${auth.user.schoolId}/discovery-runs/${runId}`
      );
      if (this.discoveryRun?.status === "failed") {
        this.discoveryError = this.discoveryRun.errorMessage || "周边场所发现失败";
      }
      return this.discoveryRun;
    },
    async loadCandidate(candidateId) {
      const auth = useAuthStore();
      return api.get(
        `/api/school-map/schools/${auth.user.schoolId}/discovery-candidates/${candidateId}`
      );
    },
    async loadApprovedResource(resourceId) {
      const auth = useAuthStore();
      return api.get(
        `/api/school-map/schools/${auth.user.schoolId}/resources/${resourceId}`
      );
    }
  }
});
