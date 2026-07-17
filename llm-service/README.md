# LLM Service

本目录是独立 LLM 服务入口，负责地图讲解、学校周边资源问答、教学方案生成等大模型能力。当前实现仍是本地演示骨架，后续应在这里接入 Neo4j 检索、向量库和真实 LLM API。

## 启动

```bash
pip install -r requirements.txt
python app.py
```

默认监听：

- `http://127.0.0.1:5050`

已提供接口：

- `POST /llm/town/explain`
- `POST /llm/town/ask`
- `POST /llm/school/explain`
- `POST /llm/school/ask`
- `POST /llm/teaching-plan/generate`

结构化教学方案生成接口支持本地兜底。如果配置了 OpenAI-compatible 模型服务，会优先调用真实模型：

- `LLM_API_URL`：模型接口地址
- `LLM_API_KEY`：模型密钥
- `LLM_MODEL`：模型名称，默认 `qwen-plus`
- `LLM_TIMEOUT_SECONDS`：调用超时秒数，默认 `20`

后续建议替换为：

1. Neo4j 检索上下文
2. MySQL/内容分块补充
3. 向量库相似度检索
4. 外部 LLM 生成最终回答
