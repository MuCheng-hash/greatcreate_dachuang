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
    delete apiMock.stream;
    schoolMock.load.mockResolvedValue(schoolMock);
    apiMock.post.mockResolvedValue({
      answer: "已找到相关资源。",
      relatedResources: ["常安镇敬老院"],
      citations: [{ citationId: "chunk:1", title: "敬老服务资源说明", excerpt: "资源说明" }],
      followUpQuestions: ["它适合哪个年级？"],
      retrievalStatus: "ok",
      generationStatus: "completed"
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

    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/qa/ask", expect.objectContaining({
      question: "附近有哪些红色资源？",
      scopeType: "SCHOOL",
      scopeId: 1
    }));
    expect(apiMock.post.mock.calls[0][1].conversationId).toBeTruthy();
    expect(wrapper.text()).toContain("已找到相关资源。");
    expect(wrapper.text()).toContain("敬老服务资源说明");
    expect(wrapper.text()).toContain("已结合知识检索证据");
    expect(wrapper.text()).toContain("已由答案生成服务整理");
  });

  it("streams tool progress and token deltas through one conversation", async () => {
    apiMock.stream = vi.fn(async (path, body, options) => {
      expect(path).toBe("/api/ai/qa/stream");
      expect(body.conversationId).toBeTruthy();
      options.onEvent("run.started", { runId: "run-1", conversationId: body.conversationId });
      options.onEvent("tool.started", { toolName: "/internal/agent/tools/knowledge-retrieve" });
      options.onEvent("tool.completed", { toolName: "/internal/agent/tools/knowledge-retrieve", status: "ok" });
      options.onEvent("token", { delta: "逐字" });
      options.onEvent("token", { delta: "回答" });
      options.onEvent("final", {
        response: {
          answer: "逐字回答",
          conversationId: body.conversationId,
          runId: "run-1",
          citations: [{ citationId: "chunk:1", title: "检索证据", excerpt: "证据" }],
          retrievalStatus: "ok",
          generationStatus: "completed"
        }
      });
      options.onEvent("done", { runId: "run-1" });
    });
    const wrapper = mount(AssistantView, {
      global: {
        stubs: {
          AppShell: { template: "<div><slot /></div>" },
          InlineNotice: true
        }
      }
    });

    await flushPromises();
    await wrapper.get("textarea").setValue("附近有哪些资源？");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(apiMock.stream).toHaveBeenCalledTimes(1);
    expect(apiMock.post).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain("逐字回答");
    expect(wrapper.text()).toContain("知识检索：完成");
    expect(wrapper.text()).toContain("检索证据");
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

  it("renders generation degradation and clarification guidance", async () => {
    apiMock.post.mockResolvedValueOnce({
      answer: "请补充具体学校名称。",
      citations: [],
      retrievalStatus: "empty",
      generationStatus: "skipped",
      clarificationRequired: true,
      clarificationMessage: "问题中匹配到多个学校，请补充完整学校名称。",
      clarificationOptions: ["里庄小学", "示例小学"]
    });
    const wrapper = mount(AssistantView, {
      global: {
        stubs: {
          AppShell: { template: "<div><slot /></div>" },
          InlineNotice: true
        }
      }
    });

    await flushPromises();
    await wrapper.get("textarea").setValue("附近有哪些资源？");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.text()).toContain("未调用答案生成服务");
    expect(wrapper.text()).toContain("问题中匹配到多个学校");
    expect(wrapper.text()).toContain("里庄小学、示例小学");
  });
});
