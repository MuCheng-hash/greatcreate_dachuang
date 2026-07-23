export type AgentGenerationStatus = "completed" | "degraded" | "skipped" | "incomplete" | string;
export type AgentRetrievalStatus = "ok" | "empty" | "degraded" | string;

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

export interface AgentQaResponse {
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
}

export type AgentSseEventName =
  | "run.started"
  | "model.started"
  | "model.completed"
  | "model.failed"
  | "tool.started"
  | "tool.completed"
  | "token"
  | "final"
  | "error"
  | "done"
  | string;

export interface AgentSseEventData {
  runId?: string;
  threadId?: string;
  conversationId?: string;
  delta?: string;
  toolName?: string;
  name?: string;
  status?: string;
  errorType?: string;
  message?: string;
  response?: AgentQaResponse;
  [key: string]: unknown;
}

export interface AgentSseEvent {
  event: AgentSseEventName;
  data: AgentSseEventData;
}
