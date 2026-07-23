from __future__ import annotations

import json
import time
from collections.abc import AsyncIterator
from typing import Any

from .model_gateway import ModelGateway
from .observability import LlmTraceContext
from .prompt_manager import PromptManager, PromptSelection


def compact_list(values: list[Any], limit: int = 6) -> list[str]:
    result: list[str] = []
    for value in values:
        text = str(value or "").strip()
        if text:
            result.append(text)
        if len(result) >= limit:
            break
    return result


def resource_names(payload: dict[str, Any]) -> list[str]:
    resources = payload.get("resources") or []
    return [
        str(item.get("resource", {}).get("resourceName"))
        for item in resources if item.get("resource", {}).get("resourceName")
    ]


def citation_ids(payload: dict[str, Any]) -> list[str]:
    candidates = payload.get("citationCandidates") or []
    return compact_list([item.get("citationId") for item in candidates if isinstance(item, dict)], 6)


def build_teaching_plan_fallback(payload: dict[str, Any], status: str = "degraded", message: str | None = None) -> dict[str, Any]:
    request = payload.get("request") or {}
    school = payload.get("school") or {}
    school_name = school.get("schoolName") or "当前学校"
    theme = request.get("theme") or "本土思政实践活动"
    grade = request.get("grade") or "小学高年级"
    resources = resource_names(payload)
    basis = [f"{name}：可作为“{theme}”主题的真实情境资源。" for name in resources[:5]]
    if not basis:
        basis = [f"{school_name}周边资源数据较少，建议先补充可引用资源后再完善方案。"]
    return {
        "generationStatus": status,
        "message": message or "当前使用 LLM 服务本地结构化兜底结果。",
        "theme": theme,
        "grade": grade,
        "activityType": request.get("activityType") or "classroom",
        "durationMinutes": request.get("durationMinutes"),
        "practiceRequired": bool(request.get("practiceRequired")),
        "objectives": [f"帮助{grade}学生理解“{theme}”与家乡真实资源之间的联系。", "引导学生在观察、讨论和实践中形成社会责任意识。"],
        "resourceBasis": basis,
        "activityFlow": [
            f"课堂导入：从{school_name}的位置和周边资源切入主题。",
            "资源研读：分组阅读资源简介、教育价值和活动建议。",
            "实践体验：围绕资源完成观察记录、访谈或志愿服务任务。",
            "展示反思：整理任务成果并进行小组交流。",
        ],
        "preparation": ["教师提前核对资源开放状态、交通方式和安全要求。", "准备分组任务单、引用材料和活动记录表。"],
        "fieldTasks": ["记录一个与主题相关的人物、故事、场景或服务对象。", "完成一条可以带回课堂讨论的学习发现。"],
        "safetyNotes": ["校外活动应统一行动，明确带队教师和集合地点。", "开展服务活动时注意礼仪、隐私和现场秩序。"],
        "reflection": ["学生完成活动感想或学习札记。", "班级围绕本地资源如何服务思政学习开展讨论。"],
        "evaluation": ["从参与度、任务完成度、合作表现和反思表达四个方面评价。"],
        "citations": [{"citationId": item} for item in citation_ids(payload)],
        "relatedResources": resources[:5],
        "followUpSuggestions": [f"{school_name}还可以围绕哪些资源设计第二课时？", f"如何把“{theme}”沉淀为校本课程案例？"],
    }


def teaching_plan_context(payload: dict[str, Any]) -> dict[str, Any]:
    candidates = payload.get("citationCandidates") or []
    context = {
        "request": payload.get("request") or {},
        "school": payload.get("school") or {},
        "resources": (payload.get("resources") or [])[:8],
        "contentChunks": (payload.get("contentChunks") or [])[:8],
        "graphFacts": (payload.get("graphFacts") or [])[:8],
        "citationCandidateIds": [item.get("citationId") for item in candidates if isinstance(item, dict)],
    }
    return context


def _teaching_prompt(payload: dict[str, Any], prompts: PromptManager) -> tuple[PromptSelection, str]:
    request = payload.get("request") or {}
    school = payload.get("school") or {}
    subject_key = str(school.get("schoolId") or request.get("schoolId") or "anonymous")
    return prompts.resolve("teaching-plan", subject_key, teaching_plan_context(payload)), subject_key


def _trace_identity(payload: dict[str, Any], fallback_session: str = "") -> tuple[str, str]:
    actor = payload.get("actor") if isinstance(payload.get("actor"), dict) else {}
    request = payload.get("request") if isinstance(payload.get("request"), dict) else {}
    school = payload.get("school") if isinstance(payload.get("school"), dict) else {}
    user_value = (
        actor.get("accountId") or payload.get("accountId") or payload.get("userId")
        or request.get("accountId") or school.get("schoolId") or request.get("schoolId")
    )
    session_value = (
        payload.get("sessionId") or payload.get("conversationId") or payload.get("requestId")
        or request.get("sessionId") or fallback_session
    )
    user_id = str(user_value) if user_value is not None else "anonymous"
    if user_value is not None and not (actor.get("accountId") or payload.get("accountId") or payload.get("userId")):
        user_id = f"school:{user_id}"
    return user_id, str(session_value or "")


def _normalize_generated_plan(generated: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    generated.setdefault("generationStatus", "completed")
    generated.setdefault("message", "已调用配置的 LLM 服务生成结构化教学方案。")
    allowed = set(citation_ids(payload))
    generated["citations"] = [
        item for item in generated.get("citations", [])
        if isinstance(item, dict) and item.get("citationId") in allowed
    ]
    return generated


def _valid_teaching_plan(generated: dict[str, Any]) -> bool:
    return all(
        isinstance(generated.get(field), list) and bool(generated.get(field))
        for field in ("objectives", "activityFlow")
    )


def _attach_prompt_metadata(
    result: dict[str, Any], selection: PromptSelection, run_id: str
) -> dict[str, Any]:
    result["promptVersion"] = selection.version
    result["promptRunId"] = run_id
    result["promptExperiment"] = selection.experiment_key
    result["promptVariant"] = selection.variant
    return result


async def build_structured_teaching_plan(
    payload: dict[str, Any], model: ModelGateway, prompts: PromptManager
) -> dict[str, Any]:
    selection, subject_key = _teaching_prompt(payload, prompts)
    run_id = prompts.start_run(selection, subject_key, model.settings.llm_model, len(selection.content))
    started = time.perf_counter()
    user_id, session_id = _trace_identity(payload, run_id)
    generated, model_metadata = await model.generate_json_with_metadata(
        selection.content,
        LlmTraceContext(
            feature="teaching-plan",
            user_id=user_id,
            session_id=session_id,
            trace_id=run_id,
            metadata={"promptVersion": selection.version, "promptRunId": run_id},
        ),
        _valid_teaching_plan,
    )
    if generated:
        result = _normalize_generated_plan(generated, payload)
        result["llmProvider"] = model_metadata.get("provider")
        result["llmModel"] = model_metadata.get("model")
        result["fallbackLevel"] = model_metadata.get("fallbackLevel")
        status = "completed"
    else:
        result = build_teaching_plan_fallback(payload)
        status = "degraded"
    elapsed = round((time.perf_counter() - started) * 1000)
    prompts.finish_run(run_id, status, elapsed, len(json.dumps(result, ensure_ascii=False)))
    return _attach_prompt_metadata(result, selection, run_id)


async def stream_structured_teaching_plan(
    payload: dict[str, Any], model: ModelGateway, prompts: PromptManager
) -> AsyncIterator[tuple[str, dict[str, Any]]]:
    selection, subject_key = _teaching_prompt(payload, prompts)
    run_id = prompts.start_run(selection, subject_key, model.settings.llm_model, len(selection.content))
    yield "meta", {
        "promptVersion": selection.version,
        "promptRunId": run_id,
        "promptExperiment": selection.experiment_key,
        "promptVariant": selection.variant,
    }
    started = time.perf_counter()
    raw_parts: list[str] = []
    error_message = ""
    generated: dict[str, Any] | None = None
    selected_model: dict[str, Any] = {}
    user_id, session_id = _trace_identity(payload, run_id)
    trace_context = LlmTraceContext(
        feature="teaching-plan-stream",
        user_id=user_id,
        session_id=session_id,
        trace_id=run_id,
        metadata={"promptVersion": selection.version, "promptRunId": run_id},
    )
    async for gateway_event, gateway_data in model.stream_json_events(
        selection.content, trace_context, _valid_teaching_plan
    ):
        if gateway_event == "attempt":
            selected_model = gateway_data
            yield "stage", {
                "stage": "generation",
                "message": f"正在调用 {gateway_data['model']} 生成教学方案",
                **gateway_data,
            }
        elif gateway_event == "token":
            token = str(gateway_data.get("delta") or "")
            raw_parts.append(token)
            yield "token", {"delta": token}
        elif gateway_event == "fallback":
            raw_parts.clear()
            error_message = str(gateway_data.get("errorType") or "model_failed")
            yield "fallback", {
                **gateway_data,
                "reset": True,
                "message": f"模型不可用，正在切换到 {gateway_data['nextModel']}",
            }
        elif gateway_event == "complete":
            generated = gateway_data.get("result")
            selected_model = gateway_data
        elif gateway_event == "exhausted":
            raw_parts.clear()
            error_message = "all configured models failed"
            yield "fallback", {
                "failedModel": selected_model.get("model"),
                "nextModel": "local-evidence-fallback",
                "errorType": "fallback_exhausted",
                "fallbackLevel": "local",
                "reset": True,
                "message": "模型服务暂不可用，正在生成基于检索证据的本地方案",
            }

    if generated:
        result = _normalize_generated_plan(generated, payload)
        result["llmProvider"] = selected_model.get("provider")
        result["llmModel"] = selected_model.get("model")
        result["fallbackLevel"] = selected_model.get("fallbackLevel")
        status = "completed"
    else:
        result = build_teaching_plan_fallback(
            payload,
            message="模型流不可用或返回格式无效，已生成本地结构化兜底方案。",
        )
        status = "degraded"
        fallback_text = json.dumps(result, ensure_ascii=False)
        if not raw_parts:
            for start in range(0, len(fallback_text), 18):
                yield "token", {"delta": fallback_text[start:start + 18]}
    elapsed = round((time.perf_counter() - started) * 1000)
    prompts.finish_run(
        run_id, status, elapsed, len("".join(raw_parts)) or len(json.dumps(result, ensure_ascii=False)), error_message
    )
    yield "result", _attach_prompt_metadata(result, selection, run_id)
    yield "done", {"promptRunId": run_id}


def build_resource_discovery_prompt(payload: dict[str, Any]) -> str:
    categories = [
        "red_culture", "intangible_culture", "traditional_culture", "local_history", "public_culture",
        "labor_education", "public_welfare", "ecological_civilization", "patriotism_base", "social_practice", "other",
    ]
    context = {"school": payload.get("school") or {}, "candidates": (payload.get("candidates") or [])[:20]}
    return (
        "你是乡村学校思政教育资源审核助手。只能分析输入 candidates 中已有地点，不得新增或修改事实。"
        "输出严格 JSON，顶层字段为 analysisStatus、message、results。results 每项包含 providerPlaceId、"
        "ideologicalRelevant、resourceCategory、resourceSubcategory、confidence、rationale、educationThemes、"
        "targetGrades、activitySuggestion、verificationNotes。resourceCategory 只能取 "
        f"{categories}；confidence 在 0 到 1 之间。\n\n上下文：{json.dumps(context, ensure_ascii=False)}"
    )


async def build_resource_discovery_classification(payload: dict[str, Any], model: ModelGateway) -> dict[str, Any]:
    candidates = (payload.get("candidates") or [])[:20]
    if not candidates:
        return {"analysisStatus": "completed", "message": "没有待分析地点。", "results": []}
    user_id, session_id = _trace_identity(payload)
    generated = await model.generate_json(
        build_resource_discovery_prompt(payload),
        LlmTraceContext(
            feature="resource-discovery",
            user_id=user_id,
            session_id=session_id,
            metadata={"candidateCount": len(candidates)},
        ),
        lambda value: isinstance(value.get("results"), list),
    )
    if not isinstance(generated, dict) or not isinstance(generated.get("results"), list):
        return {"analysisStatus": "unavailable", "message": "LLM 未配置或暂时不可用，保留高德原始地点等待分析。", "results": []}
    allowed_ids = {item.get("providerPlaceId") for item in candidates if isinstance(item, dict) and item.get("providerPlaceId")}
    generated["results"] = [item for item in generated["results"] if isinstance(item, dict) and item.get("providerPlaceId") in allowed_ids]
    generated.setdefault("analysisStatus", "completed")
    generated.setdefault("message", "已完成候选思政资源识别。")
    return generated


def build_map_answer(payload: dict[str, Any], school_mode: bool, ask_mode: bool) -> dict[str, Any]:
    if school_mode:
        school = payload.get("school") or {}
        name = school.get("schoolName") or "当前学校"
        resources = payload.get("resources") or []
        question = payload.get("question") or "请介绍学校周边思政资源"
        resource_values = [item.get("resource", {}).get("resourceName") for item in resources if item.get("resource", {}).get("resourceName")]
        if ask_mode:
            answer = f"围绕“{question}”，{name}可以从已入库的 {len(resources)} 个周边资源中选择主题最贴近的一组，形成课堂讲解、现场体验和反思展示的活动闭环。"
            citations = [f"问题：{question}", f"学校：{name}"]
        else:
            answer = f"{name}当前已关联 {len(resources)} 个周边思政教育资源。讲解时可以从学校位置出发，把真实资源转化为课堂导入、现场观察、实践服务和反思表达。"
            citations = [f"{name} 学校资源聚合结果", "当前回答由本地独立 LLM 服务生成"]
        return {"answer": answer, "citations": citations, "relatedResources": resource_values[:4], "followUpQuestions": [f"{name}有哪些资源适合低年级学生？", f"{name}周边资源如何转化为校本课程？"]}
    name = payload.get("regionName") or "当前乡镇"
    markers = payload.get("markers") or []
    question = payload.get("question") or "请介绍这里的红色文化"
    if ask_mode:
        answer = f"围绕“{question}”，可以先从{name}已加载的 {len(markers)} 个地图点位出发，串联遗址、事件、人物与故事。"
        citations = [f"问题：{question}", f"区域：{name}"]
    else:
        answer = f"{name}当前汇聚了 {len(markers)} 个地图资源点。建议先从空间定位切入，再串联遗址、事件、人物关系和研学价值。"
        citations = [f"{name} 地图聚合结果", "当前回答由本地独立 LLM 服务生成"]
    return {"answer": answer, "citations": citations, "relatedResources": [item.get("name") for item in markers[:4] if item.get("name")], "followUpQuestions": [f"{name}最适合优先讲解的资源是哪一个？", f"{name}有哪些人物和事件可以串联成研学路线？"]}
