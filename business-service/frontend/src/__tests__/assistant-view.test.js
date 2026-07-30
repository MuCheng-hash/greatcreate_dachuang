import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn() }));
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
    apiMock.get.mockImplementation(async (path) => path === "/api/ai/models" ? [
      { id: "deepseek", displayName: "DeepSeek", provider: "deepseek", model: "deepseek-chat", isDefault: true }
    ] : []);
    apiMock.delete.mockResolvedValue(undefined);
    apiMock.post.mockResolvedValue({
      threadId: "thread-1",
      answer: "已找到相关资源。",
      relatedResources: ["常安镇敬老院"],
      citations: [{ citationId: "chunk:1", title: "敬老服务资源说明", excerpt: "资源说明" }],
      followUpQuestions: ["它适合哪个年级？"],
      retrievalStatus: "ok",
      generationStatus: "completed"
    });
  });

  it("restores, continues, starts new, and archives historical conversations", async () => {
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      if (path === "/api/ai/qa/history") return [{
        threadId: "history-1", title: "历史问题", preview: "历史回答", messageCount: 2,
        scopeType: "SCHOOL", scopeId: "1", createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z"
      }];
      if (path === "/api/ai/qa/history/history-1") return {
        threadId: "history-1", scopeType: "SCHOOL", scopeId: "1", status: "active",
        createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z",
        messages: [
          { id: 1, role: "user", content: "历史问题", createdAt: "2026-07-28T00:00:00Z" },
          { id: 2, role: "assistant", content: "历史回答", createdAt: "2026-07-28T00:01:00Z" }
        ]
      };
      return [];
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();

    expect(wrapper.text()).toContain("历史问题");
    await wrapper.get(".history-open").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("历史回答");

    await wrapper.get("textarea").setValue("继续追问");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/qa/ask", expect.objectContaining({ threadId: "history-1" }));

    await wrapper.get(".icon-action").trigger("click");
    expect(wrapper.find(".chat-message").exists()).toBe(false);
    await wrapper.get(".history-archive").trigger("click");
    await flushPromises();
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/qa/history/history-1");
    expect(wrapper.find(".history-row").exists()).toBe(false);
  });

  it("opens archived conversations as read-only and restores them without losing messages", async () => {
    const activeSummary = {
      threadId: "active-1", title: "当前历史", preview: "当前回答", messageCount: 2,
      scopeType: "SCHOOL", scopeId: "1", createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z"
    };
    const archivedSummary = {
      threadId: "archived-1", title: "已归档问题", preview: "已归档回答", messageCount: 2,
      scopeType: "SCHOOL", scopeId: "1", createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z"
    };
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      if (path === "/api/ai/qa/history") return [activeSummary];
      if (path === "/api/ai/qa/history?status=archived") return [archivedSummary];
      if (path === "/api/ai/qa/history/archived-1") return {
        ...archivedSummary,
        status: "archived",
        messages: [
          { id: 1, role: "user", content: "已归档问题", createdAt: "2026-07-28T00:00:00Z" },
          { id: 2, role: "assistant", content: "已归档回答", createdAt: "2026-07-28T00:01:00Z" }
        ]
      };
      return [];
    });
    apiMock.post.mockImplementation(async (path) => path.endsWith("/restore") ? undefined : {
      threadId: "thread-1",
      answer: "回答",
      citations: [],
      retrievalStatus: "ok",
      generationStatus: "completed"
    });

    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();

    await wrapper.get(".history-mode-toggle").trigger("click");
    await flushPromises();
    expect(apiMock.get).toHaveBeenCalledWith("/api/ai/qa/history?status=archived");
    expect(wrapper.text()).toContain("已归档对话");
    await wrapper.get(".history-open").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("这是归档对话，仅供查看。");
    expect(wrapper.text()).toContain("已归档回答");
    expect(wrapper.get("textarea").attributes("disabled")).toBeDefined();
    expect(wrapper.get("textarea").attributes("placeholder")).toContain("请先恢复对话");

    await wrapper.get(".history-restore").trigger("click");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/qa/history/archived-1/restore");
    expect(wrapper.text()).toContain("历史对话");
    expect(wrapper.text()).toContain("已归档回答");
    expect(wrapper.get("textarea").attributes("disabled")).toBeUndefined();
    expect(wrapper.find(".archived-banner").exists()).toBe(false);
    await wrapper.get("textarea").setValue("恢复后继续追问");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/qa/ask", expect.objectContaining({ threadId: "archived-1" }));
  });

  it("keeps question answering usable when history loading fails", async () => {
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      throw new Error("history unavailable");
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    expect(wrapper.text()).toContain("历史记录暂时无法加载");
    await wrapper.get("textarea").setValue("仍然可以提问");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalled();
  });

  it("loads and forwards the selected model", async () => {
    apiMock.stream = vi.fn(async (_path, body, options) => {
      expect(body.modelId).toBe("deepseek");
      options.onEvent("model.completed", { provider: "deepseek", model: "deepseek-chat" });
      options.onEvent("final", { response: { answer: "模型回答", generationStatus: "completed" } });
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get("select").setValue("deepseek");
    await wrapper.get("textarea").setValue("测试模型切换");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.text()).toContain("deepseek / deepseek-chat");
  });

  it("previews and forwards an image attachment", async () => {
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    const file = new File(["image"], "school.png", { type: "image/png" });
    const input = wrapper.get('input[type="file"]');
    Object.defineProperty(input.element, "files", { configurable: true, value: [file] });
    await input.trigger("change");
    await vi.waitFor(() => expect(wrapper.find(".pending-images img").exists()).toBe(true));
    await wrapper.get("textarea").setValue("分析图片中的教育资源");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/qa/ask", expect.objectContaining({
      attachments: [expect.objectContaining({
        type: "image", name: "school.png", mediaType: "image/png"
      })]
    }));
  });

  it("reports unsupported speech recognition without breaking text input", async () => {
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: { template: "<div><slot /></div>" } } }
    });
    await flushPromises();
    await wrapper.get('button[aria-label="语音输入"]').trigger("click");
    expect(wrapper.text()).toContain("当前浏览器不支持语音输入");
    expect(wrapper.get("textarea").exists()).toBe(true);
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
      options.onEvent("phase.started", { phase: "reasoning", label: "正在分析问题并规划处理步骤" });
      options.onEvent("phase.completed", { phase: "reasoning", label: "分析完成，开始执行" });
      options.onEvent("tool.started", { toolName: "/internal/agent/tools/knowledge-retrieve", arguments: { query: "附近资源" } });
      options.onEvent("tool.completed", { toolName: "/internal/agent/tools/knowledge-retrieve", status: "ok", durationMs: 18, outputSummary: "返回 2 条结果" });
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
    expect(wrapper.text()).toContain("分析完成，开始执行");
    expect(wrapper.text()).toContain("知识检索");
    expect(wrapper.text()).toContain("返回 2 条结果");
    expect(wrapper.text()).toContain("18 ms");
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

  it("reuses the server thread id on the next turn", async () => {
    const wrapper = mount(AssistantView, {
      global: {
        stubs: {
          AppShell: { template: "<div><slot /></div>" },
          InlineNotice: true
        }
      }
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("第一轮问题");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    await wrapper.get("textarea").setValue("它适合四年级吗？");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    expect(apiMock.post).toHaveBeenLastCalledWith("/api/ai/qa/ask", {
      question: "它适合四年级吗？",
      scopeType: "SCHOOL",
      scopeId: 1,
      threadId: "thread-1"
    });
  });
});
