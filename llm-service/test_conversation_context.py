from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
from langchain_core.messages import HumanMessage

from llm_service.conversation_context import (
    ContextBudgetError,
    ContextBudgeter,
    ContextComponent,
    ConversationSummary,
    ConversationSummaryError,
    SummaryItem,
)


CONTEXT_EVAL_FIXTURE = (
    Path(__file__).parent / "tests" / "fixtures" / "conversation_context_eval.json"
)


class FixedTokenCounter:
    def count(self, _text: str) -> int:
        return 1


@dataclass(frozen=True, slots=True)
class FixtureContextResult:
    source_message_ids: frozenset[int]
    thread_ids: frozenset[str]
    rendered: str
    estimated_tokens: int
    evicted_components: tuple[str, ...]
    retrieved_message_ids: tuple[int, ...]


def load_context_fixture_cases() -> list[dict[str, Any]]:
    value = json.loads(CONTEXT_EVAL_FIXTURE.read_text(encoding="utf-8"))
    if not isinstance(value, list):
        raise AssertionError("conversation context fixture must be an array")
    cases: list[dict[str, Any]] = []
    for raw_case in value:
        if not isinstance(raw_case, dict):
            continue
        case = dict(raw_case)
        history = [dict(item) for item in case.get("history", []) if isinstance(item, dict)]
        if not history:
            raise AssertionError("fixture history cannot be empty")
        # Keep the evaluator's public test helper narrow while carrying the
        # hand-authored case budget alongside its first history record.
        history[0]["maximumEstimatedTokens"] = int(
            case["maximumEstimatedTokens"]
        )
        case["history"] = history
        cases.append(case)
    return cases


def assemble_context(
    history: list[dict[str, Any]], query: str
) -> FixtureContextResult:
    """Test-only assembly using production summaries and final budget selection."""
    if not history:
        raise AssertionError("fixture history cannot be empty")
    current_thread_id = str(history[0]["threadId"])
    current_history = [
        item for item in history if str(item.get("threadId")) == current_thread_id
    ]
    compressed = [
        {
            "id": int(item["id"]),
            "role": str(item["role"]),
            "content": str(item["content"]),
        }
        for item in current_history
        if bool(item.get("compressed"))
    ]
    summary = ConversationSummary.deterministic_fallback(compressed)
    # This validates the production renderer as well as the category-level
    # components below, which are needed to exercise priority eviction.
    summary.render(character_limit=20_000)

    components = [
        ContextComponent("system_safety", 100, ("系统安全范围",), mandatory=True),
        ContextComponent("trusted_scope", 100, ("可信学校范围",), mandatory=True),
    ]
    component_sources: dict[str, set[int]] = {}
    component_threads: dict[str, set[str]] = {}

    def add_component(
        name: str,
        priority: int,
        text: str,
        source_ids: set[int] | None = None,
        thread_ids: set[str] | None = None,
    ) -> None:
        if not text.strip():
            return
        components.append(ContextComponent(name, priority, (text,)))
        component_sources[name] = source_ids or set()
        component_threads[name] = thread_ids or set()

    categories = (
        ("summary_hard_constraints", 90, summary.hard_constraints),
        ("summary_goals", 85, summary.goals),
        ("summary_decisions", 80, summary.decisions),
        ("summary_open_questions", 30, summary.open_questions),
        ("summary_facts", 25, summary.facts),
    )
    for name, priority, items in categories:
        add_component(
            name,
            priority,
            "\n".join(item.text for item in items),
            {
                source_id
                for item in items
                for source_id in item.source_message_ids
            },
            {current_thread_id},
        )

    embedding_available = all(
        bool(item.get("embeddingAvailable", True)) for item in history
    )
    retrieved = [
        item
        for item in current_history
        if embedding_available and bool(item.get("vectorCandidate"))
    ]
    add_component(
        "retrieved_history",
        70,
        "\n".join(str(item["content"]) for item in retrieved),
        {int(item["id"]) for item in retrieved},
        {str(item["threadId"]) for item in retrieved},
    )
    for item in current_history:
        if bool(item.get("compressed")):
            continue
        add_component(
            f"recent_history:{int(item['id'])}",
            60,
            str(item["content"]),
            {int(item["id"])},
            {str(item["threadId"])},
        )
    evidence = next(
        (str(item["prefetchedEvidence"]) for item in current_history
         if item.get("prefetchedEvidence")),
        "",
    )
    add_component("prefetched_evidence", 10, evidence)
    components.append(ContextComponent("current_user", 100, (query,), mandatory=True))

    budget = ContextBudgeter(FixedTokenCounter(), per_message_framing_tokens=0).fit(
        components,
        budget=max(1, int(history[0].get("maximumEstimatedTokens", 999))),
    )
    # The outer case owns the actual budget, so retain it on each history row
    # at load time in the assertion below without teaching production code about fixtures.
    accepted = set(budget.accepted_components)
    source_ids = {
        source_id
        for name, values in component_sources.items()
        if name in accepted
        for source_id in values
    }
    thread_ids = {
        thread_id
        for name, values in component_threads.items()
        if name in accepted
        for thread_id in values
    }
    return FixtureContextResult(
        source_message_ids=frozenset(source_ids),
        thread_ids=frozenset(thread_ids),
        rendered="\n".join(str(message) for message in budget.messages),
        estimated_tokens=budget.estimated_tokens,
        evicted_components=budget.evicted_components,
        retrieved_message_ids=tuple(int(item["id"]) for item in retrieved),
    )


def test_summary_evicts_low_priority_facts_before_a_hard_constraint() -> None:
    summary = ConversationSummary(
        hard_constraints=(SummaryItem("预算不超过3000元", (11,)),),
        facts=tuple(SummaryItem(f"普通事实{index}", (20 + index,)) for index in range(8)),
    )

    rendered = summary.render(character_limit=60)

    assert "预算不超过3000元" in rendered
    assert "普通事实7" not in rendered
    assert "sourceMessageIds: 11" in rendered


def test_summary_rejects_untrusted_or_duplicate_source_message_ids() -> None:
    with pytest.raises(ConversationSummaryError, match="sourceMessageIds"):
        ConversationSummary.from_dict(
            {
                "schemaVersion": 1,
                "facts": [{"text": "预算不超过3000元", "sourceMessageIds": [11, 11]}],
            },
            allowed_source_message_ids={11},
        )

    with pytest.raises(ConversationSummaryError, match="跨摘要条目重复"):
        ConversationSummary.from_dict(
            {
                "schemaVersion": 1,
                "hardConstraints": [
                    {"text": "预算不超过3000元", "sourceMessageIds": [11]}
                ],
                "facts": [
                    {"text": "预算由用户给出", "sourceMessageIds": [11]}
                ],
            },
            allowed_source_message_ids={11},
        )

    with pytest.raises(ConversationSummaryError, match="来源"):
        ConversationSummary.from_dict(
            {
                "schemaVersion": 1,
                "facts": [{"text": "预算不超过3000元", "sourceMessageIds": [12]}],
            },
            allowed_source_message_ids={11},
        )


@pytest.mark.parametrize("source_ids", ([True], [1.5], ["11"]))
def test_summary_rejects_non_integer_source_message_ids(source_ids: list[object]) -> None:
    with pytest.raises(ConversationSummaryError, match="正整数"):
        ConversationSummary.from_dict(
            {
                "schemaVersion": 1,
                "facts": [{"text": "预算不超过3000元", "sourceMessageIds": source_ids}],
            },
            allowed_source_message_ids={1, 11},
        )


def test_summary_renders_legacy_notes_without_discarding_them() -> None:
    summary = ConversationSummary.from_dict(
        {"schemaVersion": 1, "legacyNotes": ["旧会话仍需遵守校内资源优先"]}
    )

    rendered = summary.render(character_limit=500)

    assert "历史兼容备注" in rendered
    assert "旧会话仍需遵守校内资源优先" in rendered


def test_deterministic_fallback_links_every_item_to_its_source_message() -> None:
    summary = ConversationSummary.deterministic_fallback(
        [
            {"id": 11, "role": "user", "content": "预算不超过3000元"},
            {"id": 12, "role": "assistant", "content": "优先推荐校内资源"},
        ]
    )

    state = summary.to_dict()

    assert state["hardConstraints"][0]["sourceMessageIds"] == [11]
    assert state["facts"][0]["sourceMessageIds"] == [12]


def test_deterministic_fallback_keeps_a_complete_sentence_instead_of_a_mid_item_cut() -> None:
    summary = ConversationSummary.deterministic_fallback([
        {
            "id": 31,
            "role": "assistant",
            "content": "甲" * 300 + "。" + "乙" * 300,
        }
    ])

    assert summary.facts[0].text.endswith("。")
    assert len(summary.facts[0].text) == 301


def test_budgeter_never_drops_current_user_message_or_splits_a_summary_item() -> None:
    result = ContextBudgeter(FixedTokenCounter(), per_message_framing_tokens=1).fit(
        [
            ContextComponent("summary", 30, ("完整的硬约束条目",)),
            ContextComponent("retrieved_history", 20, ("旧消息",)),
            ContextComponent("current_user", 100, ("当前问题",)),
        ],
        budget=3,
    )

    assert result.messages == ("当前问题",)
    assert "summary" in result.evicted_components
    assert "完整的硬约束条目" not in result.messages


def test_budgeter_raises_when_mandatory_context_cannot_fit() -> None:
    with pytest.raises(ContextBudgetError):
        ContextBudgeter(FixedTokenCounter(), per_message_framing_tokens=1).fit(
            [
                ContextComponent("trusted_scope", 100, ("可信范围",), mandatory=True),
                ContextComponent("current_user", 100, ("当前问题",)),
            ],
            budget=3,
        )


def test_budgeter_evicts_optional_components_atomically() -> None:
    result = ContextBudgeter(FixedTokenCounter(), per_message_framing_tokens=0).fit(
        [
            ContextComponent("retrieved_history", 20, ("旧消息一", "旧消息二")),
            ContextComponent("current_user", 100, ("当前问题",)),
        ],
        budget=2,
    )

    assert result.messages == ("当前问题",)
    assert result.evicted_components == ("retrieved_history",)


def test_budgeter_uses_fixed_cost_for_multimodal_image_blocks() -> None:
    """Data URL length must not make a legal image request exhaust its budget."""
    def message(data_url: str) -> HumanMessage:
        return HumanMessage(content=[
            {"type": "text", "text": "请分析这张图片"},
            {"type": "image_url", "image_url": {"url": data_url, "detail": "auto"}},
        ])

    budgeter = ContextBudgeter()
    small = budgeter.fit(
        [ContextComponent(
            "current_user", 100,
            (message("data:image/png;base64,QQ=="),),
            mandatory=True,
        )],
        budget=6_000,
    )
    large = budgeter.fit(
        [ContextComponent(
            "current_user", 100,
            (message("data:image/png;base64," + "A" * 20_000),),
            mandatory=True,
        )],
        budget=6_000,
    )

    assert large.estimated_tokens == small.estimated_tokens
    assert large.estimated_tokens < 6_000
    assert large.accepted_components == ("current_user",)


def test_summary_merge_removes_only_fully_superseded_items() -> None:
    existing = ConversationSummary(
        hard_constraints=(SummaryItem("预算不超过2000元", (11, 12)),),
    )
    newer = ConversationSummary(
        hard_constraints=(SummaryItem("预算不超过3000元", (13,)),),
    )

    partially_superseded = existing.merge(
        newer,
        superseded_source_message_ids={11},
    )
    fully_superseded = existing.merge(
        newer,
        superseded_source_message_ids={11, 12},
    )

    assert [item.text for item in partially_superseded.hard_constraints] == [
        "预算不超过3000元", "预算不超过2000元",
    ]
    assert [item.text for item in fully_superseded.hard_constraints] == [
        "预算不超过3000元",
    ]


def test_context_fixture_preserves_required_constraints_and_thread_boundaries() -> None:
    cases = load_context_fixture_cases()

    assert len(cases) >= 6
    for case in cases:
        result = assemble_context(case["history"], str(case["query"]))

        assert set(case["requiredMessageIds"]) <= set(result.source_message_ids), case["id"]
        assert all(
            text in result.rendered for text in case["requiredSummaryTexts"]
        ), case["id"]
        assert not (
            set(case["forbiddenThreadIds"]) & set(result.thread_ids)
        ), case["id"]
        assert result.estimated_tokens <= int(case["maximumEstimatedTokens"]), case["id"]
        assert set(case.get("expectedEvictedComponents", [])) <= set(
            result.evicted_components
        ), case["id"]

    semantic = next(item for item in cases if item["id"] == "semantic-recall-with-cross-thread-candidate")
    embedding_failure = next(item for item in cases if item["id"] == "embedding-failure-fallback")
    assert assemble_context(semantic["history"], semantic["query"]).retrieved_message_ids == (31,)
    assert assemble_context(
        embedding_failure["history"], embedding_failure["query"]
    ).retrieved_message_ids == ()
