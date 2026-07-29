import { defineStore } from "pinia";
import { api } from "@/services/api";
import { useAuthStore } from "@/stores/auth";

export interface SchoolSummary {
  schoolId: number;
  schoolName: string;
  schoolCode?: string;
  schoolLevel?: string;
  schoolType?: string;
  address?: string;
  longitude?: number | string;
  latitude?: number | string;
}

export interface SchoolResourceRelation {
  resourceId: number;
  educationThemeSummary?: string;
  resource?: Record<string, any> & { resourceName?: string; longitude?: number | string; latitude?: number | string };
}

export interface DiscoveryCandidate {
  candidateId: number;
  longitude?: number | string;
  latitude?: number | string;
  analysisStatus?: string;
  [key: string]: any;
}

export interface DiscoveryRun {
  runId: number;
  status: string;
  errorMessage?: string;
  candidates?: DiscoveryCandidate[];
}

export interface RedCultureSiteMarker {
  id: string;
  name: string;
  category?: string;
  address?: string;
  district?: string;
  longitude: number | string;
  latitude: number | string;
  summary?: string;
}

interface SchoolDetail {
  school: SchoolSummary;
  resources?: SchoolResourceRelation[];
  activityPlans?: Array<Record<string, any>>;
}

interface SchoolState {
  detail: SchoolDetail | null;
  config: Record<string, any> | null;
  loading: boolean;
  error: string;
  discoveryRun: DiscoveryRun | null;
  discoveryLoading: boolean;
  discoveryError: string;
  discoveryRadiusKm: number;
  redCultureSites: RedCultureSiteMarker[];
  redCultureError: string;
}

function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export const useSchoolStore = defineStore("school", {
  state: (): SchoolState => ({
    detail: null, config: null, loading: false, error: "",
    discoveryRun: null, discoveryLoading: false, discoveryError: "", discoveryRadiusKm: 5,
    redCultureSites: [], redCultureError: ""
  }),
  getters: {
    school: (state): SchoolSummary | null => state.detail?.school || null,
    resources: (state): SchoolResourceRelation[] => state.detail?.resources || [],
    activityPlans: (state): Array<Record<string, any>> => state.detail?.activityPlans || [],
    discoveryCandidates: (state): DiscoveryCandidate[] => state.discoveryRun?.candidates || []
  },
  actions: {
    async loadRedCultureSites(): Promise<RedCultureSiteMarker[]> {
      this.redCultureError = "";
      try {
        this.redCultureSites = await api.get<RedCultureSiteMarker[]>("/api/map/red-culture/sites");
        return this.redCultureSites;
      } catch (error) {
        this.redCultureError = messageOf(error, "红色文化图谱资源加载失败");
        return [];
      }
    },
    async loadRedCultureSite(siteId: string): Promise<Record<string, any>> {
      return api.get(`/api/map/red-culture/sites/${encodeURIComponent(siteId)}`);
    },
    async load(force = false): Promise<SchoolDetail | null> {
      const auth = useAuthStore();
      if (!auth.user?.schoolId) return null;
      if (this.detail && !force) return this.detail;
      this.loading = true;
      this.error = "";
      try {
        this.detail = await api.get<SchoolDetail>(`/api/school-map/schools/${auth.user.schoolId}/detail`);
        return this.detail;
      } catch (error) {
        this.error = messageOf(error, "学校数据加载失败");
        throw error;
      } finally {
        this.loading = false;
      }
    },
    async loadConfig(): Promise<Record<string, any>> {
      if (!this.config) this.config = await api.get<Record<string, any>>("/api/map/client-config");
      return this.config;
    },
    async startDiscovery(radiusKm = 5): Promise<DiscoveryRun | null> {
      const auth = useAuthStore();
      if (!auth.user?.schoolId) return null;
      this.discoveryRadiusKm = radiusKm;
      this.discoveryLoading = true;
      this.discoveryError = "";
      try {
        this.discoveryRun = await api.post<DiscoveryRun>(`/api/school-map/schools/${auth.user.schoolId}/discovery-runs`, { radiusKm });
        return this.discoveryRun;
      } catch (error) {
        this.discoveryError = messageOf(error, "周边场所发现失败");
        throw error;
      } finally {
        this.discoveryLoading = false;
      }
    },
    async loadDiscoveryRun(runId: number): Promise<DiscoveryRun | null> {
      const auth = useAuthStore();
      if (!auth.user?.schoolId || !runId) return null;
      this.discoveryRun = await api.get<DiscoveryRun>(`/api/school-map/schools/${auth.user.schoolId}/discovery-runs/${runId}`);
      if (this.discoveryRun?.status === "failed") this.discoveryError = this.discoveryRun.errorMessage || "周边场所发现失败";
      return this.discoveryRun;
    },
    async loadCandidate(candidateId: number): Promise<Record<string, any>> {
      const auth = useAuthStore();
      if (!auth.user?.schoolId) throw new Error("school account is required");
      return api.get(`/api/school-map/schools/${auth.user.schoolId}/discovery-candidates/${candidateId}`);
    },
    async loadApprovedResource(resourceId: number): Promise<Record<string, any>> {
      const auth = useAuthStore();
      if (!auth.user?.schoolId) throw new Error("school account is required");
      return api.get(`/api/school-map/schools/${auth.user.schoolId}/resources/${resourceId}`);
    }
  }
});
