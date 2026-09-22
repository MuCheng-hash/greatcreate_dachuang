from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Mapping, Protocol


SUMMARY_CATEGORIES = (
    "goals",
    "hardConstraints",
    "decisions",
    "openQuestions",
    "facts",
)
SUMMARY_RENDER_ORDER = (
    "hardConstraints",
    "goals",
    "decisions",
    "openQuestions",
    "facts",
    "legacyNotes",
)
SUMMARY_LABELS = {
    "goals": "目标",
    "hardConstraints": "硬约束",
    "decisions": "已确认决策",
    "openQuestions": "待确认问题",
    "facts": "事实与上下文",
    "legacyNotes": "历史兼容备注",
}
DEFAULT_SUMMARY_ITEM_CHARACTER_LIMIT = 500
MULTIMODAL_IMAGE_TOKEN_ALLOWANCE = 1024
_MULTIMODAL_TEXT_BLOCK_TYPES = frozenset(("text", "input_text"))
_MULTIMODAL_IMAGE_BLOCK_TYPES = frozenset(("image_url", "input_image", "image"))


def _bounded_source_excerpt(content: str, character_limit: int) -> str:
    """Return a complete sentence/word excerpt, never an arbitrary text cut."""
    if len(content) <= character_limit:
        return content
    sentence_end = max(
        (content.rfind(marker, 0, character_limit) for marker in "。！？；.!?;"),
        default=-1,
    )
    if sentence_end >= 0:
        return content[: sentence_end + 1].strip()
    word_end = max(content.rfind(" ", 0, character_limit), content.rfind("\n", 0, character_limit))
    return content[:word_end].strip() if word_end > 0 else ""


class ConversationSummaryError(ValueError):
    """结构化会话摘要不满足来源或边界约束。"""


class ContextBudgetError(ValueError):
    """强制保留的系统/用户上下文已经超过应用预算。"""


@dataclass(frozen=True, slots=True)
class SummaryItem:
    text: str
    source_message_ids: tuple[int, ...]

    def __post_init__(self) -> None:
        normalized = " ".join(str(self.text).split())
        if not normalized:
            raise ConversationSummaryError("摘要条目不能为空")
        if len(normalized) > DEFAULT_SUMMARY_ITEM_CHARACTER_LIMIT:
            raise ConversationSummaryError("摘要条目超过长度上限")
        normalized_ids = tuple(self.source_message_ids)
        if any(
            isinstance(value, bool) or not isinstance(value, int)
            for value in normalized_ids
        ):
            raise ConversationSummaryError("sourceMessageIds 必须包含正整数")
        if not normalized_ids or any(value <= 0 for value in normalized_ids):
            raise ConversationSummaryError("sourceMessageIds 必须包含正整数")
        if len(set(normalized_ids)) != len(normalized_ids):
            raise ConversationSummaryError("sourceMessageIds 不能重复")
        object.__setattr__(self, "text", normalized)
        object.__setattr__(self, "source_message_ids", normalized_ids)

    def to_dict(self) -> dict[str, object]:
        return {
            "text": self.text,
            "sourceMessageIds": list(self.source_message_ids),
        }


@dataclass(frozen=True, slots=True)
class ConversationSummary:
    goals: tuple[SummaryItem, ...] = ()
    hard_constraints: tuple[SummaryItem, ...] = ()
    decisions: tuple[SummaryItem, ...] = ()
    open_questions: tuple[SummaryItem, ...] = ()
    facts: tuple[SummaryItem, ...] = ()
    legacy_notes: tuple[str, ...] = ()
    schema_version: int = 1

    def __post_init__(self) -> None:
        if self.schema_version != 1:
            raise ConversationSummaryError("不支持的摘要 schemaVersion")
        seen_source_ids: set[int] = set()
        for value in self._category_values().values():
            self._ensure_unique_items(value)
            for item in value:
                duplicate_ids = seen_source_ids.intersection(item.source_message_ids)
                if duplicate_ids:
                    raise ConversationSummaryError(
                        "sourceMessageIds 不能跨摘要条目重复"
                    )
                seen_source_ids.update(item.source_message_ids)
        normalized_notes = tuple(
            " ".join(str(note).split()) for note in self.legacy_notes
        )
        if any(not note for note in normalized_notes):
            raise ConversationSummaryError("legacyNotes 不能包含空值")
        if any(len(note) > DEFAULT_SUMMARY_ITEM_CHARACTER_LIMIT for note in normalized_notes):
            raise ConversationSummaryError("legacyNotes 超过长度上限")
        object.__setattr__(self, "legacy_notes", tuple(dict.fromkeys(normalized_notes)))

    @classmethod
    def from_dict(
        cls,
        value: Mapping[str, object],
        *,
        allowed_source_message_ids: set[int] | None = None,
        item_character_limit: int = DEFAULT_SUMMARY_ITEM_CHARACTER_LIMIT,
    ) -> ConversationSummary:
        if not isinstance(value, Mapping):
            raise ConversationSummaryError("摘要状态必须是对象")
        if value.get("schemaVersion", 1) != 1:
            raise ConversationSummaryError("不支持的摘要 schemaVersion")
        if item_character_limit < 1:
            raise ConversationSummaryError("摘要条目长度上限必须为正数")
        values: dict[str, tuple[SummaryItem, ...]] = {}
        all_source_message_ids: set[int] = set()
        for category in SUMMARY_CATEGORIES:
            raw_items = value.get(category, [])
            if not isinstance(raw_items, (list, tuple)):
                raise ConversationSummaryError(f"{category} 必须是数组")
            parsed: list[SummaryItem] = []
            for raw_item in raw_items:
                if not isinstance(raw_item, Mapping):
                    raise ConversationSummaryError(f"{category} 条目必须是对象")
                raw_ids = raw_item.get("sourceMessageIds")
                if not isinstance(raw_ids, (list, tuple)):
                    raise ConversationSummaryError("sourceMessageIds 必须是数组")
                item = SummaryItem(
                    str(raw_item.get("text") or ""),
                    tuple(raw_ids),
                )
                if len(item.text) > item_character_limit:
                    raise ConversationSummaryError("摘要条目超过长度上限")
                if (
                    allowed_source_message_ids is not None
                    and not set(item.source_message_ids).issubset(allowed_source_message_ids)
                ):
                    raise ConversationSummaryError("摘要条目的来源消息不在本次压缩范围内")
                if all_source_message_ids.intersection(item.source_message_ids):
                    raise ConversationSummaryError(
                        "sourceMessageIds 不能跨摘要条目重复"
                    )
                all_source_message_ids.update(item.source_message_ids)
                parsed.append(item)
            cls._ensure_unique_items(tuple(parsed))
            values[category] = tuple(parsed)

        raw_notes = value.get("legacyNotes", [])
        if not isinstance(raw_notes, (list, tuple)) or not all(
            isinstance(note, str) for note in raw_notes
        ):
            raise ConversationSummaryError("legacyNotes 必须是字符串数组")
        notes = tuple(" ".join(note.split()) for note in raw_notes)
        if any(not note for note in notes):
            raise ConversationSummaryError("legacyNotes 不能包含空值")
        if any(len(note) > item_character_limit for note in notes):
            raise ConversationSummaryError("legacyNotes 超过长度上限")
        return cls(
            goals=values["goals"],
            hard_constraints=values["hardConstraints"],
            decisions=values["decisions"],
            open_questions=values["openQuestions"],
            facts=values["facts"],
            legacy_notes=tuple(dict.fromkeys(notes)),
        )

    @classmethod
    def deterministic_fallback(
        cls,
        messages: Iterable[Mapping[str, object]],
        *,
        item_character_limit: int = DEFAULT_SUMMARY_ITEM_CHARACTER_LIMIT,
    ) -> ConversationSummary:
        """模型摘要不可用时，安全地保留带来源的原文摘录。"""
        hard_constraints: list[SummaryItem] = []
        goals: list[SummaryItem] = []
        facts: list[SummaryItem] = []
        for message in messages:
            message_id = int(message.get("id") or 0)
            content = " ".join(str(message.get("content") or "").split())
            if message_id <= 0 or not content:
                continue
            excerpt = _bounded_source_excerpt(content, item_character_limit)
            if not excerpt:
                continue
            item = SummaryItem(excerpt, (message_id,))
            lowered = excerpt.lower()
            if any(marker in lowered for marker in (
                "不超过", "不得", "必须", "只能", "禁止", "预算", "限制",
            )):
                hard_constraints.append(item)
            elif str(message.get("role") or "") == "user" and any(
                marker in excerpt for marker in ("请", "需要", "想", "希望")
            ):
                goals.append(item)
            else:
                facts.append(item)
        return cls(
            goals=tuple(goals),
            hard_constraints=tuple(hard_constraints),
            facts=tuple(facts),
        )

    def merge(
        self,
        newer: ConversationSummary,
        *,
        item_limit: int = 24,
        superseded_source_message_ids: Iterable[int] = (),
    ) -> ConversationSummary:
        """合并新旧摘要，并且仅按完整来源条目移除被替代的旧状态。"""
        if item_limit < 1:
            raise ConversationSummaryError("摘要条目数量上限必须为正数")
        try:
            superseded_values = tuple(superseded_source_message_ids)
        except TypeError as exc:
            raise ConversationSummaryError(
                "supersededSourceMessageIds 必须包含正整数"
            ) from exc
        if any(
            isinstance(value, bool) or not isinstance(value, int)
            for value in superseded_values
        ):
            raise ConversationSummaryError("supersededSourceMessageIds 必须包含正整数")
        superseded_ids = set(superseded_values)
        if any(value <= 0 for value in superseded_ids):
            raise ConversationSummaryError("supersededSourceMessageIds 必须包含正整数")

        def retained(items: tuple[SummaryItem, ...]) -> tuple[SummaryItem, ...]:
            return tuple(
                item for item in items
                if not set(item.source_message_ids).issubset(superseded_ids)
            )

        combined = {
            "goals": self._unique_items((*newer.goals, *retained(self.goals))),
            "hardConstraints": self._unique_items(
                (*newer.hard_constraints, *retained(self.hard_constraints))
            ),
            "decisions": self._unique_items(
                (*newer.decisions, *retained(self.decisions))
            ),
            "openQuestions": self._unique_items(
                (*newer.open_questions, *retained(self.open_questions))
            ),
            "facts": self._unique_items((*newer.facts, *retained(self.facts))),
        }
        remaining = item_limit
        selected: dict[str, tuple[SummaryItem, ...]] = {}
        for category in (
            "hardConstraints", "goals", "decisions", "openQuestions", "facts",
        ):
            selected[category] = combined[category][:remaining]
            remaining -= len(selected[category])
        legacy_notes = tuple(dict.fromkeys((*newer.legacy_notes, *self.legacy_notes)))
        return ConversationSummary(
            goals=selected["goals"],
            hard_constraints=selected["hardConstraints"],
            decisions=selected["decisions"],
            open_questions=selected["openQuestions"],
            facts=selected["facts"],
            legacy_notes=legacy_notes,
        )

    def render(self, character_limit: int) -> str:
        """按保留优先级渲染完整条目，绝不截断任何已选条目。"""
        if character_limit < 1:
            return ""
        sections: list[str] = []
        used = 0
        for category in SUMMARY_RENDER_ORDER:
            rendered_items = self._rendered_items(category)
            if not rendered_items:
                continue
            heading = f"## {SUMMARY_LABELS[category]}"
            section_parts: list[str] = []
            for item in rendered_items:
                candidate = "\n".join((heading, *section_parts, item))
                separator = 2 if sections else 0
                if used + separator + len(candidate) <= character_limit:
                    section_parts.append(item)
                    continue
                # 单个高优先级条目比上限还长时宁可保留完整条目，不能将其截断。
                if not sections and not section_parts and category == "hardConstraints":
                    section_parts.append(item)
                # 剩余条目优先级不会高于当前条目，不再尝试塞入同一节。
                break
            if section_parts:
                section = "\n".join((heading, *section_parts))
                sections.append(section)
                used += (2 if len(sections) > 1 else 0) + len(section)
        return "\n\n".join(sections)

    def to_dict(self) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "goals": [item.to_dict() for item in self.goals],
            "hardConstraints": [item.to_dict() for item in self.hard_constraints],
            "decisions": [item.to_dict() for item in self.decisions],
            "openQuestions": [item.to_dict() for item in self.open_questions],
            "facts": [item.to_dict() for item in self.facts],
            "legacyNotes": list(self.legacy_notes),
        }

    def _category_values(self) -> dict[str, tuple[SummaryItem, ...]]:
        return {
            "goals": self.goals,
            "hardConstraints": self.hard_constraints,
            "decisions": self.decisions,
            "openQuestions": self.open_questions,
            "facts": self.facts,
        }

    def _rendered_items(self, category: str) -> tuple[str, ...]:
        if category == "legacyNotes":
            return tuple(f"- {note}" for note in self.legacy_notes)
        return tuple(
            f"- {item.text} [sourceMessageIds: {', '.join(map(str, item.source_message_ids))}]"
            for item in self._category_values()[category]
        )

    @staticmethod
    def _ensure_unique_items(items: tuple[SummaryItem, ...]) -> None:
        if len(ConversationSummary._unique_items(items)) != len(items):
            raise ConversationSummaryError("摘要条目不能重复")

    @staticmethod
    def _unique_items(items: Iterable[SummaryItem]) -> tuple[SummaryItem, ...]:
        result: list[SummaryItem] = []
        seen: set[tuple[str, tuple[int, ...]]] = set()
        for item in items:
            key = (item.text, item.source_message_ids)
            if key not in seen:
                seen.add(key)
                result.append(item)
        return tuple(result)


class TokenCounter(Protocol):
    def count(self, text: str) -> int:
        """返回应用级保守 Token 估算。"""


@dataclass(frozen=True, slots=True)
class ConservativeTokenCounter:
    """中英文混合内容的提供方无关保守估算器。"""

    minimum_tokens: int = 1

    def count(self, text: str) -> int:
        return max(self.minimum_tokens, (len(text) + 1) // 2)


@dataclass(frozen=True, slots=True)
class ContextComponent:
    name: str
    priority: int
    messages: tuple[object, ...]
    mandatory: bool = False

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("上下文组件名称不能为空")
        if any(not _message_content(message).strip() for message in self.messages):
            raise ValueError("上下文消息不能为空")
        object.__setattr__(self, "messages", tuple(self.messages))


@dataclass(frozen=True, slots=True)
class BudgetedContext:
    messages: tuple[object, ...]
    estimated_tokens: int
    component_tokens: tuple[tuple[str, int], ...]
    accepted_components: tuple[str, ...]
    evicted_components: tuple[str, ...]


@dataclass(slots=True)
class ContextBudgeter:
    token_counter: TokenCounter = field(default_factory=ConservativeTokenCounter)
    per_message_framing_tokens: int = 4
    multimodal_image_token_allowance: int = MULTIMODAL_IMAGE_TOKEN_ALLOWANCE

    def fit(
        self,
        components: Iterable[ContextComponent],
        budget: int,
    ) -> BudgetedContext:
        if budget < 1:
            raise ContextBudgetError("上下文预算必须为正数")
        if self.per_message_framing_tokens < 0:
            raise ValueError("消息框架 Token 不能为负数")
        if self.multimodal_image_token_allowance < 1:
            raise ValueError("图片 Token 预留必须为正数")
        values = tuple(component for component in components if component.messages)
        mandatory_names = {"current_user", "trusted_scope", "system_safety"}
        costs = [
            sum(
                _message_token_cost(
                    message,
                    self.token_counter,
                    self.per_message_framing_tokens,
                    self.multimodal_image_token_allowance,
                )
                for message in component.messages
            )
            for component in values
        ]
        selected: set[int] = set()
        used = 0
        for component_index, component in enumerate(values):
            if not (component.mandatory or component.name in mandatory_names):
                continue
            cost = costs[component_index]
            if used + cost > budget:
                raise ContextBudgetError("强制上下文超过应用预算")
            selected.add(component_index)
            used += cost

        optional = [
            (component_index, component, costs[component_index])
            for component_index, component in enumerate(values)
            if component_index not in selected
        ]
        optional.sort(key=lambda unit: (-unit[1].priority, unit[0]))
        for component_index, _component, cost in optional:
            if used + cost <= budget:
                selected.add(component_index)
                used += cost

        messages = tuple(
            message
            for component_index, component in enumerate(values)
            if component_index in selected
            for message in component.messages
        )
        accepted = tuple(
            component.name
            for component_index, component in enumerate(values)
            if component_index in selected
        )
        evicted = tuple(
            component.name
            for component_index, component in enumerate(values)
            if component_index not in selected
        )
        component_tokens = tuple(
            (component.name, costs[component_index])
            for component_index, component in enumerate(values)
        )
        return BudgetedContext(
            messages,
            used,
            component_tokens,
            accepted,
            evicted,
        )


def _message_content(message: object) -> str:
    """返回预算校验所需的文本占位，不把多模态 URL 转成普通文本。"""
    text, image_count = _message_parts(message)
    if text.strip():
        return text
    return "[image]" if image_count else ""


def _message_token_cost(
    message: object,
    token_counter: TokenCounter,
    framing_tokens: int,
    image_token_allowance: int,
) -> int:
    text, image_count = _message_parts(message)
    text_cost = token_counter.count(text) if text.strip() else 0
    return text_cost + image_count * image_token_allowance + framing_tokens


def _message_parts(message: object) -> tuple[str, int]:
    if isinstance(message, str):
        return message, 0
    if isinstance(message, Mapping):
        content = message.get("content")
    else:
        content = getattr(message, "content", None)
    return _multimodal_content_parts(content)


def _multimodal_content_parts(content: object) -> tuple[str, int]:
    if isinstance(content, str):
        return content, 0
    if isinstance(content, (list, tuple)):
        text_parts: list[str] = []
        image_count = 0
        for block in content:
            text, images = _multimodal_content_parts(block)
            if text.strip():
                text_parts.append(text)
            image_count += images
        return "\n".join(text_parts), image_count
    if isinstance(content, Mapping):
        block_type = str(content.get("type") or "")
        if block_type in _MULTIMODAL_TEXT_BLOCK_TYPES:
            return str(content.get("text") or ""), 0
        if block_type in _MULTIMODAL_IMAGE_BLOCK_TYPES:
            return "", 1
        nested = content.get("content")
        if nested is not None:
            return _multimodal_content_parts(nested)
        if isinstance(content.get("text"), str):
            return str(content["text"]), 0
        return "", 0
    if content is None:
        return "", 0
    return str(content), 0
