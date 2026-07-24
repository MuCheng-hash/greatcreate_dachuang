from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator


class CamelModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")


class AgentActor(CamelModel):
    account_id: int | None = Field(default=None, alias="accountId")
    role_code: str | None = Field(default=None, alias="roleCode")
    school_id: int | None = Field(default=None, alias="schoolId")


class AgentScope(CamelModel):
    scope_type: str = Field(default="SCHOOL", alias="scopeType")
    scope_id: int = Field(alias="scopeId")
    name: str | None = None


class AgentRuntimeRequest(CamelModel):
    question: str
    conversation_id: str | None = Field(default=None, alias="conversationId")
    scope: AgentScope | None = None
    scope_type: str | None = Field(default=None, alias="scopeType")
    scope_id: int | None = Field(default=None, alias="scopeId")
    actor: AgentActor = Field(default_factory=AgentActor)
    grade: str | None = None
    theme: str | None = None
    top_k: int = Field(default=5, alias="topK")

    @model_validator(mode="after")
    def normalize_scope(self) -> "AgentRuntimeRequest":
        if self.scope is None and self.scope_id is not None:
            self.scope = AgentScope(
                scopeType=self.scope_type or "SCHOOL",
                scopeId=self.scope_id,
            )
        if self.scope is None:
            raise ValueError("scopeId is required")
        if not self.question.strip():
            raise ValueError("question is required")
        self.top_k = max(1, min(self.top_k, 8))
        return self


class AgentFinalResponse(CamelModel):
    answer: str
    conversation_id: str = Field(alias="conversationId")
    run_id: str = Field(alias="runId")
    intent: str = "UNKNOWN"
    generation_status: str = Field(default="completed", alias="generationStatus")
    retrieval_status: str = Field(default="empty", alias="retrievalStatus")
    scope_type: str | None = Field(default=None, alias="scopeType")
    scope_id: int | None = Field(default=None, alias="scopeId")
    related_resources: list[str] = Field(default_factory=list, alias="relatedResources")
    citations: list[dict[str, Any]] = Field(default_factory=list)
    citation_ids: list[str] = Field(default_factory=list, alias="citationIds")
    follow_up_questions: list[str] = Field(default_factory=list, alias="followUpQuestions")
    clarification_required: bool = Field(default=False, alias="clarificationRequired")
    clarification_message: str | None = Field(default=None, alias="clarificationMessage")
    clarification_options: list[str] = Field(default_factory=list, alias="clarificationOptions")
    message: str | None = None
    fallback_level: str | None = Field(default=None, alias="fallbackLevel")
    input_tokens: int | None = Field(default=None, alias="inputTokens")
    output_tokens: int | None = Field(default=None, alias="outputTokens")


def dump_model(model: BaseModel) -> dict[str, Any]:
    return model.model_dump(by_alias=True, exclude_none=True)
