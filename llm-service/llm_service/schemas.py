from __future__ import annotations

import base64
import binascii
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


TaskType = Literal["CHAT", "TEACHING_PLAN", "RESOURCE_DISCOVERY"]
MemoryType = Literal["PROFILE", "TASK"]
MemoryStatus = Literal["pending", "active", "deleted"]
MemorySource = Literal["explicit_chat", "inferred_chat", "profile_ui", "teaching_plan"]


class ApiModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")


class TrustedContext(ApiModel):
    actor: dict[str, Any] | None = None
    scope: dict[str, Any] | None = None
    school: dict[str, Any] | None = None
    region: dict[str, Any] | None = None
    resource: dict[str, Any] | None = None
    resources: list[dict[str, Any]] = Field(default_factory=list)
    retrieval: dict[str, Any] = Field(default_factory=dict)
    citation_candidates: list[dict[str, Any]] = Field(default_factory=list, alias="citationCandidates")


class AgentAttachment(ApiModel):
    type: Literal["image"] = "image"
    name: str = Field(min_length=1, max_length=180)
    media_type: Literal["image/jpeg", "image/png", "image/webp", "image/gif"] = Field(alias="mediaType")
    data_url: str = Field(alias="dataUrl", min_length=32, max_length=7_100_000)

    @model_validator(mode="after")
    def validate_data_url(self) -> "AgentAttachment":
        prefix = f"data:{self.media_type};base64,"
        if not self.data_url.startswith(prefix):
            raise ValueError("attachment dataUrl does not match mediaType")
        try:
            decoded = base64.b64decode(self.data_url[len(prefix):], validate=True)
        except (ValueError, binascii.Error) as exc:
            raise ValueError("attachment dataUrl is not valid base64") from exc
        if len(decoded) > 5 * 1024 * 1024:
            raise ValueError("attachment exceeds 5MB")
        return self


class AgentMessageRequest(ApiModel):
    owner_id: str = Field(alias="ownerId", min_length=1, max_length=160)
    scope_type: str = Field(alias="scopeType", min_length=1, max_length=32)
    scope_id: str | int = Field(alias="scopeId")
    message: str = Field(min_length=1, max_length=12000)
    thread_id: str | None = Field(default=None, alias="threadId", max_length=64)
    model_id: str | None = Field(default=None, alias="modelId", min_length=1, max_length=300)
    task_type: TaskType = Field(default="CHAT", alias="taskType")
    task_payload: dict[str, Any] = Field(default_factory=dict, alias="taskPayload")
    intent: str | None = Field(default=None, max_length=64)
    grade: str | None = Field(default=None, max_length=100)
    theme: str | None = Field(default=None, max_length=200)
    context: TrustedContext = Field(default_factory=TrustedContext)
    attachments: list[AgentAttachment] = Field(default_factory=list, max_length=3)

    @field_validator("message")
    @classmethod
    def clean_message(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("message must not be blank")
        return value

    @field_validator("scope_type")
    @classmethod
    def normalize_scope(cls, value: str) -> str:
        value = value.strip().upper()
        if value not in {"SCHOOL", "REGION", "RESOURCE"}:
            raise ValueError("scopeType must be SCHOOL, REGION, or RESOURCE")
        return value


class ThreadCreateRequest(ApiModel):
    owner_id: str = Field(alias="ownerId", min_length=1, max_length=160)
    scope_type: str = Field(alias="scopeType", min_length=1, max_length=32)
    scope_id: str | int = Field(alias="scopeId")

    @field_validator("scope_type")
    @classmethod
    def normalize_scope(cls, value: str) -> str:
        value = value.strip().upper()
        if value not in {"SCHOOL", "REGION", "RESOURCE"}:
            raise ValueError("scopeType must be SCHOOL, REGION, or RESOURCE")
        return value


class MemorySettingUpdateRequest(ApiModel):
    owner_id: str = Field(alias="ownerId", min_length=1, max_length=160)
    scope_type: str = Field(alias="scopeType", min_length=1, max_length=32)
    scope_id: str | int = Field(alias="scopeId")
    enabled: bool

    @field_validator("scope_type")
    @classmethod
    def normalize_scope(cls, value: str) -> str:
        value = value.strip().upper()
        if value not in {"SCHOOL", "REGION", "RESOURCE"}:
            raise ValueError("scopeType must be SCHOOL, REGION, or RESOURCE")
        return value


class MemorySettingResponse(ApiModel):
    available: bool
    enabled: bool
    effective_enabled: bool = Field(alias="effectiveEnabled")
    created_at: datetime | None = Field(default=None, alias="createdAt")
    updated_at: datetime | None = Field(default=None, alias="updatedAt")


class MemoryCreateRequest(ApiModel):
    owner_id: str = Field(alias="ownerId", min_length=1, max_length=160)
    scope_type: str = Field(alias="scopeType", min_length=1, max_length=32)
    scope_id: str | int = Field(alias="scopeId")
    memory_type: MemoryType = Field(alias="memoryType")
    field_key: str | None = Field(default=None, alias="fieldKey", max_length=64)
    content: str = Field(min_length=2, max_length=1000)
    status: MemoryStatus = "active"
    source: MemorySource = "profile_ui"
    source_thread_id: str | None = Field(
        default=None, alias="sourceThreadId", max_length=128
    )
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    replace_conflicts: bool = Field(default=False, alias="replaceConflicts")

    @field_validator("scope_type")
    @classmethod
    def normalize_scope(cls, value: str) -> str:
        value = value.strip().upper()
        if value not in {"SCHOOL", "REGION", "RESOURCE"}:
            raise ValueError("scopeType must be SCHOOL, REGION, or RESOURCE")
        return value


class MemoryUpdateRequest(ApiModel):
    memory_type: MemoryType | None = Field(default=None, alias="memoryType")
    field_key: str | None = Field(default=None, alias="fieldKey", max_length=64)
    content: str | None = Field(default=None, min_length=2, max_length=1000)
    replace_conflicts: bool = Field(default=False, alias="replaceConflicts")

    @model_validator(mode="after")
    def require_update(self) -> "MemoryUpdateRequest":
        if not ({"memory_type", "field_key", "content"} & self.model_fields_set):
            raise ValueError("at least one memory field is required")
        return self


class MemoryResolutionRequest(ApiModel):
    replace_conflicts: bool = Field(default=False, alias="replaceConflicts")


class MemoryItem(ApiModel):
    id: str
    memory_type: MemoryType = Field(alias="memoryType")
    field_key: str | None = Field(default=None, alias="fieldKey")
    content: str
    status: MemoryStatus
    source: MemorySource
    source_thread_id: str | None = Field(default=None, alias="sourceThreadId")
    confidence: float | None = None
    expires_at: datetime | None = Field(default=None, alias="expiresAt")
    deleted_at: datetime | None = Field(default=None, alias="deletedAt")
    purge_after: datetime | None = Field(default=None, alias="purgeAfter")
    created_at: datetime = Field(alias="createdAt")
    updated_at: datetime = Field(alias="updatedAt")


class MemoryConflictPreviewResponse(ApiModel):
    candidate: MemoryItem
    conflicts: list[MemoryItem] = Field(default_factory=list)
    duplicate: bool = False


class MemoryCandidateOutput(ApiModel):
    memory_type: MemoryType = Field(alias="memoryType")
    field_key: str | None = Field(default=None, alias="fieldKey", max_length=64)
    content: str = Field(min_length=2, max_length=1000)
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)


class MemoryApplied(ApiModel):
    count: int = Field(ge=0)
    memory_ids: list[str] = Field(default_factory=list, alias="memoryIds")


class Citation(ApiModel):
    citation_id: str = Field(alias="citationId")
    title: str | None = None
    excerpt: str | None = None
    source_type: str | None = Field(default=None, alias="sourceType")
    score: float | None = None


class ToolExecution(ApiModel):
    name: str
    status: Literal["completed", "degraded", "failed"]
    duration_ms: int = Field(alias="durationMs")


class AgentMessageResponse(ApiModel):
    thread_id: str = Field(alias="threadId")
    task_type: TaskType = Field(default="CHAT", alias="taskType")
    answer: str
    status: Literal["completed", "degraded", "incomplete"]
    generation_status: Literal["completed", "degraded", "skipped"] | None = Field(
        default=None, alias="generationStatus"
    )
    retrieval_status: str | None = Field(default=None, alias="retrievalStatus")
    retrieval_methods: list[str] = Field(default_factory=list, alias="retrievalMethods")
    provider: str | None = None
    model: str | None = None
    fallback_level: int | str | None = Field(default=None, alias="fallbackLevel")
    degraded_reason: str | None = Field(default=None, alias="degradedReason")
    citations: list[Citation] = Field(default_factory=list)
    related_resources: list[str] = Field(default_factory=list, alias="relatedResources")
    follow_up_questions: list[str] = Field(default_factory=list, alias="followUpQuestions")
    tool_executions: list[ToolExecution] = Field(default_factory=list, alias="toolExecutions")
    context_compacted: bool = Field(default=False, alias="contextCompacted")
    teaching_plan: dict[str, Any] | None = Field(default=None, alias="teachingPlan")
    resource_discovery: dict[str, Any] | None = Field(default=None, alias="resourceDiscovery")
    memory_candidates: list[MemoryItem] | None = Field(
        default=None, alias="memoryCandidates"
    )
    memory_applied: MemoryApplied | None = Field(default=None, alias="memoryApplied")


class StoredMessage(ApiModel):
    id: int
    role: str
    content: str
    created_at: datetime = Field(alias="createdAt")
    metadata: dict[str, Any] = Field(default_factory=dict)


class ThreadResponse(ApiModel):
    thread_id: str = Field(alias="threadId")
    owner_id: str = Field(alias="ownerId")
    scope_type: str = Field(alias="scopeType")
    scope_id: str = Field(alias="scopeId")
    status: str
    summary: str
    created_at: datetime = Field(alias="createdAt")
    updated_at: datetime = Field(alias="updatedAt")
    messages: list[StoredMessage] = Field(default_factory=list)


class ThreadSummaryResponse(ApiModel):
    thread_id: str = Field(alias="threadId")
    scope_type: str = Field(alias="scopeType")
    scope_id: str = Field(alias="scopeId")
    title: str
    preview: str
    message_count: int = Field(alias="messageCount")
    created_at: datetime = Field(alias="createdAt")
    updated_at: datetime = Field(alias="updatedAt")


class AgentModelOutput(ApiModel):
    answer: str
    citation_ids: list[str] = Field(default_factory=list, alias="citationIds")
    related_resources: list[str] = Field(default_factory=list, alias="relatedResources")
    follow_up_questions: list[str] = Field(default_factory=list, alias="followUpQuestions")
    memory_candidates: list[MemoryCandidateOutput] = Field(
        default_factory=list, alias="memoryCandidates"
    )
