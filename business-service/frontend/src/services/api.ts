import type {
  AgentSseEvent,
  AgentSseEventData,
  AgentSseEventName,
} from "@/types/agent";

const DEFAULT_TIMEOUT_MS = 15_000;
const DEFAULT_STREAM_TIMEOUT_MS = 90_000;
const GET_RETRY_LIMIT = 2;

export class ApiError extends Error {
  readonly status: number;
  readonly code: number;
  readonly payload: unknown;

  constructor(message: string, status = 0, code = 0, payload: unknown = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.payload = payload;
  }
}

export interface ApiRequestOptions extends RequestInit {
  timeoutMs?: number;
  skipAuthRefresh?: boolean;
  retryGet?: boolean;
}

export interface StreamRequestOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
  onEvent?: (eventName: AgentSseEventName, data: AgentSseEventData) => void;
}

function isBrowser(): boolean {
  return typeof window !== "undefined" && typeof document !== "undefined";
}

function dispatchUnauthorized(): void {
  if (isBrowser()) {
    window.dispatchEvent(new CustomEvent("portal:unauthorized"));
  }
}

function readCookie(name: string): string | null {
  if (!isBrowser()) return null;
  const prefix = `${encodeURIComponent(name)}=`;
  const item = document.cookie.split(";").map((value) => value.trim()).find((value) => value.startsWith(prefix));
  if (!item) return null;
  try {
    return decodeURIComponent(item.slice(prefix.length));
  } catch {
    return item.slice(prefix.length);
  }
}

function isMutating(method: string): boolean {
  return ["POST", "PUT", "PATCH", "DELETE"].includes(method);
}

function isRetryableStatus(status: number): boolean {
  return status === 408 || status === 429 || status >= 500;
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => globalThis.setTimeout(resolve, ms));
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "网络请求失败";
}

function prepareHeaders(options: RequestInit, method: string): Headers {
  const headers = new Headers(options.headers || {});
  headers.set("Accept", headers.get("Accept") || "application/json");
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (isMutating(method)) {
    const csrfToken = readCookie("XSRF-TOKEN");
    if (csrfToken && !headers.has("X-CSRF-TOKEN")) {
      headers.set("X-CSRF-TOKEN", csrfToken);
    }
  }
  return headers;
}

function withTimeout(signal: AbortSignal | null | undefined, timeoutMs: number): {
  signal: AbortSignal;
  dispose: () => void;
} {
  const controller = new AbortController();
  const timeoutId = globalThis.setTimeout(() => controller.abort(), timeoutMs);
  const abort = () => controller.abort();
  if (signal) {
    if (signal.aborted) controller.abort();
    else signal.addEventListener("abort", abort, { once: true });
  }
  return {
    signal: controller.signal,
    dispose: () => {
      globalThis.clearTimeout(timeoutId);
      signal?.removeEventListener("abort", abort);
    },
  };
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    throw new ApiError(`服务返回了无法识别的内容（HTTP ${response.status}）`, response.status);
  }
}

async function refreshAccessToken(): Promise<boolean> {
  try {
    const timeout = withTimeout(null, DEFAULT_TIMEOUT_MS);
    try {
      const response = await fetch("/api/auth/refresh", {
        method: "POST",
        credentials: "include",
        headers: { Accept: "application/json" },
        signal: timeout.signal,
      });
      const payload = await readJson(response) as { code?: number };
      return response.ok && payload?.code === 200;
    } finally {
      timeout.dispose();
    }
  } catch {
    return false;
  }
}

async function fetchWithTimeout(path: string, options: RequestInit, timeoutMs: number): Promise<Response> {
  const timeout = withTimeout(options.signal, timeoutMs);
  try {
    return await fetch(path, { ...options, signal: timeout.signal });
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw new ApiError(`请求超时或网络不可用：${errorMessage(error)}`, 0);
  } finally {
    timeout.dispose();
  }
}

export async function apiRequest<T = unknown>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const method = (options.method || "GET").toString().toUpperCase();
  const maxRetries = method === "GET" && options.retryGet !== false ? GET_RETRY_LIMIT : 0;
  let retries = 0;
  let refreshAttempted = Boolean(options.skipAuthRefresh);

  while (true) {
    const headers = prepareHeaders(options, method);
    try {
      const response = await fetchWithTimeout(
        path,
        { ...options, credentials: "include", headers },
        options.timeoutMs || DEFAULT_TIMEOUT_MS,
      );

      if (response.status === 401 && !refreshAttempted && path !== "/api/auth/refresh") {
        refreshAttempted = true;
        if (await refreshAccessToken()) continue;
        dispatchUnauthorized();
      }

      const payload = await readJson(response) as { code?: number; message?: string; data?: T };
      if (!response.ok || payload?.code !== 200) {
        const error = new ApiError(
          payload?.message || `请求失败（HTTP ${response.status}）`,
          response.status,
          payload?.code || 0,
          payload,
        );
        if (response.status === 401) dispatchUnauthorized();
        if (retries < maxRetries && isRetryableStatus(response.status)) {
          retries += 1;
          await wait(200 * 2 ** (retries - 1));
          continue;
        }
        throw error;
      }
      return payload.data as T;
    } catch (error) {
      if (error instanceof ApiError && error.status !== 0) throw error;
      if (options.signal?.aborted) throw error;
      if (retries < maxRetries) {
        retries += 1;
        await wait(200 * 2 ** (retries - 1));
        continue;
      }
      throw error instanceof ApiError ? error : new ApiError(errorMessage(error));
    }
  }
}

export async function streamRequest(
  path: string,
  body: unknown,
  options: StreamRequestOptions = {},
): Promise<AgentSseEvent | undefined> {
  let refreshAttempted = false;
  while (true) {
    const headers = prepareHeaders(
      { body: JSON.stringify(body), headers: { Accept: "text/event-stream", "Content-Type": "application/json" } },
      "POST",
    );
    const timeout = withTimeout(options.signal, options.timeoutMs || DEFAULT_STREAM_TIMEOUT_MS);
    let response: Response;
    try {
      response = await fetch(path, {
        method: "POST",
        credentials: "include",
        signal: timeout.signal,
        headers,
        body: JSON.stringify(body),
      });
    } catch (error) {
      timeout.dispose();
      if (options.signal?.aborted) throw error;
      throw new ApiError(`流式请求超时或网络不可用：${errorMessage(error)}`, 0);
    }
    if (response.status === 401 && !refreshAttempted) {
      refreshAttempted = true;
      if (await refreshAccessToken()) {
        timeout.dispose();
        continue;
      }
      dispatchUnauthorized();
    }
    if (!response.ok) {
      timeout.dispose();
      let message = `请求失败（HTTP ${response.status}）`;
      try {
        const payload = await response.json() as { message?: string };
        message = payload?.message || message;
      } catch {
        // SSE error responses may not be JSON.
      }
      throw new ApiError(message, response.status);
    }
    if (!response.body) {
      timeout.dispose();
      throw new ApiError("服务没有返回流式响应", response.status);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    let lastEvent: AgentSseEvent | undefined;
    const dispatch = (frame: string): void => {
      const lines = frame.split(/\r?\n/);
      let eventName: AgentSseEventName = "message";
      const dataLines: string[] = [];
      for (const line of lines) {
        if (line.startsWith("event:")) eventName = line.slice(6).trim();
        if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
      }
      if (!dataLines.length) return;
      let data: AgentSseEventData;
      try {
        data = JSON.parse(dataLines.join("\n")) as AgentSseEventData;
      } catch (error) {
        throw new ApiError(`流式事件 JSON 无法解析：${errorMessage(error)}`);
      }
      lastEvent = { event: eventName, data };
      options.onEvent?.(eventName, data);
    };

    try {
      while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
        const frames = buffer.split(/\r?\n\r?\n/);
        buffer = frames.pop() || "";
        for (const frame of frames) if (frame.trim()) dispatch(frame);
        if (done) break;
      }
      if (buffer.trim()) dispatch(buffer);
      return lastEvent;
    } finally {
      timeout.dispose();
      reader.releaseLock();
    }
  }
}

export const api = {
  get<T = unknown>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    return apiRequest<T>(path, { ...options, method: "GET" });
  },
  post<T = unknown>(path: string, body: unknown = {}, options: ApiRequestOptions = {}): Promise<T> {
    return apiRequest<T>(path, { ...options, method: "POST", body: JSON.stringify(body) });
  },
  put<T = unknown>(path: string, body: unknown = {}, options: ApiRequestOptions = {}): Promise<T> {
    return apiRequest<T>(path, { ...options, method: "PUT", body: JSON.stringify(body) });
  },
  patch<T = unknown>(path: string, body: unknown = {}, options: ApiRequestOptions = {}): Promise<T> {
    return apiRequest<T>(path, { ...options, method: "PATCH", body: JSON.stringify(body) });
  },
  delete<T = unknown>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    return apiRequest<T>(path, { ...options, method: "DELETE" });
  },
  stream(path: string, body: unknown, options: StreamRequestOptions = {}): Promise<AgentSseEvent | undefined> {
    return streamRequest(path, body, options);
  },
};
