import { describe, expect, it } from "vitest";
import { resolveRouteAccess } from "@/router";

describe("portal route access", () => {
  it("redirects unauthenticated users to login with the requested route", () => {
    const result = resolveRouteAccess({ meta: {}, fullPath: "/assistant" }, { isAdmin: false, isAuthenticated: false });
    expect(result).toEqual({ path: "/login", query: { redirect: "/assistant" } });
  });

  it("redirects authenticated school users away from login", () => {
    expect(resolveRouteAccess({ meta: { public: true }, fullPath: "/login" }, { isAdmin: false, isAuthenticated: true })).toBe("/map");
  });

  it("sends platform administrators to the existing admin console", () => {
    expect(resolveRouteAccess({ meta: {}, fullPath: "/map" }, { isAdmin: true, isAuthenticated: true })).toEqual({ external: "/admin.html" });
  });
});
