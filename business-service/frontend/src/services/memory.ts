import { api } from "@/services/api";
import type {
  AgentMemoryCreatePayload,
  AgentMemoryItem,
  AgentMemorySetting,
  AgentMemoryUpdatePayload,
  MemoryStatus,
  MemoryType,
} from "@/types/agent";

function memoryPath(id: string, suffix = ""): string {
  return `/api/ai/memories/${encodeURIComponent(id)}${suffix}`;
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

  confirm(id: string): Promise<AgentMemoryItem> {
    return api.post<AgentMemoryItem>(memoryPath(id, "/confirm"));
  },

  recycle(id: string): Promise<AgentMemoryItem> {
    return api.delete<AgentMemoryItem>(memoryPath(id));
  },

  restore(id: string): Promise<AgentMemoryItem> {
    return api.post<AgentMemoryItem>(memoryPath(id, "/restore"));
  },

  permanentlyDelete(id: string): Promise<void> {
    return api.delete<void>(memoryPath(id, "/permanent"));
  },
};
