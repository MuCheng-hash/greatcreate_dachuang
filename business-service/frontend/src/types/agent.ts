export type AgentGenerationStatus = "completed" | "degraded" | "skipped" | "incomplete" | string;
export type AgentRetrievalStatus = "ok" | "empty" | "degraded" | string;
export type AgentTaskType = "CHAT" | "TEACHING_PLAN" | "RESOURCE_DISCOVERY";

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

export interface ResourceDiscoveryResult {
  providerPlaceId?: string | null;
  ideologicalRelevant?: boolean | null;
  resourceCategory?: string | null;
  resourceSubcategory?: string | null;
  confidence?: number | null;
  rationale?: string | null;
  educationThemes?: string[];
  targetGrades?: string | null;
  activitySuggestion?: string | null;
  verificationNotes?: string | null;
}

export interface ResourceDiscoveryResponse {
  analysisStatus?: string | null;
  message?: string | null;
  results?: ResourceDiscoveryResult[];
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
  teachingPlan?: TeachingPlanResponse | null;
  resourceDiscovery?: ResourceDiscoveryResponse | null;
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
}

export type AgentSseEventName =
  | "run.started"
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
  response?: AgentQaResponse;
  [key: string]: unknown;
}

export interface AgentSseEvent {
  event: AgentSseEventName;
  data: AgentSseEventData;
}
