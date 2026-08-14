import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api, withQuery } from "@/services/api";

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function pendingJsonResponse(payload: unknown, status = 401): {
  response: Response;
  cancel: ReturnType<typeof vi.fn>;
} {
  const cancel = vi.fn();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(JSON.stringify(payload)));
    },
    cancel,
  });
  return {
    response: new Response(body, {
      status,
      headers: { "Content-Type": "application/json" },
    }),
    cancel,
  };
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
    vi.useRealTimers();
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

  it("encodes query values and omits empty optional filters", () => {
    expect(withQuery("/api/admin/resources", {
      pageNum: 2,
      keyword: "红色 教育",
      approved: true,
      category: "",
      optional: null,
    })).toBe(
      "/api/admin/resources?pageNum=2&keyword=%E7%BA%A2%E8%89%B2+%E6%95%99%E8%82%B2&approved=true",
    );
    expect(withQuery("/api/admin/resources?scope=SCHOOL", { limit: 50 }))
      .toBe("/api/admin/resources?scope=SCHOOL&limit=50");
  });

  it("refreshes once after 401 and retries the original request", async () => {
    const fetchMock = vi.mocked(fetch);
    const stale = pendingJsonResponse({ code: 401, message: "expired" });
    fetchMock
      .mockResolvedValueOnce(stale.response)
      .mockResolvedValueOnce(jsonResponse({ code: 200, data: { refreshed: true } }))
      .mockImplementationOnce(async () => {
        expect(stale.cancel).toHaveBeenCalledTimes(1);
        return jsonResponse({ code: 200, data: { accountId: 7 } });
      });

    await expect(api.get("/api/auth/me")).resolves.toEqual({ accountId: 7 });
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      "/api/auth/me",
      "/api/auth/refresh",
      "/api/auth/me",
    ]);
  });

  it("shares one refresh request across concurrent 401 responses", async () => {
    const fetchMock = vi.mocked(fetch);
    const staleResponses = [
      pendingJsonResponse({ code: 401, message: "expired" }),
      pendingJsonResponse({ code: 401, message: "expired" }),
    ];
    let protectedCalls = 0;
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (path) => {
      if (path === "/api/auth/refresh") {
        refreshCalls += 1;
        await Promise.resolve();
        return jsonResponse({ code: 200, data: { refreshed: true } });
      }
      protectedCalls += 1;
      return protectedCalls <= 2
        ? staleResponses[protectedCalls - 1].response
        : jsonResponse({ code: 200, data: { ok: true } });
    });

    await expect(Promise.all([api.get("/api/one"), api.get("/api/two")]))
      .resolves.toEqual([{ ok: true }, { ok: true }]);
    expect(refreshCalls).toBe(1);
    expect(staleResponses[0].cancel).toHaveBeenCalledTimes(1);
    expect(staleResponses[1].cancel).toHaveBeenCalledTimes(1);
  });

  it("continues the retry when releasing a stale 401 response fails", async () => {
    const fetchMock = vi.mocked(fetch);
    const stale = pendingJsonResponse({ code: 401, message: "expired" });
    stale.cancel.mockRejectedValueOnce(new Error("cancel failed"));
    fetchMock
      .mockResolvedValueOnce(stale.response)
      .mockResolvedValueOnce(jsonResponse({ code: 200, data: { refreshed: true } }))
      .mockResolvedValueOnce(jsonResponse({ code: 200, data: { ok: true } }));

    await expect(api.get("/api/protected")).resolves.toEqual({ ok: true });
    expect(stale.cancel).toHaveBeenCalledTimes(1);
  });

  it("keeps the original 401 payload when token refresh fails", async () => {
    const fetchMock = vi.mocked(fetch);
    const stale = jsonResponse({ code: 401, message: "expired" }, 401);
    const cancel = vi.spyOn(stale.body!, "cancel");
    fetchMock
      .mockResolvedValueOnce(stale)
      .mockResolvedValueOnce(jsonResponse({ code: 500, message: "refresh failed" }, 500));

    await expect(api.get("/api/protected")).rejects.toMatchObject({
      status: 401,
      message: "expired",
      payload: { code: 401, message: "expired" },
    });
    expect(cancel).not.toHaveBeenCalled();
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

  it("aborts an SSE request that cannot establish a connection in time", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockImplementation((_path, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => {
        reject(new DOMException("aborted", "AbortError"));
      }, { once: true });
    }));

    const request = api.stream("/api/ai/qa/stream", {}, { connectTimeoutMs: 50 });
    const assertion = expect(request).rejects.toBeInstanceOf(ApiError);
    await vi.advanceTimersByTimeAsync(50);

    await assertion;
    expect(vi.getTimerCount()).toBe(0);
  });

  it("resets the SSE idle timeout whenever response data arrives", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.mocked(fetch);
    const streamController: {
      current?: ReadableStreamDefaultController<Uint8Array>;
    } = {};
    fetchMock.mockImplementation(async (_path, init) => {
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          streamController.current = controller;
          init?.signal?.addEventListener("abort", () => {
            controller.error(new DOMException("aborted", "AbortError"));
          }, { once: true });
        },
      });
      return new Response(body, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
      });
    });

    let settled = false;
    const request = api.stream("/api/ai/qa/stream", {}, {
      connectTimeoutMs: 50,
      idleTimeoutMs: 100,
    });
    void request.then(() => { settled = true; }, () => { settled = true; });
    await vi.advanceTimersByTimeAsync(0);

    streamController.current?.enqueue(new TextEncoder().encode(
      "event: token\ndata: {\"delta\":\"第一段\"}\n\n",
    ));
    await vi.advanceTimersByTimeAsync(80);
    streamController.current?.enqueue(new TextEncoder().encode(
      "event: token\ndata: {\"delta\":\"第二段\"}\n\n",
    ));
    await vi.advanceTimersByTimeAsync(80);
    expect(settled).toBe(false);

    const assertion = expect(request).rejects.toBeInstanceOf(ApiError);
    await vi.advanceTimersByTimeAsync(21);
    await assertion;
    expect(vi.getTimerCount()).toBe(0);
  });

  it("releases SSE timers when the user aborts the stream", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockImplementation(async (_path, init) => {
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          init?.signal?.addEventListener("abort", () => {
            controller.error(new DOMException("aborted", "AbortError"));
          }, { once: true });
        },
      });
      return new Response(body, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
      });
    });
    const controller = new AbortController();
    const removeListener = vi.spyOn(controller.signal, "removeEventListener");
    const request = api.stream("/api/ai/qa/stream", {}, {
      signal: controller.signal,
      idleTimeoutMs: 100,
    });
    const assertion = expect(request).rejects.toMatchObject({ name: "AbortError" });
    await vi.advanceTimersByTimeAsync(0);

    controller.abort();
    await assertion;

    expect(removeListener).toHaveBeenCalledWith("abort", expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });

  it("cleans the first SSE attempt before retrying after a 401", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.mocked(fetch);
    const stale = pendingJsonResponse({ code: 401, message: "expired" });
    let firstAttemptSignal: AbortSignal | undefined;
    let resolveRefresh: ((response: Response) => void) | undefined;
    fetchMock
      .mockImplementationOnce(async (_path, init) => {
        firstAttemptSignal = init?.signal || undefined;
        return stale.response;
      })
      .mockImplementationOnce(() => new Promise<Response>((resolve) => {
        resolveRefresh = resolve;
      }))
      .mockResolvedValueOnce(new Response(
        "event: done\ndata: {\"runId\":\"run-1\"}\n\n",
        { status: 200, headers: { "Content-Type": "text/event-stream" } },
      ));

    const request = api.stream("/api/ai/qa/stream", {}, { connectTimeoutMs: 20 });
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(25);
    expect(firstAttemptSignal?.aborted).toBe(false);

    resolveRefresh?.(jsonResponse({ code: 200, data: { refreshed: true } }));
    await expect(request).resolves.toMatchObject({
      event: "done",
    });

    expect(stale.cancel).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      "/api/ai/qa/stream",
      "/api/auth/refresh",
      "/api/ai/qa/stream",
    ]);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("keeps the original SSE 401 payload when token refresh fails", async () => {
    const fetchMock = vi.mocked(fetch);
    const stale = jsonResponse({ code: 401, message: "stream expired" }, 401);
    const cancel = vi.spyOn(stale.body!, "cancel");
    fetchMock
      .mockResolvedValueOnce(stale)
      .mockResolvedValueOnce(jsonResponse({ code: 500, message: "refresh failed" }, 500));

    await expect(api.stream("/api/ai/qa/stream", {})).rejects.toMatchObject({
      status: 401,
      message: "stream expired",
    });
    expect(cancel).not.toHaveBeenCalled();
  });
});
