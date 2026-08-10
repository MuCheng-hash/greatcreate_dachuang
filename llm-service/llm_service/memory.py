from __future__ import annotations

from dataclasses import dataclass


def estimate_tokens(text: str) -> int:
    # Conservative for mixed Chinese/English without coupling to a provider tokenizer.
    return max(1, (len(text) + 1) // 2)


@dataclass(slots=True)
class ContextWindow:
    messages: list[dict[str, str]]
    summary: str
    compacted: bool
    summary_through_message_id: int


class ContextWindowManager:
    def __init__(self, token_budget: int, recent_message_count: int, summary_character_limit: int):
        self.token_budget = token_budget
        self.recent_message_count = recent_message_count
        self.summary_character_limit = summary_character_limit

    def build(
        self,
        stored_messages: list[dict],
        existing_summary: str = "",
        summary_through_message_id: int = 0,
    ) -> ContextWindow:
        normalized = [
            {
                "id": int(item.get("id") or 0),
                "role": str(item.get("role", "user")),
                "content": str(item.get("content", "")),
            }
            for item in stored_messages
            if item.get("content")
            and int(item.get("id") or 0) > summary_through_message_id
        ]
        total = estimate_tokens(existing_summary) if existing_summary else 0
        total += sum(estimate_tokens(item["content"]) for item in normalized)
        if total <= self.token_budget and len(normalized) <= self.recent_message_count:
            return ContextWindow(
                self._model_messages(normalized),
                existing_summary,
                False,
                summary_through_message_id,
            )

        retained: list[dict[str, str]] = []
        used = estimate_tokens(existing_summary) if existing_summary else 0
        for message in reversed(normalized):
            cost = estimate_tokens(message["content"])
            if retained and (used + cost > self.token_budget or len(retained) >= self.recent_message_count):
                break
            retained.append(message)
            used += cost
        retained.reverse()
        omitted_count = len(normalized) - len(retained)
        omitted = normalized[:omitted_count]
        summary = self._merge_summary(existing_summary, omitted)
        new_cursor = (
            max(item["id"] for item in omitted)
            if omitted
            else summary_through_message_id
        )
        return ContextWindow(
            self._model_messages(retained),
            summary,
            omitted_count > 0,
            new_cursor,
        )

    def _merge_summary(self, existing: str, omitted: list[dict[str, str]]) -> str:
        lines = [existing.strip()] if existing.strip() else []
        for item in omitted:
            role = "用户" if item["role"] == "user" else "助手"
            content = " ".join(item["content"].split())[:500]
            lines.append(f"{role}: {content}")
        value = "\n".join(lines)
        return value[-self.summary_character_limit :]

    @staticmethod
    def _model_messages(messages: list[dict]) -> list[dict[str, str]]:
        return [
            {"role": str(item["role"]), "content": str(item["content"])}
            for item in messages
        ]
