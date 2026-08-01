export type AgentGenerationStatus = "completed" | "degraded" | "skipped" | "incomplete" | string;
export type AgentRetrievalStatus = "ok" | "empty" | "degraded" | string;
export type AgentTaskType = "CHAT" | "TEACHING_PLAN";
export type MemoryType = "PROFILE" | "TASK";
export type MemoryStatus = "pending" | "active" | "deleted";
export type MemorySource = "explicit_chat" | "inferred_chat" | "profile_ui" | "teaching_plan";

export interface AgentMemorySetting {
  available: boolean;
  enabled: boolean;
  effectiveEnabled: boolean;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface AgentMemoryItem {
  id: string;
  memoryType: MemoryType;
  fieldKey?: string | null;
  content: string;
  status: MemoryStatus;
  source: MemorySource;
  sourceThreadId?: string | null;
  confidence?: number | null;
  expiresAt?: string | null;
  deletedAt?: string | null;
  purgeAfter?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface AgentMemoryApplied {
  count: number;
  memoryIds: string[];
}

export interface AgentMemoryCreatePayload {
  memoryType: MemoryType;
  fieldKey?: string | null;
  content: string;
}

export interface AgentMemoryUpdatePayload {
  memoryType?: MemoryType;
  fieldKey?: string | null;
  content?: string;
}

export interface LlmModelOption {
  id: string;
  displayName: string;
  provider: string;
  model: string;
  isDefault: boolean;
}

export interface AssistantConversationSummary {
  threadId: string;
  scopeType: string;
  scopeId: string;
  title: string;
  preview: string;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AssistantConversationStoredMessage {
  id: number;
  role: "user" | "assistant" | string;
  content: string;
  createdAt: string;
  metadata?: Record<string, unknown>;
}

export interface AssistantConversationDetail {
  threadId: string;
  scopeType: string;
  scopeId: string;
  status: string;
  summary?: string;
  createdAt: string;
  updatedAt: string;
  messages: AssistantConversationStoredMessage[];
}

export interface AgentCitation {
  citationId?: string;
  title?: string | null;
  excerpt?: string | null;
  sourceType?: string | null;
  score?: number | null;
}
export interface AgentToolExecution {
  name?: string;
  toolName?: string;
  status?: string;
  durationMs?: number;
}

export interface TeachingPlanResponse {
  threadId?: string | null;
  generationStatus?: AgentGenerationStatus | null;
  retrievalStatus?: AgentRetrievalStatus | null;
  promptVersion?: string | null;
  promptRunId?: string | null;
  llmProvider?: string | null;
  llmModel?: string | null;
  fallbackLevel?: number | null;
  message?: string | null;
  theme?: string | null;
  grade?: string | null;
  activityType?: string | null;
  durationMinutes?: number | null;
  practiceRequired?: boolean | null;
  objectives?: string[];
  resourceBasis?: string[];
  activityFlow?: string[];
  preparation?: string[];
  fieldTasks?: string[];
  safetyNotes?: string[];
  reflection?: string[];
  evaluation?: string[];
  citations?: AgentCitation[];
  relatedResources?: string[];
  followUpSuggestions?: string[];
}

export interface AgentQaResponse {
  taskType?: AgentTaskType | string | null;
  answer?: string;
  intent?: string | null;
  retrievalStatus?: AgentRetrievalStatus | null;
  generationStatus?: AgentGenerationStatus | null;
  relatedResources?: string[];
  citations?: Array<AgentCitation | string>;
  followUpQuestions?: string[];
  clarificationRequired?: boolean;
  clarificationMessage?: string;
  clarificationOptions?: string[];
  conversationId?: string | null;
  threadId?: string | null;
  runId?: string | null;
  status?: string | null;
  toolExecutions?: AgentToolExecution[];
  fallbackLevel?: number | string | null;
  provider?: string | null;
  model?: string | null;
  teachingPlan?: TeachingPlanResponse | null;
  memoryCandidates?: AgentMemoryItem[] | null;
  memoryApplied?: AgentMemoryApplied | null;
}

export interface AgentThreadMessage {
  id: number;
  role: string;
  content: string;
  createdAt?: string;
  metadata?: Record<string, unknown>;
}

export interface AgentThreadHistoryResponse {
  threadId: string;
  ownerId?: string;
  scopeType?: string;
  scopeId?: string | number;
  status?: string;
  summary?: string;
  createdAt?: string;
  updatedAt?: string;
  messages?: AgentThreadMessage[];
}

export interface StatefulAgentRequest {
  taskType: AgentTaskType;
  taskPayload?: Record<string, unknown>;
  ownerId: string;
  scopeType: string;
  scopeId: number | null;
  threadId?: string | null;
  message: string;
  context?: Record<string, unknown>;
}

export interface AgentQaRequestPayload {
  question: string;
  threadId?: string | null;
  conversationId?: string | null;
  scopeType: string;
  scopeId: number | null;
  grade?: string | null;
  theme?: string | null;
  topK?: number;
  modelId?: string | null;
  attachments?: AgentAttachment[];
}

export interface AgentAttachment {
  type: "image";
  name: string;
  mediaType: "image/jpeg" | "image/png" | "image/webp" | "image/gif";
  dataUrl: string;
}

export type AgentSseEventName =
  | "run.started"
  | "phase.started"
  | "phase.completed"
  | "model.started"
  | "model.completed"
  | "model.failed"
  | "tool.started"
  | "tool.completed"
  | "token"
  | "plan.patch"
  | "final"
  | "error"
  | "done"
  | string;

export interface AgentSseEventData {
  runId?: string;
  threadId?: string;
  conversationId?: string;
  delta?: string;
  patch?: Partial<TeachingPlanResponse>;
  toolName?: string;
  name?: string;
  status?: string;
  errorType?: string;
  message?: string;
  phase?: string;
  label?: string;
  durationMs?: number;
  arguments?: Record<string, unknown>;
  outputSummary?: string;
  provider?: string;
  model?: string;
  response?: AgentQaResponse;
  [key: string]: unknown;
}

export interface AgentSseEvent {
  event: AgentSseEventName;
  data: AgentSseEventData;
}
