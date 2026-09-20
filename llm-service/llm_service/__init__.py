"""平台的有状态 FastAPI 与 LangChain 运行时。"""


def create_app(*args, **kwargs):
    """延迟导入可避免 Agent 包在启动时产生循环导入。"""
    from .api import create_app as _create_app

    return _create_app(*args, **kwargs)


__all__ = ["create_app"]


def __getattr__(name: str):
    """按需导出应用工厂，避免 agent 与 FastAPI 容器互相导入。"""
    if name == "create_app":
        from .api import create_app

        return create_app
    raise AttributeError(name)
