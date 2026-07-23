import { defineStore } from "pinia";
import { api } from "@/services/api";

export interface AuthCurrentUser {
  accountId?: number;
  username?: string;
  roleCode?: string;
  schoolId?: number;
  schoolName?: string;
  displayName?: string;
  contactName?: string;
  contactPhone?: string;
}

export interface AuthCredentials {
  username: string;
  password: string;
}

interface AuthState {
  user: AuthCurrentUser | null;
  initialized: boolean;
  loading: boolean;
}

export const useAuthStore = defineStore("auth", {
  state: (): AuthState => ({
    user: null,
    initialized: false,
    loading: false,
  }),
  getters: {
    isAuthenticated: (state): boolean => Boolean(state.user?.accountId),
    isAdmin: (state): boolean => state.user?.roleCode === "platform_admin",
    schoolLabel: (state): string => state.user?.schoolName || state.user?.displayName || state.user?.username || "学校账号",
  },
  actions: {
    async ensureLoaded(force = false): Promise<AuthCurrentUser | null> {
      if (this.initialized && !force) return this.user;
      this.loading = true;
      try {
        this.user = await api.get<AuthCurrentUser>("/api/auth/me");
      } catch {
        this.user = null;
      } finally {
        this.loading = false;
        this.initialized = true;
      }
      return this.user;
    },
    async login(credentials: AuthCredentials): Promise<AuthCurrentUser> {
      this.loading = true;
      try {
        this.user = await api.post<AuthCurrentUser>("/api/auth/login", credentials);
        this.initialized = true;
        return this.user;
      } finally {
        this.loading = false;
      }
    },
    async logout(): Promise<void> {
      try {
        await api.post("/api/auth/logout", {});
      } finally {
        this.user = null;
        this.initialized = true;
      }
    },
    setUser(user: AuthCurrentUser | null): void {
      this.user = user;
      this.initialized = true;
    },
    clear(): void {
      this.user = null;
      this.initialized = true;
    },
  },
});
