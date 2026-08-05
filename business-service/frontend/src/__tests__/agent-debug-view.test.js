import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ get: vi.fn(), stream: vi.fn() }));
const authMock = vi.hoisted(() => ({ user: { schoolId: 1 }, schoolLabel: "里庄小学" }));
const schoolMock = vi.hoisted(() => ({ school: { schoolId: 1, schoolName: "里庄小学" }, load: vi.fn() }));

vi.mock("@/services/api", () => ({ api: apiMock }));
vi.mock("@/stores/auth", () => ({ useAuthStore: () => authMock }));
vi.mock("@/stores/school", () => ({ useSchoolStore: () => schoolMock }));

import AgentDebugView from "@/views/AgentDebugView.vue";

describe("agent debug view", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    schoolMock.load.mockResolvedValue(schoolMock);
    apiMock.get.mockResolvedValue([{ id: "qwen", displayName: "Qwen", provider: "bailian", model: "qwen-plus", isDefault: true }]);
  });

  it("renders the live execution graph, raw events, and final output", async () => {
    apiMock.stream.mockImplementation(async (path, body, options) => {
      expect(path).toBe("/api/ai/qa/stream");
      expect(body).toEqual(expect.objectContaining({ scopeType: "SCHOOL", scopeId: 1, modelId: "qwen", debug: true }));
      options.onEvent("run.started", { runId: "debug-run-1", provider: "bailian", model: "qwen-plus" });
      options.onEvent("phase.started", { phase: "retrieval", label: "正在检索可信知识" });
      options.onEvent("phase.completed", {
        phase: "retrieval",
        label: "知识与业务上下文已准备",
        retrievalTrace: {
          graphStatus: "ok",
          denseCandidateCount: 8,
          lexicalCandidateCount: 10,
          graphCandidateCount: 2,
          rerankedCandidateCount: 12,
          retrievalMethods: ["dense", "lexical", "rrf", "heuristic-rerank", "knowledge-graph"]
        }
      });
      options.onEvent("tool.started", { toolName: "retrieve_knowledge" });
      options.onEvent("tool.completed", { toolName: "retrieve_knowledge", status: "ok", durationMs: 12, outputSummary: "返回 2 条结果" });
      options.onEvent("token", { delta: "调试" });
      options.onEvent("final", { response: { runId: "debug-run-1", answer: "调试回答", provider: "bailian", model: "qwen-plus" } });
      options.onEvent("done", { runId: "debug-run-1" });
    });
    const wrapper = mount(AgentDebugView, {
      global: { stubs: { AppShell: { template: "<div><slot /></div>" }, InlineNotice: true } }
    });
    await flushPromises();
    await wrapper.get(".primary-button").trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("正在检索可信知识");
    expect(wrapper.text()).toContain("retrieve_knowledge · 完成");
    expect(wrapper.text()).toContain("返回 2 条结果");
    expect(wrapper.text()).toContain("Dense 召回");
    expect(wrapper.text()).toContain("Lexical 召回");
    expect(wrapper.text()).toContain("Graph 检索");
    expect(wrapper.text()).toContain("业务重排");
    expect(wrapper.text()).toContain("debug-run-1");
    expect(wrapper.text()).toContain("调试回答");
    expect(wrapper.findAll(".event-stream details")).toHaveLength(8);
  });
});
