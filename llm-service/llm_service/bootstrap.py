from __future__ import annotations

from .api import create_app
from .container import AppContainer, build_container
from .observability import configure_json_logging
from .settings import Settings, load_settings


def create_application(
    settings: Settings | None = None,
    container: AppContainer | None = None,
):
    configure_json_logging()
    if container is None:
        container = build_container(settings or load_settings())
    return create_app(container=container)
