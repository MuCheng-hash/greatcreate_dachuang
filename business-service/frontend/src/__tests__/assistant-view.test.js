import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ post: vi.fn() }));
const authMock = vi.hoisted(() => ({ user: { schoolId: 1 } }));
const schoolMock = vi.hoisted(() => ({
  school: { schoolId: 1, schoolName: "里庄小学" },
  resources: [],
  activityPlans: [],
  load: vi.fn()
}));

vi.mock("@/services/api", () => ({ api: apiMock }));
vi.mock("@/stores/auth", () => ({ useAuthStore: () => authMock }));
vi.mock("@/stores/school", () => ({ useSchoolStore: () => schoolMock }));

import AssistantView from "@/views/AssistantView.vue";

describe("assistant view", () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.clearAllMocks();
    schoolMock.load.mockResolvedValue(schoolMock);
    apiMock.post.mockResolvedValue({
      answer: "已找到相关资源。",
      relatedResources: ["常安镇敬老院"],
      citations: [{ citationId: "chunk:1", title: "敬老服务资源说明", excerpt: "资源说明" }],
      followUpQuestions: ["它适合哪个年级？"],
      retrievalStatus: "ok"
    });
  });

  it("sends questions to the business Agent endpoint and renders citations", async () => {
    const wrapper = mount(AssistantView, {
      global: {
        stubs: {
          AppShell: { template: "<div><slot /></div>" },
          InlineNotice: true
        }
      }
    });

    await flushPromises();
    await wrapper.get("textarea").setValue("附近有哪些红色资源？");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/qa/ask", {
      question: "附近有哪些红色资源？",
      scopeType: "SCHOOL",
      scopeId: 1
    });
    expect(wrapper.text()).toContain("已找到相关资源。");
    expect(wrapper.text()).toContain("敬老服务资源说明");
    expect(wrapper.text()).toContain("已结合知识检索证据");
  });

  it.each([
    ["empty", "未检索到直接匹配的知识证据"],
    ["degraded", "知识检索部分不可用，当前回答基于可用业务数据"]
  ])("renders the %s retrieval status", async (retrievalStatus, statusText) => {
    apiMock.post.mockResolvedValueOnce({ answer: "回答", citations: [], retrievalStatus });
    const wrapper = mount(AssistantView, {
      global: {
        stubs: {
          AppShell: { template: "<div><slot /></div>" },
          InlineNotice: true
        }
      }
    });

    await flushPromises();
    await wrapper.get("textarea").setValue("请继续说明。");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.text()).toContain(statusText);
  });
});
