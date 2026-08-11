import { api, ApiError } from "./api";

export class RequestError extends ApiError {}

function query(params?: Record<string, unknown>): string {
  if (!params) return "";
  const values = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => { if (value != null && value !== "") values.set(key, String(value)); });
  const text = values.toString();
  return text ? `?${text}` : "";
}

export async function get<T>(path: string, params?: Record<string, unknown>): Promise<T> { try { return await api.get<T>(`${path}${query(params)}`); } catch (error) { throw error instanceof ApiError ? new RequestError(error.message, error.status, error.code, error.payload) : error; } }
export async function post<T>(path: string, body: unknown = {}): Promise<T> { try { return await api.post<T>(path, body); } catch (error) { throw error instanceof ApiError ? new RequestError(error.message, error.status, error.code, error.payload) : error; } }
export async function del<T>(path: string): Promise<T> { try { return await api.delete<T>(path); } catch (error) { throw error instanceof ApiError ? new RequestError(error.message, error.status, error.code, error.payload) : error; } }
