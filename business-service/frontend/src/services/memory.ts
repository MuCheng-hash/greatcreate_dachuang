import { ApiError, api } from "@/services/api";
import type {
  AgentMemoryCreatePayload,
  AgentMemoryConflictPreview,
  AgentMemoryItem,
  AgentMemorySetting,
  AgentMemoryUpdatePayload,
  MemoryStatus,
  MemoryType,
} from "@/types/agent";

function memoryPath(id: string, suffix = ""): string {
  return `/api/ai/memories/${encodeURIComponent(id)}${suffix}`;
}

export function memoryConflictPreviewFromError(error: unknown): AgentMemoryConflictPreview | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const payload = error.payload as { data?: unknown } | null;
  const data = payload?.data;
  if (!data || typeof data !== "object") return null;
  const preview = data as Partial<AgentMemoryConflictPreview>;
  if (!preview.candidate || !Array.isArray(preview.conflicts) || typeof preview.duplicate !== "boolean") return null;
  return preview as AgentMemoryConflictPreview;
}

export const memoryApi = {
  setting(): Promise<AgentMemorySetting> {
    return api.get<AgentMemorySetting>("/api/ai/memory-settings");
  },

  updateSetting(enabled: boolean): Promise<AgentMemorySetting> {
    return api.put<AgentMemorySetting>("/api/ai/memory-settings", { enabled });
  },

  list(status: MemoryStatus, memoryType?: MemoryType): Promise<AgentMemoryItem[]> {
    const query = new URLSearchParams({ status });
    if (memoryType) query.set("memoryType", memoryType);
    return api.get<AgentMemoryItem[]>(`/api/ai/memories?${query.toString()}`);
  },

  create(payload: AgentMemoryCreatePayload): Promise<AgentMemoryItem> {
    return api.post<AgentMemoryItem>("/api/ai/memories", payload);
  },

  update(id: string, payload: AgentMemoryUpdatePayload): Promise<AgentMemoryItem> {
    return api.patch<AgentMemoryItem>(memoryPath(id), payload);
  },

  confirmationPreview(id: string): Promise<AgentMemoryConflictPreview> {
    return api.get<AgentMemoryConflictPreview>(memoryPath(id, "/confirmation-preview"));
  },

  confirm(id: string, replaceConflicts = false): Promise<AgentMemoryItem> {
    return replaceConflicts
      ? api.post<AgentMemoryItem>(memoryPath(id, "/confirm"), { replaceConflicts: true })
      : api.post<AgentMemoryItem>(memoryPath(id, "/confirm"));
  },

  recycle(id: string): Promise<AgentMemoryItem> {
    return api.delete<AgentMemoryItem>(memoryPath(id));
  },

  restore(id: string, replaceConflicts = false): Promise<AgentMemoryItem> {
    return replaceConflicts
      ? api.post<AgentMemoryItem>(memoryPath(id, "/restore"), { replaceConflicts: true })
      : api.post<AgentMemoryItem>(memoryPath(id, "/restore"));
  },

  permanentlyDelete(id: string): Promise<void> {
    return api.delete<void>(memoryPath(id, "/permanent"));
  },
};
