export class ApiError extends Error {
  constructor(message, status = 0, code = 0) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export async function apiRequest(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("Accept", "application/json");
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, {
    credentials: "same-origin",
    ...options,
    headers
  });

  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError("服务返回了无法识别的内容", response.status);
  }

  if (!response.ok || payload?.code !== 200) {
    const error = new ApiError(payload?.message || `请求失败 (${response.status})`, response.status, payload?.code);
    if (response.status === 401) {
      window.dispatchEvent(new CustomEvent("portal:unauthorized"));
    }
    throw error;
  }
  return payload.data;
}

export async function streamRequest(path, body, { signal, onEvent } = {}) {
  const response = await fetch(path, {
    method: "POST",
    credentials: "same-origin",
    signal,
    headers: {
      Accept: "text/event-stream",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    if (response.status === 401) {
      window.dispatchEvent(new CustomEvent("portal:unauthorized"));
    }
    let message = `请求失败 (${response.status})`;
    try {
      const payload = await response.json();
      message = payload?.message || message;
    } catch {
      // SSE errors may not have a JSON body.
    }
    throw new ApiError(message, response.status);
  }
  if (!response.body) {
    throw new ApiError("服务没有返回流式响应", response.status);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let lastEvent;

  const dispatch = (frame) => {
    const lines = frame.split(/\r?\n/);
    let eventName = "message";
    const dataLines = [];
    for (const line of lines) {
      if (line.startsWith("event:")) eventName = line.slice(6).trim();
      if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
    }
    if (!dataLines.length) return;
    let data;
    try {
      data = JSON.parse(dataLines.join("\n"));
    } catch {
      throw new ApiError("流式事件 JSON 无法解析");
    }
    lastEvent = { event: eventName, data };
    onEvent?.(eventName, data);
  };

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || "";
    for (const frame of frames) {
      if (frame.trim()) dispatch(frame);
    }
    if (done) break;
  }
  if (buffer.trim()) dispatch(buffer);
  return lastEvent;
}

export const api = {
  get(path) {
    return apiRequest(path);
  },
  post(path, body) {
    return apiRequest(path, { method: "POST", body: JSON.stringify(body) });
  },
  stream(path, body, options) {
    return streamRequest(path, body, options);
  },
  put(path, body) {
    return apiRequest(path, { method: "PUT", body: JSON.stringify(body) });
  }
};
