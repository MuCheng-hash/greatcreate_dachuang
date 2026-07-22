from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class ApiModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")


class TrustedContext(ApiModel):
    school: dict[str, Any] | None = None
    region: dict[str, Any] | None = None
    resource: dict[str, Any] | None = None
    resources: list[dict[str, Any]] = Field(default_factory=list)
    retrieval: dict[str, Any] = Field(default_factory=dict)
    citation_candidates: list[dict[str, Any]] = Field(default_factory=list, alias="citationCandidates")


class AgentMessageRequest(ApiModel):
    owner_id: str = Field(alias="ownerId", min_length=1, max_length=160)
    scope_type: str = Field(alias="scopeType", min_length=1, max_length=32)
    scope_id: str | int = Field(alias="scopeId")
    message: str = Field(min_length=1, max_length=12000)
    thread_id: str | None = Field(default=None, alias="threadId", max_length=64)
    intent: str | None = Field(default=None, max_length=64)
    grade: str | None = Field(default=None, max_length=100)
    theme: str | None = Field(default=None, max_length=200)
    context: TrustedContext = Field(default_factory=TrustedContext)

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


class Citation(ApiModel):
    citation_id: str = Field(alias="citationId")
    title: str | None = None
    excerpt: str | None = None
    source_type: str | None = Field(default=None, alias="sourceType")
    score: float | None = None


class ToolExecution(ApiModel):
    name: str
    status: Literal["completed", "failed"]
    duration_ms: int = Field(alias="durationMs")


class AgentMessageResponse(ApiModel):
    thread_id: str = Field(alias="threadId")
    answer: str
    status: Literal["completed", "degraded", "incomplete"]
    citations: list[Citation] = Field(default_factory=list)
    related_resources: list[str] = Field(default_factory=list, alias="relatedResources")
    follow_up_questions: list[str] = Field(default_factory=list, alias="followUpQuestions")
    tool_executions: list[ToolExecution] = Field(default_factory=list, alias="toolExecutions")
    context_compacted: bool = Field(default=False, alias="contextCompacted")


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


class AgentModelOutput(ApiModel):
    answer: str
    citation_ids: list[str] = Field(default_factory=list, alias="citationIds")
    related_resources: list[str] = Field(default_factory=list, alias="relatedResources")
    follow_up_questions: list[str] = Field(default_factory=list, alias="followUpQuestions")
