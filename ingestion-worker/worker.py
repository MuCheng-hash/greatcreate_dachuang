"""基于 RocketMQ 的文档处理流水线；重试会重新执行完整流程。"""
import hashlib
import io
import json
import os
import re
import uuid
import zipfile
import base64
from typing import TypedDict

import pymysql
import ingest_runtime
import requests
from docx import Document as DocxDocument
from langgraph.graph import END, START, StateGraph
from minio import Minio
from pypdf import PdfReader

MYSQL = dict(host=os.getenv("MYSQL_HOST", "host.docker.internal"), port=int(os.getenv("MYSQL_PORT", "3306")),
             user=os.getenv("MYSQL_USER", "root"), password=os.getenv("MYSQL_PASSWORD", "root"),
             database=os.getenv("MYSQL_DATABASE", "red_culture_platform"), charset="utf8mb4", autocommit=True,
             cursorclass=pymysql.cursors.DictCursor)
MINIO = Minio(os.getenv("MINIO_ENDPOINT", "minio:9000"), access_key=os.getenv("MINIO_ACCESS_KEY", "minioadmin"),
              secret_key=os.getenv("MINIO_SECRET_KEY", "minioadmin"), secure=os.getenv("MINIO_SECURE", "false").lower() == "true")
BUCKET = os.getenv("MINIO_KNOWLEDGE_BUCKET", "knowledge")
QDRANT = os.getenv("QDRANT_URL", "http://qdrant:6333").rstrip("/")
COLLECTION = os.getenv("QDRANT_COLLECTION", "red_culture_content_chunks")
INDEX_VERSION = os.getenv("RAG_INDEX_VERSION", "v2")
MINERU_URL = os.getenv("MINERU_URL", "").rstrip("/")
MAX_CHUNK_CHARS = int(os.getenv("INGEST_CHUNK_CHARS", "1800"))
EMBEDDING_URL = os.getenv("EMBEDDING_URL", "").rstrip("/")
EMBEDDING_KEY = os.getenv("EMBEDDING_API_KEY", "")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-v3")
DIMENSIONS = int(os.getenv("EMBEDDING_DIMENSIONS", "1024"))
MODEL_URL = os.getenv("METADATA_MODEL_URL", "").rstrip("/")
MODEL_KEY = os.getenv("METADATA_MODEL_API_KEY", "")
MODEL_NAME = os.getenv("METADATA_MODEL", "")
VISION_URL = os.getenv("VISION_URL", "").rstrip("/")
VISION_KEY = os.getenv("VISION_API_KEY", "")
VISION_MODEL = os.getenv("VISION_MODEL", "")

class State(TypedDict, total=False):
    """图节点间传递的临时处理状态；最终业务状态只由数据库事务提交。"""
    job: dict; document: dict; data: bytes; markdown: str; chunks: list; metadata: dict; images: list
    degradations: list; image_refs: list

def db():
    """建立仅供入库运行时使用的 MySQL 连接；连接生命周期由消息处理器管理。"""
    return pymysql.connect(connect_timeout=10, read_timeout=20, write_timeout=20, **MYSQL)
def fetch(sql, args=()):
    """在持有文档锁的连接上执行单行查询，拒绝脱离运行时上下文的数据库访问。"""
    # 查询前也检查租约，避免失权后依据过期状态决定是否写入。
    ingest_runtime.guard()
    connection = ingest_runtime.CONNECTION.get()
    if connection is None: raise RuntimeError('database operation outside document lock')
    with connection.cursor() as c: c.execute(sql, args); return c.fetchone()
def execute(sql, args=()):
    """在当前文档锁持有的连接中执行写操作，并在执行前检查消息租约。"""
    # 所有写入统一从这里经过，保证每次写前均验证消息和连接所有权。
    ingest_runtime.guard()
    connection = ingest_runtime.CONNECTION.get()
    if connection is None: raise RuntimeError('database operation outside document lock')
    with connection.cursor() as c: c.execute(sql, args)
def checkpoint(state, node):
    """持久化当前图节点，便于管理端展示任务进度并在失败时定位处理阶段。"""
    # 不更新 finished_at；只有 complete 或 fail 才能把任务收敛为终态。
    execute("UPDATE knowledge_ingest_job SET status='RUNNING', current_node=%s, started_at=COALESCE(started_at,NOW()) WHERE id=%s", (node, state['job']['id']))

def degrade(state, node, reason):
    """记录非致命能力缺失，允许任务以 DEGRADED 状态继续完成索引。"""
    # 降级只保存在图状态，最终在 complete 中与成功状态一起原子落库。
    values = state.setdefault('degradations', [])
    values.append({'node': node, 'reason': str(reason)[:300]})
    return state

def validate(state):
    """从对象存储读取源文件、计算内容哈希并写入文档记录。

    哈希是后续图片去重和文档变更判断的稳定依据，文件读取失败会中断整个任务。
    """
    checkpoint(state, "VALIDATE")
    document = state['document']
    # 对象存储响应必须显式关闭，否则长批量任务会耗尽 HTTP 连接池。
    response = MINIO.get_object(BUCKET, document['object_key'])
    try: data = response.read()
    finally:
        response.close()
        response.release_conn()
    # SHA-256 以原始字节计算，不受后续转换器或编码容错处理影响。
    digest = hashlib.sha256(data).hexdigest()
    execute("UPDATE knowledge_document SET sha256=%s WHERE id=%s", (digest, document['id']))
    return {**state, 'data': data}

def convert(state):
    """将支持的源文件统一转换为 Markdown，并保存规范化文本副本。

    PDF 优先调用 MinerU；外部解析不可用时回退至本地文本提取，并把降级原因
    写入图状态，使最终任务可标记为 DEGRADED 而不是静默降低质量。
    """
    checkpoint(state, "CONVERT")
    name = state['document']['original_filename'].lower()
    # 依据文件扩展名选择确定的转换器；不支持的格式视为不可重试的业务输入错误。
    if name.endswith(('.md', '.markdown')): markdown = state['data'].decode('utf-8-sig', errors='replace')
    elif name.endswith('.docx'):
        # DOCX 只抽取非空段落，避免把布局空白写入向量索引。
        doc = DocxDocument(io.BytesIO(state['data'])); markdown = '\n\n'.join(p.text for p in doc.paragraphs if p.text.strip())
    elif name.endswith('.pdf'):
        try:
            # MinerU 输出保留版面语义，适合作为 PDF 的优先转换结果。
            if not MINERU_URL: raise RuntimeError('MinerU is not configured')
            response = requests.post(MINERU_URL + '/parse', files={'file': ('document.pdf', state['data'], 'application/pdf')}, timeout=120)
            response.raise_for_status(); markdown = response.json().get('markdown', '')
            if not markdown.strip(): raise ValueError('MinerU returned no markdown')
        except Exception as error:
            # 本地提取牺牲版面质量但仍能产出可搜索正文，因此降级后继续处理。
            degrade(state, 'CONVERT', 'MinerU fallback: ' + type(error).__name__)
            markdown = '\n\n'.join(page.extract_text() or '' for page in PdfReader(io.BytesIO(state['data'])).pages)
    else: raise ValueError('unsupported document type')
    # 空正文无法产生有效分块，直接终止而不是创建无内容的索引。
    if not markdown.strip(): raise ValueError('document contains no extractable text')
    key = 'markdown/%s/%s.md' % (state['document']['school_id'] or 'public', state['document']['id'])
    # 写入规范化副本前检查租约，避免旧消费者覆盖新一代任务的 Markdown。
    ingest_runtime.guard()
    MINIO.put_object(BUCKET, key, io.BytesIO(markdown.encode()), len(markdown.encode()), content_type='text/markdown')
    execute("UPDATE knowledge_document SET markdown_object_key=%s WHERE id=%s", (key, state['document']['id']))
    return {**state, 'markdown': markdown, 'images': extract_images(state['data'], name)}

def extract_images(data, filename):
    """从 DOCX 压缩包提取嵌入媒体，供视觉节点生成可检索的图片描述。"""
    # 目前只有 DOCX 的嵌入媒体可在不依赖外部解析器的情况下稳定提取。
    if not filename.endswith('.docx'): return []
    values = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for member in archive.namelist():
            if member.startswith('word/media/'):
                # 保留压缩包内文件名，用于后续图片引用的可读展示名称。
                values.append((member.rsplit('/', 1)[-1], archive.read(member)))
    return values

def understand_images(state):
    """复用图片哈希缓存或调用视觉服务，为文档图片补充描述和对象存储引用。

    图片描述失败不阻断正文索引，只记录降级；相同哈希的图片会复用已有描述，
    防止重放任务重复调用视觉模型。
    """
    checkpoint(state, "IMAGE_VISION")
    images = []
    for name, data in state.get('images', []):
        # 按内容哈希查缓存，避免同一图片在不同文档或重试中重复分析。
        digest = hashlib.sha256(data).hexdigest()
        cached = fetch('SELECT description, object_key, model FROM knowledge_document_image WHERE sha256=%s AND status=\'SUCCESS\' LIMIT 1', (digest,))
        key = 'images/%s/%s-%s' % (state['document']['id'], digest, name)
        if not cached:
            # 缓存未命中时先持久化原图，再调用视觉模型，保证描述失败仍保留可追溯素材。
            ingest_runtime.guard()
            MINIO.put_object(BUCKET, key, io.BytesIO(data), len(data), content_type='application/octet-stream')
            description = ''
            if VISION_URL and VISION_KEY and VISION_MODEL:
                # 视觉网关接受 Base64 数据；远程错误由外层运行时按重试策略处理。
                encoded = base64.b64encode(data).decode()
                response = requests.post(VISION_URL + '/internal/vision/analyze', headers={'X-Model-Gateway-Key': VISION_KEY}, json={'model':VISION_MODEL,'imageBase64':encoded}, timeout=90); response.raise_for_status(); description = response.json().get('description','')
            status = 'SUCCESS' if description else 'SKIPPED'
            # 没有可用描述时正文仍然可索引，记录降级而不丢弃整份文档。
            if not description: degrade(state, 'IMAGE_VISION', 'vision model is unavailable or returned no description')
            execute('INSERT INTO knowledge_document_image(document_id,sha256,object_key,description,status,model) VALUES(%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE object_key=VALUES(object_key),description=VALUES(description),status=VALUES(status),model=VALUES(model)', (state['document']['id'],digest,key,description,status,VISION_MODEL or None))
        else:
            # 命中缓存时复制引用关系到当前文档，不重新上传或调用模型。
            key, description = cached['object_key'], cached['description'] or ''
            execute('INSERT INTO knowledge_document_image(document_id,sha256,object_key,description,status,model) VALUES(%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE object_key=VALUES(object_key),description=VALUES(description),status=VALUES(status),model=VALUES(model)', (state['document']['id'],digest,key,description,'SUCCESS',cached['model']))
        images.append({'name': name, 'description': description, 'object_key': key})
    markdown = state['markdown']
    for image in images:
        # 追加 Markdown 图片引用，使分块与嵌入模型可感知同一文档的视觉补充信息。
        markdown += '\n\n![%s](%s)' % (image['description'] or image['name'], image['object_key'])
    return {**state, 'markdown': markdown, 'image_refs': images}

def split(state):
    """按 Markdown 标题路径切分正文，并在超长段落中优先按中文标点截断。

    每个分块携带标题层级，既控制向量检索粒度，也让召回结果保留原文结构语境。
    """
    checkpoint(state, "CHUNK")
    path, chunks, buffer = [], [], []
    def flush():
        """将累计段落写成受长度上限约束的一个或多个分块。"""
        nonlocal buffer
        text = '\n'.join(buffer).strip()
        while text:
            if len(text) > MAX_CHUNK_CHARS:
                # 优先在后半段后的中文断句点截断，避免过短分块和截断句意同时发生。
                boundary = max(text.rfind(mark, 0, MAX_CHUNK_CHARS) for mark in '。！？；\n')
                take = boundary + 1 if boundary >= MAX_CHUNK_CHARS // 2 else MAX_CHUNK_CHARS
                part, text = text[:take].strip(), text[take:].strip()
            else:
                part, text = text, ''
            prefix = (' > '.join(path) + '\n\n') if path else ''
            # 标题路径并入内容和独立字段，兼顾向量语义与展示筛选。
            content = prefix + part
            chunks.append({'title_path': ' > '.join(path), 'content': content, 'token_count': max(1, len(content) // 3)})
        buffer = []
    for line in state['markdown'].splitlines():
        match = re.match(r'^(#{1,6})\s+(.+)', line)
        if match:
            # 新标题到来时先结算上一段，并用层级切片丢弃同级及更深的旧路径。
            flush(); level, title = len(match.group(1)), match.group(2).strip(); path[level - 1:] = [title]
        else:
            buffer.append(line)
            # 无标题的长正文按词数提前刷出，控制内存和单块嵌入请求大小。
            if len(' '.join(buffer).split()) >= 700: flush()
    flush()
    return {**state, 'chunks': chunks}

def metadata(state):
    """使用可选模型提取主题和标签；模型不可用时回退到文档标题与文件名。"""
    checkpoint(state, "METADATA")
    value = {'subject': None, 'subjectType': None, 'tags': []}
    if MODEL_URL and MODEL_KEY and MODEL_NAME:
        try:
            # 限制输入长度以控制模型请求成本，并强制 JSON 响应供后续字段直接写入。
            prompt = 'Extract JSON only: {"subject":string|null,"subjectType":string|null,"tags":[string]}. Text:\n' + state['markdown'][:30000]
            response = requests.post(MODEL_URL + '/chat/completions', headers={'Authorization': 'Bearer ' + MODEL_KEY}, json={'model': MODEL_NAME, 'messages': [{'role':'user','content':prompt}], 'temperature':0, 'response_format': {'type':'json_object'}}, timeout=45)
            value = json.loads(response.json()['choices'][0]['message']['content'])
        except Exception as error:
            # 元数据失败不影响正文检索，使用本地文件信息保证索引仍具备基础筛选字段。
            degrade(state, 'METADATA', 'metadata fallback: ' + type(error).__name__)
            value['subject'] = state['document'].get('title') or state['document'].get('original_filename')
            value['tags'] = [state['document'].get('original_filename', '').rsplit('.', 1)[0]][:1]
    return {**state, 'metadata': value}

def embed(texts):
    """调用兼容 OpenAI 的嵌入接口生成稠密向量；未配置服务时任务不可继续。"""
    # 嵌入是索引正确性的前置条件；无服务配置不能产出部分可用的文档。
    if not EMBEDDING_URL or not EMBEDDING_KEY: raise RuntimeError('embedding service is not configured')
    r = requests.post(EMBEDDING_URL + '/embeddings', headers={'Authorization':'Bearer ' + EMBEDDING_KEY}, json={'model':EMBEDDING_MODEL, 'input':texts, 'dimensions':DIMENSIONS}, timeout=60); r.raise_for_status()
    return [item['embedding'] for item in r.json()['data']]

def hybrid_embed(texts):
    """优先获取稠密与稀疏混合向量，旧接口不可用时仅生成稠密向量。"""
    if EMBEDDING_URL:
        # 新网关提供稠密和稀疏向量；仅在响应成功时采用，避免兼容性问题阻断入库。
        response = requests.post(EMBEDDING_URL + '/internal/embeddings/hybrid', headers={'X-Model-Gateway-Key': EMBEDDING_KEY}, json={'model': EMBEDDING_MODEL, 'texts': texts}, timeout=90)
        if response.ok: return response.json()['items']
    # 旧嵌入端点没有稀疏向量时以空稀疏向量保持 Qdrant payload 结构兼容。
    return [{'dense': vector, 'sparse': {'indices': [], 'values': []}} for vector in embed(texts)]

def index(state):
    """以确定性点位标识重建文档的 Qdrant 向量和 MySQL 分块记录。

    先删除该文档的旧向量再写入新批次，随后在一个 MySQL 事务中替换分块行；
    每次远程写入前检查消息租约，避免失去消费权后继续覆盖索引。
    """
    checkpoint(state, "INDEX")
    doc, meta = state['document'], state['metadata']
    if requests.get(QDRANT + '/collections/' + COLLECTION, timeout=10).status_code == 404:
        # 首次启动才创建 collection；存在时沿用既有 schema，避免运行时擅自迁移。
        ingest_runtime.guard()
        requests.put(QDRANT + '/collections/' + COLLECTION, json={'vectors': {'dense': {'size': DIMENSIONS, 'distance':'Cosine'}}, 'sparse_vectors': {'sparse': {}}}, timeout=20).raise_for_status()
    rows = []
    points = []
    for i in range(0, len(state['chunks']), 16):
        # 小批量限制单次模型请求和 Qdrant payload，索引顺序仍由全局 offset 保证。
        batch = state['chunks'][i:i+16]; vectors = hybrid_embed([x['content'] for x in batch])
        # 数量不匹配会导致内容与向量错位，必须失败重试而不能截断写入。
        if len(vectors) != len(batch): raise RuntimeError('embedding count does not match chunks')
        for offset, (chunk, vector) in enumerate(zip(batch, vectors)):
            # UUID5 由文档和全局分块序号推导，重试不会产生重复向量点。
            point_id = str(uuid.uuid5(uuid.NAMESPACE_URL, 'knowledge:' + str(doc['id']) + ':' + str(i + offset)))
            rows.append((doc['id'],i+offset,chunk['title_path'],chunk['content'],chunk['token_count'],meta.get('subject'),meta.get('subjectType'),json.dumps(meta.get('tags', []), ensure_ascii=False),point_id))
            points.append({'id':point_id,'vector':{'dense':vector['dense'], 'sparse': vector['sparse']},'payload':{'documentId':doc['id'],'chunk_id':point_id,'entity_key':'knowledge-document:'+str(doc['id']),'schoolId':doc['school_id'],'titlePath':chunk['title_path'],'subject':meta.get('subject'),'subjectType':meta.get('subjectType'),'tags':meta.get('tags', []),'index_version':INDEX_VERSION,'documentTitle':doc['title'],'imageRefs':state.get('image_refs',[])}})
    ingest_runtime.guard()
    # 点位 ID 基于文档和分块序号，可使相同版本的重放保持幂等。
    delete_vectors(doc['id'])
    ingest_runtime.guard()
    # wait=true 要求服务端落盘后才返回，后续 MySQL 元数据不会指向尚未可读的向量。
    r = requests.put(QDRANT + '/collections/' + COLLECTION + '/points?wait=true', json={'points':points}, timeout=90); r.raise_for_status()
    ingest_runtime.guard()
    connection = ingest_runtime.CONNECTION.get()
    # 分块表以整份文档为单位替换，事务避免查询端读取到新旧分块混合结果。
    connection.begin()
    try:
        # 删除后批量写入，确保缩短或重排文档时旧分块不会残留。
        execute('DELETE FROM knowledge_chunk WHERE document_id=%s', (doc['id'],))
        with connection.cursor() as cursor:
            cursor.executemany('INSERT INTO knowledge_chunk(document_id,chunk_order,title_path,content,token_count,subject,subject_type,tags,qdrant_point_id) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)', rows)
        connection.commit()
    except BaseException:
        # Qdrant 已写入而 MySQL 失败时保留异常让整个任务重试，确定性点位会在重试中覆盖。
        connection.rollback()
        raise
    return state

def complete(state):
    """根据是否存在非致命降级记录，原子提交文档和任务的最终状态。"""
    ingest_runtime.guard()
    status = 'DEGRADED' if state.get('degradations') else 'SUCCESS'
    # 把降级原因和分块数量作为终态元数据保存，调用方可区分可用结果与完全成功。
    metadata_json = json.dumps({'degradations': state.get('degradations', []), 'chunkCount': len(state.get('chunks', []))}, ensure_ascii=False)
    connection = ingest_runtime.CONNECTION.get()
    # 文档可发布状态与任务完成状态必须原子更新，避免前台看到半完成文档。
    connection.begin()
    try:
        execute("UPDATE knowledge_document SET status=%s, indexed_at=NOW(), published_at=COALESCE(published_at,NOW()) WHERE id=%s", (status,state['document']['id']))
        execute("UPDATE knowledge_ingest_job SET status=%s, current_node='DONE', metadata_json=%s, error_summary=NULL, finished_at=NOW() WHERE id=%s", (status,metadata_json,state['job']['id']))
        connection.commit()
    except BaseException:
        # 终态提交失败必须保留 RUNNING/PENDING 语义，让投递运行时进行恢复。
        connection.rollback()
        raise
    return state


# 图节点按“读取、转换、图片、切分、元数据、索引、提交”顺序执行；任一步抛错
# 会由投递运行时决定重试或失败，不在图内部吞掉致命错误。
graph = StateGraph(State)
for name, fn in [('validate',validate),('convert',convert),('image_vision',understand_images),('split',split),('metadata',metadata),('index',index),('complete',complete)]: graph.add_node(name, fn)
graph.add_edge(START,'validate'); graph.add_edge('validate','convert')
for left, right in [('convert','image_vision'),('image_vision','split'),('split','metadata'),('metadata','index'),('index','complete'),('complete',END)]: graph.add_edge(left,right)
pipeline = graph.compile()

def delete_vectors(document_id):
    """删除指定文档的既有向量点，防止缩短文档后遗留过期召回内容。"""
    # 远程删除同样需要有效租约；否则旧消费者可能删掉新任务刚写入的向量。
    ingest_runtime.guard()
    requests.post(QDRANT + '/collections/' + COLLECTION + '/points/delete?wait=true', json={'filter': {'must': [{'key': 'documentId', 'match': {'value': document_id}}]}}, timeout=30).raise_for_status()

if __name__ == '__main__':
    import sys
    ingest_runtime.main(sys.modules[__name__])
