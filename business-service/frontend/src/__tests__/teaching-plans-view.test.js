import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), stream: vi.fn() }));
const authMock = vi.hoisted(() => ({ user: { schoolId: 1 } }));
const schoolMock = vi.hoisted(() => ({
  school: { schoolId: 1, schoolName: "里庄小学" },
  resources: [],
  load: vi.fn()
}));

vi.mock("@/services/api", () => ({ api: apiMock }));
vi.mock("@/stores/auth", () => ({ useAuthStore: () => authMock }));
vi.mock("@/stores/school", () => ({ useSchoolStore: () => schoolMock }));

import TeachingPlansView from "@/views/TeachingPlansView.vue";

describe("teaching plans view", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    schoolMock.load.mockResolvedValue(schoolMock);
    apiMock.get.mockResolvedValue({ records: [] });
  });

  it("renders token deltas before replacing them with the final structured plan", async () => {
    let finishStream;
    apiMock.stream.mockImplementation(async (path, body, options) => {
      expect(path).toBe("/api/ai/teaching-plans/generate/stream");
      expect(body).toEqual(expect.objectContaining({ schoolId: 1, theme: "敬老志愿服务" }));
      options.onEvent("run.started", { message: "正在调用模型" });
      options.onEvent("token", { delta: "主模型残片" });
      options.onEvent("model.failed", { reset: true, nextModel: "qwen3:8b", message: "正在切换备用模型" });
      options.onEvent("token", { delta: "{\"objectives\":[\"逐字生成目标" });
      await new Promise((resolve) => { finishStream = resolve; });
      options.onEvent("token", { delta: "\"]}" });
      options.onEvent("final", {
        threadId: "thread-teaching-plan-1",
        response: {
          taskType: "TEACHING_PLAN",
          status: "completed",
          teachingPlan: {
            generationStatus: "completed",
            message: "教学方案已生成",
            theme: "敬老志愿服务",
            grade: "四年级",
            durationMinutes: 120,
            objectives: ["逐字生成目标"],
            citations: []
          }
        }
      });
      options.onEvent("done", {});
    });

    const wrapper = mount(TeachingPlansView, {
      global: {
        stubs: {
          AppShell: { template: "<div><slot /></div>" },
          InlineNotice: true,
          LoadingBlock: true
        }
      }
    });
    await flushPromises();
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.text()).toContain("正在生成教学方案");
    expect(wrapper.text()).toContain("逐字生成目标");
    expect(wrapper.text()).not.toContain("主模型残片");
    expect(wrapper.text()).toContain("停止生成");

    finishStream();
    await flushPromises();

    expect(wrapper.text()).toContain("教学目标");
    expect(wrapper.text()).toContain("逐字生成目标");
    expect(wrapper.text()).toContain("保存草稿");
  });
});
