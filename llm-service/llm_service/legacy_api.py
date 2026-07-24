from __future__ import annotations

import asyncio
import json
from typing import Any, Optional

from fastapi import APIRouter, Body, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import ValidationError
from llm_service.model_gateway import ModelGateway
from llm_service.observability import (
    FallbackAlertManager,
    LlmObservability,
    LlmTraceContext,
    configure_json_logging,
)
from llm_service.settings import Settings, get_settings
from llm_service.container import AppContainer, get_container


router = APIRouter(tags=["legacy-agent"])
configure_json_logging()
service_settings = get_settings()
LLM_API_URL = service_settings.llm_api_url
LLM_BASE_URL = service_settings.openai_base_url
LLM_API_KEY = service_settings.llm_api_key
LLM_MODEL = service_settings.llm_model


def compact_list(values: Any, limit: int = 6) -> list[str]:
    if isinstance(values, str):
        values = [values]
    if not isinstance(values, (list, tuple)):
        return []
    result: list[str] = []
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if text:
            result.append(text)
        if len(result) >= limit:
            break
    return result


def resource_names(payload: dict[str, Any]) -> list[str]:
    resources = payload.get("resources") or []
    names: list[str] = []
    for item in resources:
        if not isinstance(item, dict):
            continue
        resource = item.get("resource") or {}
        if not isinstance(resource, dict):
            continue
        name = resource.get("resourceName")
        if name:
            names.append(str(name))
    return names


def citation_ids(payload: dict[str, Any]) -> list[str]:
    candidates = payload.get("citationCandidates") or []
    return compact_list(
        [item.get("citationId") for item in candidates if isinstance(item, dict)],
        6,
    )

def build_teaching_plan_fallback(payload: dict[str, Any], status: str = "degraded", message: Optional[str] = None) -> dict[str, Any]:
    req = payload.get("request") or {}
    school = payload.get("school") or {}
    school_name = school.get("schoolName") or "当前学校"
    theme = req.get("theme") or "本土思政实践活动"
    grade = req.get("grade") or "小学高年级"
    resources = resource_names(payload)
    basis = [f"{name}：可作为“{theme}”主题的真实情境资源。" for name in resources[:5]]
    if not basis:
        basis = [f"{school_name}周边资源数据较少，建议先补充可引用资源后再完善方案。"]

    return {
        "generationStatus": status,
        "message": message or "当前使用 LLM 服务本地结构化兜底结果。",
        "theme": theme,
        "grade": grade,
        "activityType": req.get("activityType") or "classroom",
        "durationMinutes": req.get("durationMinutes"),
        "practiceRequired": bool(req.get("practiceRequired")),
        "objectives": [
            f"帮助{grade}学生理解“{theme}”与家乡真实资源之间的联系。",
            "引导学生在观察、讨论和实践中形成社会责任意识。"
        ],
        "resourceBasis": basis,
        "activityFlow": [
            f"课堂导入：从{school_name}的位置和周边资源切入主题。",
            "资源研读：分组阅读资源简介、教育价值和活动建议。",
            "实践体验：围绕资源完成观察记录、访谈或志愿服务任务。",
            "展示反思：整理任务成果并进行小组交流。"
        ],
        "preparation": [
            "教师提前核对资源开放状态、交通方式和安全要求。",
            "准备分组任务单、引用材料和活动记录表。"
        ],
        "fieldTasks": [
            "记录一个与主题相关的人物、故事、场景或服务对象。",
            "完成一条可以带回课堂讨论的学习发现。"
        ],
        "safetyNotes": [
            "校外活动应统一行动，明确带队教师和集合地点。",
            "开展服务活动时注意礼仪、隐私和现场秩序。"
        ],
        "reflection": [
            "学生完成活动感想或学习札记。",
            "班级围绕本地资源如何服务思政学习开展讨论。"
        ],
        "evaluation": [
            "从参与度、任务完成度、合作表现和反思表达四个方面评价。"
        ],
        "citations": [{"citationId": item} for item in citation_ids(payload)],
        "relatedResources": resources[:5],
        "followUpSuggestions": [
            f"{school_name}还可以围绕哪些资源设计第二课时？",
            f"如何把“{theme}”沉淀为校本课程案例？"
        ],
    }


def resolve_llm_api_url(settings: Settings | None = None) -> Optional[str]:
    settings = settings or service_settings
    configured_url = (settings.llm_api_url or settings.openai_base_url).strip()
    if not configured_url:
        return None
    normalized = configured_url.rstrip("/")
    return normalized if normalized.endswith("/chat/completions") else normalized + "/chat/completions"


def parse_model_json(content: Any) -> Optional[dict[str, Any]]:
    if isinstance(content, dict):
        return content
    if not isinstance(content, str):
        return None
    normalized = content.strip()
    if normalized.startswith("```"):
        normalized = normalized.split("\n", 1)[1] if "\n" in normalized else normalized
        if normalized.endswith("```"):
            normalized = normalized[:-3].rstrip()
    try:
        parsed = json.loads(normalized)
    except (TypeError, json.JSONDecodeError):
        return None
    return parsed if isinstance(parsed, dict) else None


def call_openai_compatible(
    prompt: str,
    trace_context: LlmTraceContext | None = None,
    gateway: ModelGateway | None = None,
) -> Optional[dict[str, Any]]:
    trace_context = trace_context or LlmTraceContext(feature="legacy-agent-answer")
    if gateway is None:
        from llm_service.observability import FallbackAlertManager, LlmObservability

        observability = LlmObservability(service_settings.database_path, service_settings.llm_model_pricing)
        alerts = FallbackAlertManager(service_settings.llm_alert_webhook_url)
        gateway = ModelGateway(service_settings, observability, alerts)
    return asyncio.run(gateway.generate_json(
        prompt,
        trace_context,
        lambda value: bool(str(value.get("answer") or "").strip()),
    ))


def build_explain_answer(payload: dict[str, Any]) -> dict[str, Any]:
    region_name = payload.get("regionName") or "当前乡镇"
    markers = payload.get("markers") or []
    hero_ids = payload.get("heroIds") or []
    event_ids = payload.get("eventIds") or []
    story_ids = payload.get("storyIds") or []

    answer = (
        f"{region_name}当前汇聚了 {len(markers)} 个地图资源点、"
        f"{len(hero_ids)} 位关联人物、{len(event_ids)} 个事件和 {len(story_ids)} 条故事。"
        f"在讲解时，建议先从空间定位切入，再串联遗址、事件与人物关系，最后落到故事精神和研学价值。"
    )

    return {
        "answer": answer,
        "citations": [
            f"{region_name} 地图聚合结果",
            "当前回答由本地独立 LLM 服务脚手架生成"
        ],
        "relatedResources": [item.get("name") for item in markers[:4] if item.get("name")],
        "followUpQuestions": [
            f"{region_name}最适合优先讲解的红色遗址是哪一个？",
            f"{region_name}有哪些人物和事件可以串联成研学路线？"
        ]
    }


def build_ask_answer(payload: dict[str, Any]) -> dict[str, Any]:
    region_name = payload.get("regionName") or "当前乡镇"
    question = payload.get("question") or "请介绍这里的红色文化"
    markers = payload.get("markers") or []

    answer = (
        f"围绕“{question}”，当前可以先从 {region_name} 已加载的 {len(markers)} 个地图点位出发，"
        f"优先组织遗址、事件和人物之间的事实关系，再补充故事化讲述。"
        f"这版服务是独立 LLM 骨架，后续可替换为真正的 Neo4j 检索 + LLM 生成链路。"
    )

    return {
        "answer": answer,
        "citations": [
            f"问题：{question}",
            f"区域：{region_name}"
        ],
        "relatedResources": [item.get("name") for item in markers[:4] if item.get("name")],
        "followUpQuestions": [
            f"{region_name}有哪些故事最适合继续追问？",
            f"{region_name}的英雄人物与事件之间有什么关系？"
        ]
    }


def build_school_explain_answer(payload: dict[str, Any]) -> dict[str, Any]:
    school = payload.get("school") or {}
    resources = payload.get("resources") or []
    activity_plans = payload.get("activityPlans") or []
    school_name = school.get("schoolName") or "当前学校"

    resource_names = [
        item.get("resource", {}).get("resourceName")
        for item in resources
        if item.get("resource", {}).get("resourceName")
    ]
    plan_themes = [
        item.get("theme")
        for item in activity_plans
        if item.get("theme")
    ]

    answer = (
        f"{school_name}当前已关联 {len(resources)} 个周边思政教育资源和 {len(activity_plans)} 条教学活动建议。"
        f"讲解时可以先从学校位置出发，选择距离近、可达性好的真实资源，"
        f"再把资源转化为课堂导入、现场观察、实践服务和反思表达四个环节。"
    )

    return {
        "answer": answer,
        "citations": [
            f"{school_name} 学校资源聚合结果",
            "当前回答由本地独立 LLM 服务脚手架生成"
        ],
        "relatedResources": resource_names[:4],
        "followUpQuestions": [
            f"{school_name}最适合优先使用哪个周边资源开展思政课？",
            f"{school_name}可以怎样设计一条敬老志愿服务路线？",
            f"{school_name}周边资源如何转化为校本课程？",
        ],
        "activityThemes": plan_themes[:4],
    }


def build_school_ask_answer(payload: dict[str, Any]) -> dict[str, Any]:
    school = payload.get("school") or {}
    resources = payload.get("resources") or []
    activity_plans = payload.get("activityPlans") or []
    question = payload.get("question") or "请介绍学校周边思政资源"
    school_name = school.get("schoolName") or "当前学校"

    nearby_resource_names = [
        item.get("resource", {}).get("resourceName")
        for item in resources
        if item.get("resource", {}).get("resourceName")
    ]

    answer = (
        f"围绕“{question}”，{school_name}可以从已入库的 {len(resources)} 个周边资源中选择主题最贴近的一组。"
        f"建议优先考虑步行或短距离可达资源，并结合 {len(activity_plans)} 条活动方案，"
        f"形成“课堂讲解 + 现场体验 + 服务实践 + 反思展示”的活动闭环。"
    )

    return {
        "answer": answer,
        "citations": [
            f"问题：{question}",
            f"学校：{school_name}"
        ],
        "relatedResources": nearby_resource_names[:4],
        "followUpQuestions": [
            f"{school_name}有哪些资源适合低年级学生？",
            f"{school_name}周边公益实践资源可以如何组织活动？",
        ],
    }


def agent_evidence_citation_ids(payload: dict[str, Any]) -> list[str]:
    retrieval = payload.get("retrievalResult") or {}
    values: list[str] = []
    for key in ("citationCandidates", "chunks", "graphFacts"):
        items = retrieval.get(key) or []
        values.extend(
            item.get("citationId")
            for item in items
            if isinstance(item, dict) and item.get("citationId")
        )
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        citation_id = str(value).strip()
        if citation_id and citation_id not in seen:
            result.append(citation_id)
            seen.add(citation_id)
        if len(result) >= 8:
            break
    return result


def build_agent_answer_prompt(payload: dict[str, Any]) -> str:
    scope = payload.get("scope") or {}
    business = payload.get("businessContext") or {}
    retrieval = payload.get("retrievalResult") or {}
    evidence = {
        "chunks": (retrieval.get("chunks") or [])[:8],
        "graphFacts": (retrieval.get("graphFacts") or [])[:8],
        "citationCandidateIds": agent_evidence_citation_ids(payload),
    }
    context = {
        "question": payload.get("question") or "",
        "intent": payload.get("intent") or "UNKNOWN",
        "scope": scope,
        "grade": payload.get("grade"),
        "theme": payload.get("theme"),
        "businessContext": {
            "school": business.get("school"),
            "resources": (business.get("resources") or [])[:8],
            "activityPlans": (business.get("activityPlans") or [])[:6],
            "resource": business.get("resource"),
            "matchedResource": business.get("matchedResource"),
        },
        "evidence": evidence,
    }
    return (
        "你是学校本土思政教育 Agent 的答案生成器。只能依据给定的业务上下文和检索证据回答，"
        "不能生成或执行 SQL、Cypher，也不能编造学校、资源、人物、来源或引用。"
        "请只输出严格 JSON，字段必须包含 answer、citationIds、followUpQuestions。"
        "citationIds 只能使用 evidence.citationCandidateIds 中出现的值；没有依据时返回空数组。"
        f"上下文：{json.dumps(context, ensure_ascii=False)}"
    )


def build_agent_answer_fallback(payload: dict[str, Any], message: Optional[str] = None) -> dict[str, Any]:
    scope = payload.get("scope") or {}
    business = payload.get("businessContext") or {}
    question = payload.get("question") or "当前问题"
    school = business.get("school") or {}
    school_name = school.get("schoolName") or scope.get("name") or "当前学校"
    resources = business.get("resources") or []
    names = [
        (item.get("resource") or {}).get("resourceName")
        for item in resources
        if isinstance(item, dict) and isinstance(item.get("resource"), dict)
        and (item.get("resource") or {}).get("resourceName")
    ]
    intent = payload.get("intent") or "UNKNOWN"
    if intent == "NEARBY_RESOURCE":
        answer = (
            f"围绕“{question}”，{school_name}当前查询到 {len(names)} 个已关联周边资源。"
            f"可以优先从{('、'.join(names[:5]) if names else '已审核的本土教育资源')}中，"
            "结合学生年级、距离和活动主题进行选择。"
        )
    elif intent == "TEACHING_SUGGESTION":
        answer = f"可以围绕{school_name}周边资源设计“课堂导入、现场观察、实践体验、反思展示”的活动闭环。"
    elif intent == "RESOURCE_EXPLANATION":
        answer = f"当前可以结合{school_name}的周边资源和检索证据，进一步说明资源背景、教育价值及适用年级。"
    elif intent == "RELATION_QUERY":
        answer = f"当前可以依据{school_name}的学校、资源和图谱关系证据，回答具体人物、学校与资源之间的关联。"
    else:
        answer = "请补充具体学校、资源或教学问题，我会结合已授权的业务数据进行回答。"

    return {
        "answer": answer,
        "citationIds": agent_evidence_citation_ids(payload)[:5],
        "followUpQuestions": [
            f"{school_name}有哪些资源适合四年级学生？",
            f"如何利用{school_name}周边资源设计一次实践活动？",
        ],
        "generationStatus": "degraded",
        "message": message or "当前使用 LLM 服务本地结构化兜底结果。",
    }


def build_agent_answer(
    payload: dict[str, Any],
    gateway: ModelGateway | None = None,
) -> dict[str, Any]:
    actor = payload.get("actor") if isinstance(payload.get("actor"), dict) else {}
    scope = payload.get("scope") if isinstance(payload.get("scope"), dict) else {}
    account_id = actor.get("accountId") or payload.get("accountId")
    school_id = actor.get("schoolId") or scope.get("scopeId")
    user_id = f"account:{account_id}" if account_id is not None else (
        f"school:{school_id}" if school_id is not None else "anonymous"
    )
    session_id = str(payload.get("conversationId") or payload.get("sessionId") or "")
    prompt = build_agent_answer_prompt(payload)
    trace_context = LlmTraceContext(
        feature="legacy-agent-answer",
        user_id=user_id,
        session_id=session_id,
        metadata={"intent": payload.get("intent") or "UNKNOWN"},
    )
    generated = call_openai_compatible(prompt, trace_context, gateway=gateway)
    available_ids = set(agent_evidence_citation_ids(payload))
    if isinstance(generated, dict) and generated.get("answer"):
        raw_ids = generated.get("citationIds") or []
        citation_ids = [
            str(item) for item in raw_ids
            if item is not None and str(item) in available_ids
        ] if isinstance(raw_ids, list) else []
        follow_ups = generated.get("followUpQuestions") or []
        return {
            "answer": str(generated.get("answer")),
            "citationIds": compact_list(citation_ids, 5),
            "followUpQuestions": compact_list(follow_ups, 4),
            "generationStatus": "completed",
            "message": "已根据受控业务上下文和检索证据生成回答。",
        }
    return build_agent_answer_fallback(payload, "真实 LLM 未配置或响应不可用，已使用本地兜底回答。")


@router.post("/llm/agent/answer")
def answer_agent(
    payload: dict[str, Any] | None = Body(default=None),
    container: AppContainer = Depends(get_container),
) -> Any:
    return build_agent_answer(payload or {}, container.model_gateway)


@router.post("/llm/agent/run")
def run_agent(
    payload: dict[str, Any] | None = Body(default=None),
    container: AppContainer = Depends(get_container),
) -> Any:
    try:
        return container.legacy_agent_runtime.run(payload or {})
    except ValidationError as exception:
        raise HTTPException(status_code=422, detail=exception.errors()) from exception
    except ValueError as exception:
        raise HTTPException(status_code=409, detail=str(exception)) from exception


@router.post("/llm/agent/stream")
def stream_agent(
    payload: dict[str, Any] | None = Body(default=None),
    container: AppContainer = Depends(get_container),
) -> StreamingResponse:
    return StreamingResponse(
        container.legacy_agent_runtime.stream_events(payload or {}),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
