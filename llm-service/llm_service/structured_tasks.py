from __future__ import annotations

from typing import Any

from .schemas import AgentMessageRequest, TrustedContext


RESOURCE_CATEGORIES = {
    "red_culture",
    "intangible_culture",
    "traditional_culture",
    "local_history",
    "public_culture",
    "labor_education",
    "public_welfare",
    "ecological_civilization",
    "patriotism_base",
    "social_practice",
    "other",
}


def task_context(request: AgentMessageRequest) -> dict[str, Any]:
    trusted = request.context
    retrieval = trusted.retrieval or {}
    payload = request.task_payload or {}
    context = {
        "taskType": request.task_type,
        "taskPayload": payload,
        "school": trusted.school or {},
        "resources": (trusted.resources or [])[:8],
        "retrieval": {
            "retrievalStatus": retrieval.get("retrievalStatus"),
            "chunks": (retrieval.get("chunks") or [])[:8],
            "graphFacts": (retrieval.get("graphFacts") or [])[:8],
        },
        "citationCandidateIds": _citation_ids(trusted),
    }
    if request.task_type == "RESOURCE_DISCOVERY":
        context["candidates"] = (payload.get("candidates") or [])[:20]
    return context


def teaching_plan_valid(value: dict[str, Any]) -> bool:
    return all(
        isinstance(value.get(field), list) and bool(value.get(field))
        for field in ("objectives", "activityFlow")
    )


def resource_discovery_valid(value: dict[str, Any]) -> bool:
    return isinstance(value.get("results"), list)


def normalize_teaching_plan(
    value: dict[str, Any], request: AgentMessageRequest, status: str = "completed"
) -> dict[str, Any]:
    payload = request.task_payload or {}
    trusted = request.context
    school_name = (trusted.school or {}).get("schoolName") or "当前学校"
    result = dict(value)
    result.setdefault("generationStatus", status)
    result.setdefault("message", "已根据受控业务上下文生成结构化教学方案。")
    result.setdefault("theme", payload.get("theme") or request.theme or "本土思政实践活动")
    result.setdefault("grade", payload.get("grade") or request.grade or "小学高年级")
    result.setdefault("activityType", payload.get("activityType") or "classroom")
    result.setdefault("durationMinutes", payload.get("durationMinutes"))
    result.setdefault("practiceRequired", bool(payload.get("practiceRequired")))
    for field in (
        "objectives", "resourceBasis", "activityFlow", "preparation", "fieldTasks",
        "safetyNotes", "reflection", "evaluation", "citations", "relatedResources",
        "followUpSuggestions",
    ):
        if not isinstance(result.get(field), list):
            result[field] = []
    allowed = set(_citation_ids(trusted))
    result["citations"] = [
        item for item in result["citations"]
        if isinstance(item, dict) and str(item.get("citationId") or "") in allowed
    ]
    if not result["resourceBasis"]:
        result["resourceBasis"] = [
            f"{name}：可作为“{result['theme']}”主题的真实情境资源。"
            for name in _resource_names(trusted)[:5]
        ] or [f"{school_name}周边暂未提供足够的结构化资源依据。"]
    return result


def teaching_plan_fallback(
    request: AgentMessageRequest,
    status: str = "degraded",
    message: str | None = None,
) -> dict[str, Any]:
    payload = request.task_payload or {}
    trusted = request.context
    school_name = (trusted.school or {}).get("schoolName") or "当前学校"
    theme = payload.get("theme") or request.theme or "本土思政实践活动"
    grade = payload.get("grade") or request.grade or "小学高年级"
    resources = _resource_names(trusted)
    basis = [f"{name}：可作为“{theme}”主题的真实情境资源。" for name in resources[:5]]
    if not basis:
        basis = [f"{school_name}周边资源数据较少，建议先补充可引用资源后再完善方案。"]
    return {
        "generationStatus": status,
        "message": message or "当前模型不可用，已基于学校周边已审核资源生成本地结构化方案。",
        "theme": theme,
        "grade": grade,
        "activityType": payload.get("activityType") or "classroom",
        "durationMinutes": payload.get("durationMinutes"),
        "practiceRequired": bool(payload.get("practiceRequired")),
        "objectives": [
            f"帮助{grade}学生理解“{theme}”与家乡真实资源之间的联系。",
            "引导学生在观察、讨论和实践中形成社会责任意识。",
        ],
        "resourceBasis": basis,
        "activityFlow": [
            f"课堂导入：从{school_name}的位置和周边资源切入主题。",
            "资源研读：分组阅读资源简介、教育价值和活动建议。",
            "实践体验：围绕资源完成观察记录、访谈或志愿服务任务。",
            "展示反思：整理任务成果并进行小组交流。",
        ],
        "preparation": [
            "教师提前核对资源开放状态、交通方式和安全要求。",
            "准备分组任务单、引用材料和活动记录表。",
        ],
        "fieldTasks": [
            "记录一个与主题相关的人物、故事、场景或服务对象。",
            "完成一条可以带回课堂讨论的学习发现。",
        ],
        "safetyNotes": [
            "校外活动应统一行动，明确带队教师和集合地点。",
            "开展服务活动时注意礼仪、隐私和现场秩序。",
        ],
        "reflection": [
            "学生完成活动感想或学习札记。",
            "班级围绕本地资源如何服务思政学习开展讨论。",
        ],
        "evaluation": ["从参与度、任务完成度、合作表现和反思表达四个方面评价。"],
        "citations": [{"citationId": item} for item in _citation_ids(trusted)[:6]],
        "relatedResources": resources[:5],
        "followUpSuggestions": [
            f"{school_name}还可以围绕哪些资源设计第二课时？",
            f"如何把“{theme}”沉淀为校本课程案例？",
        ],
    }


def normalize_resource_discovery(
    value: dict[str, Any], request: AgentMessageRequest, status: str = "completed"
) -> dict[str, Any]:
    candidates = request.task_payload.get("candidates") or []
    allowed_ids = {
        str(item.get("providerPlaceId"))
        for item in candidates
        if isinstance(item, dict) and item.get("providerPlaceId")
    }
    results = []
    for item in value.get("results") or []:
        if not isinstance(item, dict):
            continue
        place_id = str(item.get("providerPlaceId") or "")
        category = str(item.get("resourceCategory") or "other")
        confidence = item.get("confidence")
        try:
            confidence = max(0.0, min(1.0, float(confidence)))
        except (TypeError, ValueError):
            confidence = 0.0
        if place_id in allowed_ids and category in RESOURCE_CATEGORIES:
            results.append({**item, "confidence": confidence})
    return {
        "analysisStatus": status,
        "message": value.get("message") or "已完成候选思政资源识别。",
        "results": results,
    }


def resource_discovery_fallback(
    request: AgentMessageRequest, message: str | None = None
) -> dict[str, Any]:
    return {
        "analysisStatus": "unavailable",
        "message": message or "模型不可用，保留高德原始地点等待分析。",
        "results": [],
    }


def task_answer(request: AgentMessageRequest, result: dict[str, Any]) -> str:
    if request.task_type == "TEACHING_PLAN":
        return str(result.get("message") or "教学方案已生成。")
    if request.task_type == "RESOURCE_DISCOVERY":
        return str(result.get("message") or "已完成候选资源分析。")
    return ""


def _resource_names(trusted: TrustedContext) -> list[str]:
    names: list[str] = []
    for item in trusted.resources or []:
        resource = item.get("resource") if isinstance(item, dict) and isinstance(item.get("resource"), dict) else item
        if not isinstance(resource, dict):
            continue
        name = resource.get("resourceName") or resource.get("name")
        if name and str(name) not in names:
            names.append(str(name))
    return names


def _citation_ids(trusted: TrustedContext) -> list[str]:
    values: list[str] = []
    for item in trusted.citation_candidates or []:
        if isinstance(item, dict) and item.get("citationId"):
            values.append(str(item["citationId"]))
    retrieval = trusted.retrieval or {}
    for group in (retrieval.get("chunks") or [], retrieval.get("graphFacts") or []):
        for item in group:
            if isinstance(item, dict) and item.get("citationId"):
                value = str(item["citationId"])
                if value not in values:
                    values.append(value)
    return values
