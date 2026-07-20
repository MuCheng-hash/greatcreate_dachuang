import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

const apiMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("@/services/api", () => ({ api: apiMock }));

import { useAuthStore } from "@/stores/auth";

describe("auth store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("loads an existing school session", async () => {
    apiMock.get.mockResolvedValue({ accountId: 1, schoolName: "里庄小学", roleCode: "school_admin" });
    const auth = useAuthStore();

    await auth.ensureLoaded();

    expect(auth.isAuthenticated).toBe(true);
    expect(auth.schoolLabel).toBe("里庄小学");
  });

  it("clears local state even when logout request fails", async () => {
    apiMock.post.mockRejectedValue(new Error("offline"));
    const auth = useAuthStore();
    auth.setUser({ accountId: 1 });

    await expect(auth.logout()).rejects.toThrow("offline");
    expect(auth.user).toBeNull();
  });
});
