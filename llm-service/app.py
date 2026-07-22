from __future__ import annotations

import uvicorn

from llm_service.api import create_app


app = create_app()


if __name__ == "__main__":
    uvicorn.run("app:app", host="127.0.0.1", port=5050, reload=False)
