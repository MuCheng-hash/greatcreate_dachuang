"""Stateful FastAPI and LangChain runtime for the platform."""


def create_app(*args, **kwargs):
    """Lazy import keeps the Agent package free of startup import cycles."""
    from .api import create_app as _create_app

    return _create_app(*args, **kwargs)


__all__ = ["create_app"]


def __getattr__(name: str):
    """按需导出应用工厂，避免 agent 与 FastAPI 容器互相导入。"""
    if name == "create_app":
        from .api import create_app

        return create_app
    raise AttributeError(name)
