from pathlib import Path

from fastapi.testclient import TestClient

from llm_service.api import create_app
from llm_service.prompt_manager import PromptManager
from llm_service.settings import Settings


PROMPT_ROOT = Path(__file__).resolve().parent / "prompts"


def test_versions_are_persistent_and_active_version_is_rendered(tmp_path: Path):
    database = tmp_path / "prompts.sqlite3"
    manager = PromptManager(database, PROMPT_ROOT)

    assert manager.list_versions("teaching-plan")[0]["version"] == "v1"
    manager.create_version("teaching-plan", "v2", "新版模板\n{{context_json}}", notes="test")
    manager.activate_version("teaching-plan", "v2")

    restarted = PromptManager(database, PROMPT_ROOT)
    selected = restarted.resolve("teaching-plan", "school:1", {"theme": "家乡文化"})

    assert selected.version == "v2"
    assert '"theme":"家乡文化"' in selected.content


def test_experiment_assignment_is_stable_and_metrics_accept_feedback(tmp_path: Path):
    manager = PromptManager(tmp_path / "prompts.sqlite3", PROMPT_ROOT)
    manager.create_version("teaching-plan", "v2", "实验模板\n{{context_json}}")
    manager.configure_experiment(
        "teaching-plan",
        "teaching-plan-copy-2026-07",
        [{"version": "v1", "weight": 50}, {"version": "v2", "weight": 50}],
        True,
    )

    first = manager.resolve("teaching-plan", "school:7", {"theme": "志愿服务"})
    second = manager.resolve("teaching-plan", "school:7", {"theme": "志愿服务"})
    assert first.version == second.version
    assert first.experiment_key == "teaching-plan-copy-2026-07"

    run_id = manager.start_run(first, "school:7", "test-model", len(first.content))
    manager.finish_run(run_id, "completed", 320, 800)
    run = manager.add_feedback(run_id, 4.5, "引用准确")
    metrics = manager.metrics("teaching-plan")

    assert run["quality_score"] == 4.5
    assert any(item["version"] == first.version and item["success_rate"] == 1.0 for item in metrics)


def test_teaching_plan_stream_and_prompt_admin_api(tmp_path: Path):
    settings = Settings(
        database_path=tmp_path / "service.sqlite3",
        prompt_root=PROMPT_ROOT,
        prompt_admin_token="admin-secret",
        llm_api_url="",
        llm_api_key="",
    )
    headers = {"X-Prompt-Admin-Token": "admin-secret"}
    with TestClient(create_app(settings)) as client:
        unauthorized = client.get("/admin/prompts/teaching-plan/versions")
        assert unauthorized.status_code == 401

        created = client.post(
            "/admin/prompts/teaching-plan/versions",
            headers=headers,
            json={"version": "v2", "content": "受管模板\n{{context_json}}", "notes": "candidate"},
        )
        assert created.status_code == 201
        assert created.json()["version"] == "v2"

        with client.stream(
            "POST",
            "/llm/teaching-plan/generate/stream",
            json={"request": {"schoolId": 1, "theme": "家乡文化", "grade": "四年级"}},
        ) as response:
            body = "".join(response.iter_text())

        assert response.status_code == 200
        assert "event: meta" in body
        assert "event: token" in body
        assert "event: result" in body
        assert "event: done" in body
        assert '"promptVersion": "v1"' in body

        metrics = client.get("/admin/prompts/teaching-plan/metrics", headers=headers)
        assert metrics.status_code == 200
        assert metrics.json()[0]["runs"] == 1
