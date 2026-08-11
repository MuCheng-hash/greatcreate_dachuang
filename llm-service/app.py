from __future__ import annotations

import sys

import uvicorn

from llm_service.bootstrap import create_application
from llm_service.database import selector_event_loop_factory
from llm_service.settings import get_settings


service_settings = get_settings()
app = create_application(settings=service_settings)


if __name__ == "__main__":
    uvicorn.run(
        app,
        host=service_settings.host,
        port=service_settings.port,
        workers=1,
        loop=selector_event_loop_factory if sys.platform == "win32" else "auto",
    )
