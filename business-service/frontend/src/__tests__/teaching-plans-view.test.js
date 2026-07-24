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

  it("renders structured plan patches before the stream finishes", async () => {
    let finishStream;
    apiMock.stream.mockImplementation(async (path, body, options) => {
      expect(path).toBe("/api/ai/teaching-plans/generate/stream");
      expect(body).toEqual(expect.objectContaining({ schoolId: 1, theme: "敬老志愿服务" }));
      options.onEvent("run.started", { model: "qwen-plus", message: "正在调用 qwen-plus" });
      options.onEvent("token", { delta: '{"theme":"敬老志愿服务"' });
      options.onEvent("model.failed", { reset: true, nextModel: "qwen3:8b", message: "备用模型 qwen3:8b" });
      options.onEvent("plan.patch", {
        patch: {
          theme: "敬老志愿服务",
          grade: "四年级",
          objectives: ["逐步认识身边的真实资源"]
        }
      });
      await new Promise((resolve) => { finishStream = resolve; });
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
    expect(wrapper.text()).toContain("敬老志愿服务");
    expect(wrapper.text()).toContain("教学目标");
    expect(wrapper.text()).toContain("逐步认识身边的真实资源");
    expect(wrapper.text()).not.toContain("qwen-plus");
    expect(wrapper.text()).not.toContain("qwen3:8b");
    expect(wrapper.text()).not.toContain("{\"theme\"");
    expect(wrapper.text()).toContain("停止生成");

    finishStream();
    await flushPromises();

    expect(wrapper.text()).toContain("教学目标");
    expect(wrapper.text()).toContain("逐字生成目标");
    expect(wrapper.text()).toContain("保存草稿");
  });

  it("uses a Chinese degraded notice without exposing backend model errors", async () => {
    apiMock.stream.mockImplementation(async (path, body, options) => {
      options.onEvent("model.started", { model: "qwen-plus" });
      options.onEvent("final", {
        threadId: "thread-teaching-plan-degraded",
        response: {
          taskType: "TEACHING_PLAN",
          status: "degraded",
          teachingPlan: {
            generationStatus: "degraded",
            message: "LLM 服务不可用",
            theme: "家乡文化",
            grade: "四年级",
            durationMinutes: 40,
            objectives: ["认识身边的真实资源"],
            citations: []
          }
        }
      });
    });

    const wrapper = mount(TeachingPlansView, {
      global: {
        stubs: {
          AppShell: { template: "<div><slot /></div>" },
          InlineNotice: { template: "<div><slot /></div>" },
          LoadingBlock: true
        }
      }
    });
    await flushPromises();
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.text()).toContain("已生成基础教学方案，部分内容可能需要人工补充");
    expect(wrapper.text()).not.toContain("LLM 服务不可用");
    expect(wrapper.text()).not.toContain("qwen-plus");
  });
});
