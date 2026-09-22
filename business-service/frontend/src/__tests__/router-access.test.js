import { describe, expect, it } from "vitest";
import router, { resolveRouteAccess, shouldReloadForDynamicImportError } from "@/router";

describe("portal route access", () => {
  it("retries one stale lazy-route module load after a frontend deployment", () => {
    expect(shouldReloadForDynamicImportError(
      new TypeError("Failed to fetch dynamically imported module"),
      false,
    )).toBe(true);
    expect(shouldReloadForDynamicImportError(
      new TypeError("Failed to fetch dynamically imported module"),
      true,
    )).toBe(false);
    expect(shouldReloadForDynamicImportError(new Error("权限不足"), false)).toBe(false);
  });

  it("redirects unauthenticated users to login with the requested route", () => {
    const result = resolveRouteAccess({ meta: {}, fullPath: "/assistant" }, { isAdmin: false, isAuthenticated: false });
    expect(result).toEqual({ path: "/login", query: { redirect: "/assistant" } });
  });

  it("redirects authenticated school users away from login", () => {
    expect(resolveRouteAccess({ meta: { public: true }, fullPath: "/login" }, { isAdmin: false, isAuthenticated: true })).toBe("/teacher/map");
    expect(resolveRouteAccess({ meta: { public: true }, fullPath: "/login" }, { isAdmin: false, isAuthenticated: true, isStudent: true })).toBe("/student/home");
  });

  it("sends platform administrators to the existing admin console", () => {
    expect(resolveRouteAccess({ meta: {}, fullPath: "/map" }, { isAdmin: true, isAuthenticated: true })).toEqual({ external: "/admin.html" });
  });

  it("does not expose an Agent debug route in the school portal", () => {
    const debugRoutes = router.getRoutes().filter(route =>
      route.path === "/teacher/agent-debug" || route.path === "/agent-debug"
    );
    expect(debugRoutes).toHaveLength(0);
  });
});
