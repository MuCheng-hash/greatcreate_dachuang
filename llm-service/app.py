from __future__ import annotations

import uvicorn

from llm_service.api import create_app
from llm_service.settings import get_settings


settings = get_settings()
app = create_app(settings)


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=5050, workers=1)
