# QA Service

本目录提供地图问答门户的独立问答服务脚手架。

## 启动

```bash
pip install -r requirements.txt
python app.py
```

默认监听：

- `http://127.0.0.1:5050`

已提供接口：

- `POST /qa/town/explain`
- `POST /qa/town/ask`

当前版本为本地演示骨架，后续可替换为：

1. Neo4j 检索上下文
2. MySQL/内容分块补充
3. 外部 LLM 生成最终回答
