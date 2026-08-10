from __future__ import annotations

from postgres_test_support import cleanup_test_resources


def pytest_sessionfinish(session, exitstatus) -> None:
    del session, exitstatus
    cleanup_test_resources()
