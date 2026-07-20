import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

const apiMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("@/services/api", () => ({ api: apiMock }));

import { useAuthStore } from "@/stores/auth";
import { useSchoolStore } from "@/stores/school";

describe("school discovery store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    useAuthStore().setUser({ accountId: 3, schoolId: 2, roleCode: "school_admin" });
  });

  it("starts a school-scoped discovery run with selected radius", async () => {
    apiMock.post.mockResolvedValue({ runId: 8, status: "running", candidates: [] });
    const store = useSchoolStore();

    await store.startDiscovery(10);

    expect(apiMock.post).toHaveBeenCalledWith(
      "/api/school-map/schools/2/discovery-runs",
      { radiusKm: 10 }
    );
    expect(store.discoveryRadiusKm).toBe(10);
    expect(store.discoveryRun.runId).toBe(8);
  });

  it("keeps returned candidates separate from approved resources", async () => {
    apiMock.post.mockResolvedValue({ runId: 9, status: "completed", candidates: [{ candidateId: 5 }] });
    const store = useSchoolStore();
    store.detail = { resources: [{ resourceId: 1 }] };

    await store.startDiscovery(5);

    expect(store.resources).toHaveLength(1);
    expect(store.discoveryCandidates).toEqual([{ candidateId: 5 }]);
  });
});
