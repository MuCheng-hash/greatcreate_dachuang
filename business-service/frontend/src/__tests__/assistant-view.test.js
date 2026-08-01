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
          {
            id: 2,
            role: "assistant",
            content: "历史回答",
            createdAt: "2026-07-28T00:01:00Z",
            metadata: { followUpQuestions: ["历史追问"] }
          }
        ]
      };
      return [];
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();

    expect(wrapper.find(".chat-main .chat-scroll").exists()).toBe(true);
    expect(wrapper.find(".chat-area > .chat-composer").exists()).toBe(true);
    expect(wrapper.find(".chat-composer .composer-model-select").exists()).toBe(true);
    expect(wrapper.find(".assistant-side .composer-model-select").exists()).toBe(false);
    expect(wrapper.text()).toContain("历史问题");
    await wrapper.get(".history-open").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("历史回答");
    expect(wrapper.get(".follow-ups button").text()).toBe("历史追问");

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

  it("restores rich result snapshots after switching conversations without exposing raw tool output", async () => {
    const summaries = [
      { threadId: "history-a", title: "问题 A", preview: "回答 A", messageCount: 2, scopeType: "SCHOOL", scopeId: "1", createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T02:00:00Z" },
      { threadId: "history-b", title: "问题 B", preview: "回答 B", messageCount: 2, scopeType: "SCHOOL", scopeId: "1", createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z" },
    ];
    const richMetadata = {
      responseSnapshot: {
        schemaVersion: 1,
        status: "completed",
        generationStatus: "completed",
        retrievalStatus: "degraded",
        retrievalMethods: ["keyword-fallback"],
        citations: [{ citationId: "chunk:1", title: "历史来源", excerpt: "可信摘要" }],
        relatedResources: ["常安镇敬老院"],
        followUpQuestions: ["历史追问 A"],
        provider: "openai-compatible",
        model: "qwen-test",
        fallbackLevel: 0,
        toolExecutions: [{ name: "get_school_context", status: "completed", durationMs: 7, outputSummary: "RAW_SCHOOL_JSON" }],
        contextCompacted: true,
        memoryApplied: { count: 2, memoryIds: ["profile-1", "task-1"] },
      },
      memoryCandidates: [{
        id: "historical-candidate",
        memoryType: "PROFILE",
        fieldKey: "grade",
        content: "不应由历史恢复的候选",
        status: "pending",
        source: "inferred_chat",
      }],
    };
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      if (path === "/api/ai/qa/history") return summaries;
      if (path === "/api/ai/qa/history/history-a") return {
        ...summaries[0], status: "active", messages: [
          { id: 1, role: "user", content: "问题 A", createdAt: "2026-07-28T00:00:00Z" },
          { id: 2, role: "assistant", content: "回答 A", createdAt: "2026-07-28T00:01:00Z", metadata: richMetadata },
        ],
      };
      if (path === "/api/ai/qa/history/history-b") return {
        ...summaries[1], status: "active", messages: [
          { id: 3, role: "user", content: "问题 B", createdAt: "2026-07-28T01:00:00Z" },
          { id: 4, role: "assistant", content: "回答 B", createdAt: "2026-07-28T01:01:00Z", metadata: {} },
        ],
      };
      return [];
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } },
    });
    await flushPromises();
    const historyButtons = wrapper.findAll(".history-open");

    await historyButtons[0].trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("历史来源");
    expect(wrapper.text()).toContain("向量检索未启用或暂不可用，已使用关键词检索");
    expect(wrapper.text()).not.toContain("实际模型：openai-compatible / qwen-test");
    expect(wrapper.text()).toContain("本次参考 2 条记忆");
    expect(wrapper.find(".composer-memory-suggestions").exists()).toBe(false);
    expect(wrapper.text()).toContain("常安镇敬老院");
    expect(wrapper.text()).toContain("历史追问 A");
    expect(wrapper.get(".agent-trace-toggle").attributes("aria-expanded")).toBe("false");
    expect(wrapper.find(".agent-trace-list").exists()).toBe(false);

    await historyButtons[1].trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("回答 B");
    await historyButtons[0].trigger("click");
    await flushPromises();
    await wrapper.get(".agent-trace-toggle").trigger("click");
    expect(wrapper.text()).toContain("查看学校上下文");
    expect(wrapper.text()).not.toContain("RAW_SCHOOL_JSON");
  });

  it("groups school explanation and clear actions at the bottom of the sidebar", async () => {
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();

    const actions = wrapper.get(".assistant-side-actions");
    expect(actions.find(".primary-button").text()).toContain("生成学校讲解");
    expect(actions.find(".clear-button").text()).toContain("清空会话");
    expect(actions.element.children[0]).toBe(actions.find(".primary-button").element);
    expect(actions.element.children[1]).toBe(actions.find(".clear-button").element);

    await actions.get(".clear-button").trigger("click");
    expect(wrapper.find(".chat-message").exists()).toBe(false);
  });

  it("renders multiple history records before the fixed bottom actions", async () => {
    const summaries = Array.from({ length: 8 }, (_, index) => ({
      threadId: `history-${index + 1}`,
      title: `历史问题 ${index + 1}`,
      preview: "历史回答",
      messageCount: 2,
      scopeType: "SCHOOL",
      scopeId: "1",
      createdAt: "2026-07-28T00:00:00Z",
      updatedAt: "2026-07-28T01:00:00Z",
    }));

    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      if (path === "/api/ai/qa/history") return summaries;
      return [];
    });

    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();

    expect(wrapper.findAll(".history-row")).toHaveLength(8);
    expect(wrapper.get(".history-list").element.nextElementSibling).toBe(
      wrapper.get(".assistant-side-actions").element,
    );
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
          {
            id: 2,
            role: "assistant",
            content: "已归档回答",
            createdAt: "2026-07-28T00:01:00Z",
            metadata: { followUpQuestions: ["归档追问"] }
          }
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
    expect(wrapper.get(".follow-ups button").text()).toBe("归档追问");
    expect(wrapper.get(".follow-ups button").attributes("disabled")).toBeDefined();
    expect(wrapper.find(".chat-area > .chat-composer").exists()).toBe(true);
    expect(wrapper.get(".assistant-side-actions .primary-button").attributes("disabled")).toBeDefined();
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

  it("shows the selected actual model only while the answer is streaming", async () => {
    let releaseFinal;
    const finalGate = new Promise((resolve) => {
      releaseFinal = resolve;
    });
    apiMock.stream = vi.fn(async (_path, body, options) => {
      expect(body.modelId).toBe("deepseek");
      options.onEvent("model.completed", { provider: "deepseek", model: "deepseek-chat" });
      await finalGate;
      options.onEvent("final", { response: { answer: "模型回答", generationStatus: "completed" } });
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    expect(wrapper.find(".chat-composer .composer-model-select").exists()).toBe(true);
    expect(wrapper.find(".assistant-side .composer-model-select").exists()).toBe(false);
    await wrapper.get(".composer-model-select").setValue("deepseek");
    await wrapper.get("textarea").setValue("测试模型切换");
    const submission = wrapper.get("form").trigger("submit.prevent");
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(wrapper.text()).toContain("实际模型：deepseek / deepseek-chat");

    releaseFinal();
    await submission;
    await flushPromises();

    expect(wrapper.text()).not.toContain("实际模型：deepseek / deepseek-chat");
  });

  it("hides the actual model after stopping an active stream", async () => {
    apiMock.stream = vi.fn(async (_path, _body, options) => {
      options.onEvent("model.completed", { provider: "deepseek", model: "deepseek-chat" });
      options.onEvent("token", { delta: "部分回答" });
      await new Promise(() => {});
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("请介绍资源");
    await wrapper.get("form").trigger("submit.prevent");
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(wrapper.text()).toContain("实际模型：deepseek / deepseek-chat");
    await wrapper.get(".stop-button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("已停止生成");
    expect(wrapper.text()).not.toContain("实际模型：deepseek / deepseek-chat");
  });

  it("renders answer-specific follow-ups and removes the sidebar suggestions", async () => {
    const responses = [
      {
        threadId: "thread-1", answer: "第一回答", relatedResources: [], citations: [],
        followUpQuestions: ["第一追问", "重复追问", "第一追问"], generationStatus: "completed"
      },
      {
        threadId: "thread-1", answer: "第二回答", relatedResources: [], citations: [],
        followUpQuestions: ["第二追问"], generationStatus: "completed"
      },
      {
        threadId: "thread-1", answer: "第三回答", relatedResources: [], citations: [],
        followUpQuestions: ["第三追问"], generationStatus: "completed"
      }
    ];
    apiMock.post.mockImplementation(async () => responses.shift());
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();

    await wrapper.get("textarea").setValue("第一问题");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    expect(wrapper.find(".suggestion-list").exists()).toBe(false);
    expect(wrapper.findAll(".follow-ups button").map((button) => button.text())).toEqual(["第一追问", "重复追问"]);

    await wrapper.get("textarea").setValue("第二问题");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();
    expect(wrapper.findAll(".follow-ups").map((item) => item.text())).toEqual(["你还可以问第一追问重复追问", "你还可以问第二追问"]);

    await wrapper.findAll(".follow-ups button")[0].trigger("click");
    await flushPromises();
    expect(apiMock.post).toHaveBeenLastCalledWith("/api/ai/qa/ask", expect.objectContaining({ question: "第一追问" }));
  });

  it("uses a resource-aware fallback when the server returns no follow-ups", async () => {
    apiMock.post.mockResolvedValueOnce({
      threadId: "thread-1", answer: "回答", relatedResources: ["常安镇敬老院"], citations: [],
      followUpQuestions: [], generationStatus: "completed"
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("请介绍资源");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.find(".follow-ups").text()).toContain("请设计一节利用“常安镇敬老院”开展的实践课。");
  });

  it("filters meta follow-ups and sends an actionable teacher task", async () => {
    apiMock.post.mockResolvedValueOnce({
      threadId: "thread-1", answer: "回答", relatedResources: ["常安镇敬老院"], citations: [],
      followUpQuestions: ["您需要查询哪些本土思政教育资源？", "您是否需要特定年级的思政教学建议？"],
      generationStatus: "completed"
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("请介绍本校周边资源");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    const buttons = wrapper.findAll(".follow-ups button");
    const suggestion = buttons[0].text();
    expect(suggestion).toBe("请说明“常安镇敬老院”适合哪些年级。");
    expect(wrapper.text()).not.toContain("您需要查询哪些本土思政教育资源？");

    await buttons[0].trigger("click");
    await flushPromises();
    expect(apiMock.post).toHaveBeenLastCalledWith("/api/ai/qa/ask", expect.objectContaining({ question: suggestion }));
    expect(wrapper.findAll(".chat-message.user").at(-1)?.text()).toContain(suggestion);
  });

  it("replaces invalid follow-ups restored from historical metadata", async () => {
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      if (path === "/api/ai/qa/history") return [{
        threadId: "history-invalid-follow-up", title: "历史问题", preview: "历史回答", messageCount: 2,
        scopeType: "SCHOOL", scopeId: "1", createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z"
      }];
      if (path === "/api/ai/qa/history/history-invalid-follow-up") return {
        threadId: "history-invalid-follow-up", scopeType: "SCHOOL", scopeId: "1", status: "active",
        createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z",
        messages: [
          { id: 1, role: "user", content: "请介绍本校周边资源", createdAt: "2026-07-28T00:00:00Z" },
          {
            id: 2, role: "assistant", content: "历史回答", createdAt: "2026-07-28T00:01:00Z",
            metadata: { followUpQuestions: ["您需要查询哪些本土思政教育资源？"] }
          }
        ]
      };
      return [];
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get(".history-open").trigger("click");
    await flushPromises();

    expect(wrapper.get(".follow-ups button").text()).toBe("请介绍适合当前年级的本土思政教育资源。");
    expect(wrapper.text()).not.toContain("您需要查询哪些本土思政教育资源？");
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

  it("renders assistant Markdown safely with source chips and copy action", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    apiMock.post.mockResolvedValueOnce({
      answer: "### 资源建议\n\n**重点：** 先确认开放状态。\n\n1. 课堂导入\n2. 现场观察\n\n- 注意安全\n- 做好记录\n\n<script>alert('xss')</script>\n\n[危险链接](javascript:alert('xss'))",
      citations: [{ citationId: "chunk:1", title: "资源说明", excerpt: "已授权证据" }],
      followUpQuestions: [],
      retrievalStatus: "ok",
      generationStatus: "completed",
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();

    await wrapper.get("textarea").setValue("**用户问题**");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    const answer = wrapper.findAll(".assistant-answer").at(-1);
    expect(answer.find("h3").text()).toBe("资源建议");
    expect(answer.find("strong").text()).toContain("重点：");
    expect(answer.findAll("ol li")).toHaveLength(2);
    expect(answer.findAll("ul li")).toHaveLength(2);
    expect(answer.find("script").exists()).toBe(false);
    expect(answer.html()).not.toContain("javascript:");
    expect(wrapper.find(".chat-message.user strong").exists()).toBe(false);
    expect(wrapper.get(".chat-citations-label").text()).toBe("来源");
    expect(wrapper.get(".citation-chip").text()).toBe("资源说明");

    await wrapper.findAll(".chat-message.assistant").at(-1).get('button[aria-label="复制回答"]').trigger("click");
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining("### 资源建议"));
    expect(wrapper.get('button[aria-label="已复制"]').exists()).toBe(true);
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

  it("renders answer text before final and keeps the streaming cursor visible", async () => {
    let releaseFinal;
    const finalGate = new Promise((resolve) => { releaseFinal = resolve; });
    apiMock.stream = vi.fn(async (_path, body, options) => {
      options.onEvent("run.started", { runId: "run-live", conversationId: body.conversationId });
      options.onEvent("phase.started", { phase: "response", label: "正在生成回答" });
      options.onEvent("token", { delta: "第一段" });
      await finalGate;
      options.onEvent("token", { delta: "回答" });
      options.onEvent("final", {
        response: {
          answer: "第一段回答", conversationId: body.conversationId,
          citations: [], followUpQuestions: [], generationStatus: "completed"
        }
      });
      options.onEvent("done", {});
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("延迟流式问题");
    const submitPromise = wrapper.get("form").trigger("submit.prevent");
    await new Promise((resolve) => setTimeout(resolve, 30));
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain("第一段");
    expect(wrapper.find(".stream-cursor").exists()).toBe(true);
    expect(wrapper.text()).toContain("正在生成回答");

    releaseFinal();
    await submitPromise;
    await flushPromises();
    expect(wrapper.text()).toContain("第一段回答");
    expect(wrapper.find(".stream-cursor").exists()).toBe(false);
  });

  it("stops streaming and ignores late token events", async () => {
    let rejectStream;
    apiMock.stream = vi.fn(async (_path, _body, options) => {
      options.onEvent("token", { delta: "已经显示" });
      await new Promise((_resolve, reject) => {
        rejectStream = reject;
        options.signal.addEventListener("abort", () => {
          const error = new Error("aborted");
          error.name = "AbortError";
          reject(error);
        }, { once: true });
      });
      options.onEvent("token", { delta: "不应继续显示" });
    });
    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("停止流式问题");
    const submitPromise = wrapper.get("form").trigger("submit.prevent");
    await new Promise((resolve) => setTimeout(resolve, 30));
    await wrapper.get(".stop-button").trigger("click");
    rejectStream?.(new Error("aborted"));
    await submitPromise;
    await flushPromises();

    expect(wrapper.text()).toContain("已经显示");
    expect(wrapper.text()).not.toContain("不应继续显示");
    expect(wrapper.find(".stream-cursor").exists()).toBe(false);
  });

  it("keeps a completed greeting conversation in history after starting a new conversation", async () => {
    let historyReads = 0;
    const summary = {
      threadId: "greeting-thread", title: "你好，你可以做什么？", preview: "问候回答", messageCount: 2,
      scopeType: "SCHOOL", scopeId: "1", createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z"
    };
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      if (path === "/api/ai/qa/history") return historyReads++ === 0 ? [] : [summary];
      return [];
    });
    apiMock.stream = vi.fn(async (_path, body, options) => {
      options.onEvent("final", {
        response: {
          threadId: "greeting-thread", conversationId: body.conversationId, answer: "问候回答",
          citations: [], followUpQuestions: [], generationStatus: "degraded"
        }
      });
      options.onEvent("done", {});
    });

    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("你好，你可以做什么？");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.find(".history-row").exists()).toBe(true);
    expect(wrapper.text()).toContain("你好，你可以做什么？");
    await wrapper.get(".icon-action").trigger("click");
    expect(wrapper.find(".chat-message").exists()).toBe(false);
    expect(wrapper.find(".history-row").exists()).toBe(true);
    expect(wrapper.text()).toContain("问候回答");
  });

  it("refreshes history after the compatibility ask fallback succeeds", async () => {
    let historyReads = 0;
    apiMock.get.mockImplementation(async (path) => {
      if (path === "/api/ai/models") return [];
      if (path === "/api/ai/qa/history") {
        return historyReads++ === 0 ? [] : [{
          threadId: "thread-1", title: "兼容接口问题", preview: "兼容接口回答", messageCount: 2,
          scopeType: "SCHOOL", scopeId: "1", createdAt: "2026-07-28T00:00:00Z", updatedAt: "2026-07-28T01:00:00Z"
        }];
      }
      return [];
    });
    apiMock.stream = vi.fn(async () => {
      throw new Error("stream unavailable");
    });

    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("兼容接口问题");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/qa/ask", expect.objectContaining({ question: "兼容接口问题" }));
    expect(apiMock.get.mock.calls.filter(([path]) => path === "/api/ai/qa/history")).toHaveLength(2);
    expect(wrapper.find(".history-row").exists()).toBe(true);
  });

  it.each([
    ["empty", [], "未检索到直接匹配的知识证据"],
    ["degraded", [], "知识检索部分不可用，当前回答基于可用业务数据"],
    ["degraded", ["keyword-fallback"], "向量检索未启用或暂不可用，已使用关键词检索"],
    ["degraded", ["vector+hybrid-rerank"], "向量检索已完成，其他知识组件部分不可用"],
    ["ok", ["vector+hybrid-rerank"], "已完成向量检索与证据校验"],
  ])("renders the %s retrieval status", async (retrievalStatus, retrievalMethods, statusText) => {
    apiMock.post.mockResolvedValueOnce({ answer: "回答", citations: [], retrievalStatus, retrievalMethods });
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

  it("shows inferred candidates above the composer and lets the user confirm or ignore them", async () => {
    apiMock.post.mockImplementation(async (path) => {
      if (path === "/api/ai/qa/ask") {
        return {
          threadId: "thread-memory",
          answer: "我会按项目式教学来组织回答。",
          citations: [],
          retrievalStatus: "ok",
          generationStatus: "completed",
          memoryApplied: { count: 2, memoryIds: ["profile-1", "task-1"] },
          memoryCandidates: [
            {
              id: "candidate-1",
              memoryType: "PROFILE",
              fieldKey: "response_format",
              content: "偏好表格回答",
              status: "pending",
              source: "inferred_chat",
            },
            {
              id: "candidate-2",
              memoryType: "TASK",
              fieldKey: null,
              content: "正在准备红色研学活动",
              status: "pending",
              source: "inferred_chat",
            },
          ],
        };
      }
      return { id: "candidate-1", status: "active" };
    });

    const wrapper = mount(AssistantView, {
      attachTo: document.body,
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } },
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("帮我设计一个活动");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.text()).toContain("本次参考 2 条记忆");
    const suggestions = wrapper.get(".composer-memory-suggestions");
    expect(suggestions.text()).toContain("待确认的记忆建议（2）");
    expect(suggestions.text()).toContain("偏好表格回答");
    expect(suggestions.findAll(".composer-memory-candidate-card")).toHaveLength(2);
    expect(wrapper.find(".chat-message .composer-memory-candidate-card").exists()).toBe(false);
    expect(wrapper.get(".chat-composer").element.previousElementSibling).toBe(suggestions.element);

    await wrapper.get('[data-memory-id="candidate-1"] [data-action="confirm"]').trigger("click");
    await flushPromises();
    expect(apiMock.post).toHaveBeenCalledWith("/api/ai/memories/candidate-1/confirm");
    expect(wrapper.findAll(".composer-memory-candidate-card")).toHaveLength(1);
    expect(wrapper.get(".composer-memory-feedback").text()).toContain("已确认并保存这条记忆");

    await wrapper.get('[data-memory-id="candidate-2"] [data-action="ignore"]').trigger("click");
    await flushPromises();
    expect(apiMock.delete).toHaveBeenCalledWith("/api/ai/memories/candidate-2");
    expect(wrapper.find(".composer-memory-candidate-card").exists()).toBe(false);
    expect(wrapper.get(".composer-memory-feedback").text()).toContain("已忽略这条候选记忆");
    expect(document.activeElement).toBe(wrapper.get("textarea").element);
    wrapper.unmount();
  });

  it("clears successful composer memory feedback after five seconds", async () => {
    apiMock.post.mockImplementation(async (path) => {
      if (path === "/api/ai/qa/ask") {
        return {
          threadId: "thread-memory-feedback",
          answer: "这是带候选的回答。",
          citations: [],
          retrievalStatus: "ok",
          generationStatus: "completed",
          memoryCandidates: [{
            id: "candidate-feedback",
            memoryType: "PROFILE",
            fieldKey: "grade",
            content: "主要任教三年级数学",
            status: "pending",
            source: "inferred_chat",
          }],
        };
      }
      return { id: "candidate-feedback", status: "active" };
    });

    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } },
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("请记住我的任教情况");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    vi.useFakeTimers();
    try {
      await wrapper.get('[data-memory-id="candidate-feedback"] [data-action="confirm"]').trigger("click");
      await flushPromises();
      expect(wrapper.get(".composer-memory-feedback").text()).toContain("已确认并保存这条记忆");

      await vi.advanceTimersByTimeAsync(4_999);
      expect(wrapper.find(".composer-memory-feedback").exists()).toBe(true);

      await vi.advanceTimersByTimeAsync(1);
      await flushPromises();
      expect(wrapper.find(".composer-memory-feedback").exists()).toBe(false);
    } finally {
      vi.useRealTimers();
      wrapper.unmount();
    }
  });

  it("keeps an inferred candidate in the composer region when its action fails", async () => {
    apiMock.post.mockImplementation(async (path) => {
      if (path === "/api/ai/qa/ask") {
        return {
          threadId: "thread-memory-error",
          answer: "这是带候选的回答。",
          citations: [],
          retrievalStatus: "ok",
          generationStatus: "completed",
          memoryCandidates: [{
            id: "candidate-error",
            memoryType: "PROFILE",
            fieldKey: "grade",
            content: "主要任教三年级数学",
            status: "pending",
            source: "inferred_chat",
          }],
        };
      }
      if (path === "/api/ai/memories/candidate-error/confirm") {
        throw new Error("记忆服务暂不可用");
      }
      return { id: "candidate-error", status: "active" };
    });

    const wrapper = mount(AssistantView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } },
    });
    await flushPromises();
    await wrapper.get("textarea").setValue("请记住我的任教情况");
    await wrapper.get("form").trigger("submit.prevent");
    await flushPromises();

    await wrapper.get('[data-memory-id="candidate-error"] [data-action="confirm"]').trigger("click");
    await flushPromises();
    expect(wrapper.findAll(".composer-memory-candidate-card")).toHaveLength(1);
    expect(wrapper.get(".composer-memory-feedback").text()).toContain("记忆服务暂不可用");
  });
});
