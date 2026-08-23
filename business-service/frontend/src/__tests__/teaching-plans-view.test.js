import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), stream: vi.fn() }));
const authMock = vi.hoisted(() => ({ user: { schoolId: 1, roleCode: "teacher" } }));
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
    apiMock.get.mockImplementation(async (path) => path === "/api/ai/models"
      ? [{ id: "ernie", displayName: "文心一言", provider: "qianfan", model: "ernie-test", isDefault: true }]
      : { records: [] });
    apiMock.put.mockResolvedValue({ generationId: 31, adopted: false, rating: 2, teacherNote: "需补充案例" });
  });

  it("shows the selected actual model only while the teaching plan is generating", async () => {
    let releaseFinal;
    const finalGate = new Promise((resolve) => {
      releaseFinal = resolve;
    });
    apiMock.stream.mockImplementation(async (_path, body, options) => {
      expect(body.modelId).toBe("ernie");
      options.onEvent("model.completed", { provider: "qianfan", model: "ernie-test" });
      await finalGate;
      options.onEvent("final", {
        response: { teachingPlan: { generationStatus: "completed", theme: "测试", grade: "四年级", durationMinutes: 40 } }
      });
    });
    const wrapper = mount(TeachingPlansView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true, LoadingBlock: true } }
    });
    await flushPromises();
    await wrapper.findAll("select")[1].setValue("ernie");
    const submission = wrapper.get("form").trigger("submit.prevent");
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(wrapper.text()).toContain("qianfan / ernie-test");

    releaseFinal();
    await submission;
    await flushPromises();

    expect(wrapper.text()).not.toContain("qianfan / ernie-test");
  });

  it("hides the actual model after teaching-plan generation fails", async () => {
    apiMock.stream.mockImplementation(async (_path, _body, options) => {
      options.onEvent("model.completed", { provider: "qianfan", model: "ernie-test" });
      throw new Error("stream unavailable");
    });
    const wrapper = mount(TeachingPlansView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true, LoadingBlock: true } }
    });
    await flushPromises();
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.text()).not.toContain("实际模型：qianfan / ernie-test");
  });

  it("hides the actual model immediately when teaching-plan generation is stopped", async () => {
    apiMock.stream.mockImplementation(async (_path, _body, options) => {
      options.onEvent("model.completed", { provider: "qianfan", model: "ernie-test" });
      await new Promise(() => {});
    });
    const wrapper = mount(TeachingPlansView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true, LoadingBlock: true } }
    });
    await flushPromises();
    await wrapper.get("form").trigger("submit.prevent");
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(wrapper.text()).toContain("实际模型：qianfan / ernie-test");
    await wrapper.get(".secondary-button.full-button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).not.toContain("实际模型：qianfan / ernie-test");
  });

  it("renders structured plan patches before the stream finishes", async () => {
    let finishStream;
    apiMock.stream.mockImplementation(async (path, body, options) => {
      expect(path).toBe("/api/ai/teaching-plans/generate/stream");
      expect(body).toEqual(expect.objectContaining({ schoolId: 1, theme: "敬老志愿服务" }));
      options.onEvent("run.started", { model: "qwen-plus", message: "正在调用 qwen-plus" });
      options.onEvent("response.reset", { nextModel: "qwen3:8b", reason: "model_fallback" });
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
      options.onEvent("model.completed", { provider: "qianfan", model: "qwen-plus" });
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

  it("submits required adoption, rating and note for the persisted generation", async () => {
    apiMock.stream.mockImplementation(async (_path, _body, options) => {
      options.onEvent("final", {
        response: {
          teachingPlan: {
            generationId: 31,
            generationStatus: "completed",
            theme: "家乡文化",
            grade: "四年级",
            durationMinutes: 40,
            objectives: ["认识家乡文化"]
          }
        }
      });
    });
    const wrapper = mount(TeachingPlansView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true, LoadingBlock: true } }
    });
    await flushPromises();
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    const feedbackCard = wrapper.get(".feedback-card");
    await feedbackCard.findAll("button")[1].trigger("click");
    const ratingButtons = feedbackCard.findAll(".star-rating button");
    expect(ratingButtons).toHaveLength(5);
    await feedbackCard.get('[aria-label="2 分"]').trigger("click");
    expect(ratingButtons[0].classes()).toContain("active");
    expect(ratingButtons[1].classes()).toContain("active");
    expect(ratingButtons[2].classes()).not.toContain("active");
    expect(ratingButtons[0].attributes("aria-checked")).toBe("false");
    expect(ratingButtons[1].attributes("aria-checked")).toBe("true");
    expect(ratingButtons[2].attributes("aria-checked")).toBe("false");
    const reasonButtons = feedbackCard.findAll(".reason-options button");
    expect(reasonButtons).toHaveLength(9);
    await reasonButtons.find((button) => button.text() === "内容不完整").trigger("click");
    await feedbackCard.get("textarea").setValue("需补充案例");
    await feedbackCard.get(".primary-button").trigger("click");
    await flushPromises();

    expect(apiMock.put).toHaveBeenCalledWith(
      "/api/ai/teaching-plans/generations/31/feedback",
      { adopted: false, rating: 2, reasonCodes: ["CONTENT_INCOMPLETE"], teacherNote: "需补充案例" }
    );
  });

  it("reuses generationId when manually saving a generated draft", async () => {
    apiMock.post.mockResolvedValue({ planId: 91 });
    apiMock.stream.mockImplementation(async (_path, _body, options) => {
      options.onEvent("final", {
        response: {
          teachingPlan: {
            generationId: 32,
            generationStatus: "completed",
            theme: "家乡文化",
            grade: "四年级",
            durationMinutes: 40,
            objectives: ["认识家乡文化"]
          }
        }
      });
    });
    const wrapper = mount(TeachingPlansView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true, LoadingBlock: true } }
    });
    await flushPromises();
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    await wrapper.findAll("button").find((button) => button.text().includes("保存草稿")).trigger("click");
    await flushPromises();

    expect(apiMock.post).toHaveBeenCalledWith(
      "/api/ai/teaching-plans/save-draft",
      expect.objectContaining({ generationId: 32 })
    );
  });

  it("splits pending and submitted feedback, with submitted records read-only", async () => {
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      if (path.includes("feedbackStatus=pending")) {
        return { total: 1, records: [{
          generationId: 43,
          theme: "待评价方案",
          grade: "四年级",
          durationMinutes: 40,
          createdAt: "2026-08-22T08:00:00",
          plan: { objectives: ["待评价目标"] },
          feedback: null
        }] };
      }
      if (path.includes("feedbackStatus=submitted")) {
        return { records: [{
          generationId: 44,
          theme: "红色家书",
          grade: "五年级",
          durationMinutes: 45,
          createdAt: "2026-08-22T09:00:00",
          plan: { objectives: ["理解家书中的家国情怀"] },
          feedback: { adopted: false, rating: 2, reasonCodes: ["THEME_DEVIATION"], teacherNote: "需要调整" }
        }], total: 1 };
      }
      return { records: [] };
    });
    const wrapper = mount(TeachingPlansView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true, LoadingBlock: true } }
    });
    await flushPromises();

    expect(wrapper.text()).toContain("待评价方案");
    expect(wrapper.text()).not.toContain("红色家书");
    await wrapper.findAll('[role="tab"]')[1].trigger("click");
    expect(wrapper.text()).toContain("红色家书");
    expect(wrapper.text()).toContain("未采纳 · 2 分");
    expect(wrapper.text()).toContain("偏离主题");
    expect(wrapper.findAll(".generation-card .star-rating button")).toHaveLength(0);
    expect(wrapper.findAll(".generation-card button").some((button) => button.text().includes("保存反馈"))).toBe(false);
  });

  it("requires a note when the other negative reason is selected", async () => {
    apiMock.stream.mockImplementation(async (_path, _body, options) => options.onEvent("final", {
      response: { teachingPlan: { generationId: 55, generationStatus: "completed", theme: "测试", grade: "四年级", durationMinutes: 40 } }
    }));
    const wrapper = mount(TeachingPlansView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: { template: "<div><slot /></div>" }, LoadingBlock: true } }
    });
    await flushPromises();
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    const card = wrapper.get(".feedback-card");
    await card.findAll(".feedback-choice button")[1].trigger("click");
    await card.get('[aria-label="1 分"]').trigger("click");
    await card.findAll(".reason-options button").find((button) => button.text() === "其他").trigger("click");
    await card.get(".primary-button").trigger("click");
    await flushPromises();

    expect(apiMock.put).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("选择“其他”原因后，请填写教师备注");
  });
});
