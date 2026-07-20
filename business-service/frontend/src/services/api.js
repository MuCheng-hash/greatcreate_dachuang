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

export const api = {
  get(path) {
    return apiRequest(path);
  },
  post(path, body) {
    return apiRequest(path, { method: "POST", body: JSON.stringify(body) });
  },
  put(path, body) {
    return apiRequest(path, { method: "PUT", body: JSON.stringify(body) });
  }
};
