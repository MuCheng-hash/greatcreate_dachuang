from __future__ import annotations

from typing import Any

from flask import Flask, jsonify, request
from flask_cors import CORS


app = Flask(__name__)
CORS(app)


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
            "当前回答由本地独立问答服务脚手架生成"
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
        f"这版服务是独立问答骨架，后续可替换为真正的 Neo4j 检索 + LLM 生成链路。"
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
            "当前回答由本地独立问答服务脚手架生成"
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


@app.post("/qa/town/explain")
def explain_town() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_explain_answer(payload))


@app.post("/qa/town/ask")
def ask_town() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_ask_answer(payload))


@app.post("/qa/school/explain")
def explain_school() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_school_explain_answer(payload))


@app.post("/qa/school/ask")
def ask_school() -> Any:
    payload = request.get_json(silent=True) or {}
    return jsonify(build_school_ask_answer(payload))


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5050, debug=True)
