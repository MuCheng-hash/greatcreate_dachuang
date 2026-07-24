"""Stateful FastAPI and LangChain runtime for the platform."""


def create_app(*args, **kwargs):
    """Lazy import keeps the Agent package free of startup import cycles."""
    from .api import create_app as _create_app

    return _create_app(*args, **kwargs)


__all__ = ["create_app"]
