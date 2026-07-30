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

export interface RegionCenter {
  longitude?: number | string;
  latitude?: number | string;
}

export interface TownBoundary {
  regionId: number;
  parentRegionId?: number;
  regionName: string;
  regionLevel?: string;
  adcode?: string;
  boundaryGeoJson?: string;
  boundaryStatus?: string;
  center?: RegionCenter;
}

export interface TownMapDetail {
  regionId: number;
  regionName: string;
  regionLevel?: string;
  intro?: string;
  boundaryGeoJson?: string;
  boundaryStatus?: string;
  center?: RegionCenter;
  graphAvailable?: boolean;
  graphStatusMessage?: string;
  markers?: Array<Record<string, any>>;
  heroes?: Array<Record<string, any>>;
  stories?: Array<Record<string, any>>;
  events?: Array<Record<string, any>>;
  suggestedQuestions?: string[];
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
  redCultureSites: RedCultureSiteMarker[];
  redCultureError: string;
  towns: TownBoundary[];
  townDetail: TownMapDetail | null;
  townError: string;
}

function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export const useSchoolStore = defineStore("school", {
  state: (): SchoolState => ({
    detail: null, config: null, loading: false, error: "",
    redCultureSites: [], redCultureError: "",
    towns: [], townDetail: null, townError: ""
  }),
  getters: {
    school: (state): SchoolSummary | null => state.detail?.school || null,
    resources: (state): SchoolResourceRelation[] => state.detail?.resources || [],
    activityPlans: (state): Array<Record<string, any>> => state.detail?.activityPlans || []
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
    async loadTowns(): Promise<TownBoundary[]> {
      this.townError = "";
      try {
        this.towns = await api.get<TownBoundary[]>("/api/map/regions/boundaries?regionLevel=township&ancestorRegionId=1");
        return this.towns;
      } catch (error) {
        this.townError = messageOf(error, "乡镇列表加载失败");
        return [];
      }
    },
    async loadTownDetail(regionId: number): Promise<TownMapDetail> {
      this.townError = "";
      try {
        this.townDetail = await api.get<TownMapDetail>(`/api/map/towns/${encodeURIComponent(regionId)}`);
        return this.townDetail;
      } catch (error) {
        this.townError = messageOf(error, "乡镇红色资源加载失败");
        throw error;
      }
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
    async loadApprovedResource(resourceId: number): Promise<Record<string, any>> {
      const auth = useAuthStore();
      if (!auth.user?.schoolId) throw new Error("school account is required");
      return api.get(`/api/school-map/schools/${auth.user.schoolId}/resources/${resourceId}`);
    }
  }
});
