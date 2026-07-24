import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "@/services/api";

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("typed api client", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.stubGlobal("fetch", vi.fn());
    Object.defineProperty(document, "cookie", {
      configurable: true,
      value: "XSRF-TOKEN=csrf-value",
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends credentials and CSRF header for mutating requests", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(jsonResponse({ code: 200, data: { saved: true } }));

    await expect(api.post("/api/profile", { displayName: "李老师" })).resolves.toEqual({ saved: true });

    const [, init] = fetchMock.mock.calls[0];
    const headers = new Headers(init?.headers);
    expect(init?.credentials).toBe("include");
    expect(headers.get("X-CSRF-TOKEN")).toBe("csrf-value");
    expect(headers.get("Content-Type")).toBe("application/json");
  });

  it("refreshes once after 401 and retries the original request", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ code: 401, message: "expired" }, 401))
      .mockResolvedValueOnce(jsonResponse({ code: 200, data: { refreshed: true } }))
      .mockResolvedValueOnce(jsonResponse({ code: 200, data: { accountId: 7 } }));

    await expect(api.get("/api/auth/me")).resolves.toEqual({ accountId: 7 });
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      "/api/auth/me",
      "/api/auth/refresh",
      "/api/auth/me",
    ]);
  });

  it("retries transient GET failures with a bounded retry count", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ code: 500, message: "temporary" }, 500))
      .mockResolvedValueOnce(jsonResponse({ code: 200, data: { ok: true } }));

    await expect(api.get("/api/health")).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("parses typed SSE frames and preserves event order", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(new Response(
      "event: run.started\ndata: {\"runId\":\"run-1\"}\n\n"
      + "event: token\ndata: {\"runId\":\"run-1\",\"delta\":\"你好\"}\n\n"
      + "event: final\ndata: {\"runId\":\"run-1\",\"response\":{\"answer\":\"你好\"}}\n\n"
      + "event: done\ndata: {\"runId\":\"run-1\"}\n\n",
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    ));
    const events: string[] = [];

    await api.stream("/api/ai/qa/stream", { question: "你好" }, {
      onEvent: (eventName) => events.push(eventName),
    });

    expect(events).toEqual(["run.started", "token", "final", "done"]);
  });

  it("surfaces malformed SSE data as ApiError", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(new Response(
      "event: token\ndata: not-json\n\n",
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    ));

    await expect(api.stream("/api/ai/qa/stream", {})).rejects.toBeInstanceOf(ApiError);
  });
});
