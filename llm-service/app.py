from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Any, Optional

from flask import Flask, jsonify, request
from flask_cors import CORS


app = Flask(__name__)
CORS(app)


LLM_API_URL = os.getenv("LLM_API_URL", "").strip()
LLM_API_KEY = os.getenv("LLM_API_KEY", "").strip()
LLM_MODEL = os.getenv("LLM_MODEL", "qwen-plus").strip()
LLM_TIMEOUT_SECONDS = float(os.getenv("LLM_TIMEOUT_SECONDS", "20"))


def compact_list(values: list[Any], limit: int = 6) -> list[str]:
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
        resource = item.get("resource") or {}
        name = resource.get("resourceName")
        if name:
            names.append(str(name))
    return names


def citation_ids(payload: dict[str, Any]) -> list[str]:
    candidates = payload.get("citationCandidates") or []
    return compact_list([item.get("citationId") for item in candidates if isinstance(item, dict)], 6)


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


def build_teaching_plan_prompt(payload: dict[str, Any]) -> str:
    req = payload.get("request") or {}
    school = payload.get("school") or {}
    resources = payload.get("resources") or []
    chunks = payload.get("contentChunks") or []
    graph_facts = payload.get("graphFacts") or []
    candidates = payload.get("citationCandidates") or []
    context = {
        "request": req,
        "school": school,
        "resources": resources[:8],
        "contentChunks": chunks[:8],
        "graphFacts": graph_facts[:8],
        "citationCandidateIds": [item.get("citationId") for item in candidates if isinstance(item, dict)],
    }
    return (
        "你是乡村学校本土思政教育课程助手。只能依据给定上下文生成，不要编造来源。"
        "请输出严格 JSON，不要 Markdown。字段必须包含：generationStatus, message, theme, grade, "
        "activityType, durationMinutes, practiceRequired, objectives, resourceBasis, activityFlow, "
        "preparation, fieldTasks, safetyNotes, reflection, evaluation, citations, relatedResources, "
        "followUpSuggestions。citations 只允许使用 citationCandidateIds 中的 citationId。\n\n"
        f"上下文：{json.dumps(context, ensure_ascii=False)}"
    )


def call_openai_compatible(prompt: str) -> Optional[dict[str, Any]]:
    if not LLM_API_URL or not LLM_API_KEY:
        return None

    body = {
        "model": LLM_MODEL,
        "messages": [
            {"role": "system", "content": "Return valid JSON only."},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.3,
    }
    request_data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        LLM_API_URL,
        data=request_data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {LLM_API_KEY}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=LLM_TIMEOUT_SECONDS) as response:
            raw = response.read().decode("utf-8")
        data = json.loads(raw)
        content = data.get("choices", [{}])[0].get("message", {}).get("content")
        if not content:
            return None
        return json.loads(content)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, IndexError):
        return None


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


def build_structured_teaching_plan(payload: dict[str, Any]) -> dict[str, Any]:
    prompt = build_teaching_plan_prompt(payload)
    generated = call_openai_compatible(prompt)
    if generated:
        generated.setdefault("generationStatus", "completed")
        generated.setdefault("message", "已调用配置的 LLM 服务生成结构化教学方案。")
        return generated
    return build_teaching_plan_fallback(payload)


def build_resource_discovery_prompt(payload: dict[str, Any]) -> str:
    school = payload.get("school") or {}
    candidates = (payload.get("candidates") or [])[:20]
    context = {"school": school, "candidates": candidates}
    categories = [
        "red_culture", "intangible_culture", "traditional_culture", "local_history",
        "public_culture", "labor_education", "public_welfare", "ecological_civilization",
        "patriotism_base", "social_practice", "other",
    ]
    return (
        "你是乡村学校思政教育资源审核助手。只能分析输入 candidates 中已有的地点，"
        "不得新增地点、修改名称、地址或坐标。请根据地点名称、地图分类、地址和距离判断其是否可能具有思政教育价值。"
        "输出严格 JSON，不要 Markdown。顶层字段为 analysisStatus、message、results。"
        "results 每项必须包含 providerPlaceId、ideologicalRelevant、resourceCategory、resourceSubcategory、"
        "confidence、rationale、educationThemes、targetGrades、activitySuggestion、verificationNotes。"
        f"resourceCategory 只能取 {categories}；confidence 必须在 0 到 1 之间。"
        "地图信息只是候选线索，verificationNotes 必须说明需要人工核实的真实性、开放时间、联系方式或接待条件。\n\n"
        f"上下文：{json.dumps(context, ensure_ascii=False)}"
    )


def build_resource_discovery_classification(payload: dict[str, Any]) -> dict[str, Any]:
    candidates = (payload.get("candidates") or [])[:20]
    if not candidates:
        return {"analysisStatus": "completed", "message": "没有待分析地点。", "results": []}
    generated = call_openai_compatible(build_resource_discovery_prompt(payload))
    if not isinstance(generated, dict) or not isinstance(generated.get("results"), list):
        return {
            "analysisStatus": "unavailable",
            "message": "LLM 未配置或暂时不可用，保留高德原始地点等待分析。",
            "results": [],
        }
    allowed_ids = {
        item.get("providerPlaceId")
        for item in candidates
        if isinstance(item, dict) and item.get("providerPlaceId")
    }
    generated["results"] = [
        item for item in generated["results"]
        if isinstance(item, dict) and item.get("providerPlaceId") in allowed_ids
    ]
    generated.setdefault("analysisStatus", "completed")
    generated.setdefault("message", "已完成候选思政资源识别。")
    return generated


@app.post("/llm/town/explain")
def explain_town() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_explain_answer(payload))


@app.post("/llm/town/ask")
def ask_town() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_ask_answer(payload))


@app.post("/llm/school/explain")
def explain_school() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_school_explain_answer(payload))


@app.post("/llm/school/ask")
def ask_school() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_school_ask_answer(payload))


@app.post("/llm/teaching-plan/generate")
def generate_teaching_plan() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_structured_teaching_plan(payload))


@app.post("/llm/resource-discovery/classify")
def classify_resource_discovery() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_resource_discovery_classification(payload))


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5050, debug=True)
