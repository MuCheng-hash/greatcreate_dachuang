"""Redis-backed, resumable knowledge-document ingestion worker."""
import hashlib
import io
import json
import os
import re
import traceback
import uuid
import zipfile
import base64
from datetime import datetime
from typing import TypedDict

import pymysql
import redis
import requests
from docx import Document as DocxDocument
from langgraph.graph import END, START, StateGraph
from minio import Minio
from pypdf import PdfReader

MYSQL = dict(host=os.getenv("MYSQL_HOST", "host.docker.internal"), port=int(os.getenv("MYSQL_PORT", "3306")),
             user=os.getenv("MYSQL_USER", "root"), password=os.getenv("MYSQL_PASSWORD", "root"),
             database=os.getenv("MYSQL_DATABASE", "red_culture_platform"), charset="utf8mb4", autocommit=True,
             cursorclass=pymysql.cursors.DictCursor)
REDIS = redis.Redis.from_url(os.getenv("REDIS_URL", "redis://redis:6379/0"), decode_responses=True)
MINIO = Minio(os.getenv("MINIO_ENDPOINT", "minio:9000"), access_key=os.getenv("MINIO_ACCESS_KEY", "minioadmin"),
              secret_key=os.getenv("MINIO_SECRET_KEY", "minioadmin"), secure=os.getenv("MINIO_SECURE", "false").lower() == "true")
BUCKET, QUEUE = os.getenv("MINIO_KNOWLEDGE_BUCKET", "knowledge"), "knowledge:ingest"
QDRANT = os.getenv("QDRANT_URL", "http://qdrant:6333").rstrip("/")
COLLECTION = os.getenv("QDRANT_COLLECTION", "knowledge_documents")
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
    job: dict; document: dict; data: bytes; markdown: str; chunks: list; metadata: dict; images: list

def db(): return pymysql.connect(**MYSQL)
def fetch(sql, args=()):
    with db().cursor() as c: c.execute(sql, args); return c.fetchone()
def execute(sql, args=()):
    with db().cursor() as c: c.execute(sql, args)
def checkpoint(state, node):
    execute("UPDATE knowledge_ingest_job SET status='RUNNING', current_node=%s, started_at=COALESCE(started_at,NOW()) WHERE id=%s", (node, state['job']['id']))

def degrade(state, node, reason):
    values = state.setdefault('degradations', [])
    values.append({'node': node, 'reason': str(reason)[:300]})
    return state

def validate(state):
    checkpoint(state, "VALIDATE")
    document = state['document']
    data = MINIO.get_object(BUCKET, document['object_key']).read()
    digest = hashlib.sha256(data).hexdigest()
    existing = fetch("SELECT id FROM knowledge_document WHERE school_id <=> %s AND sha256=%s AND status='SUCCESS' AND id<>%s LIMIT 1", (document['school_id'], digest, document['id']))
    execute("UPDATE knowledge_document SET sha256=%s WHERE id=%s", (digest, document['id']))
    if existing:
        # Same scope and file is already indexed; mark this document ready without duplicate vectors.
        execute("UPDATE knowledge_document SET status='SUCCESS', indexed_at=NOW() WHERE id=%s", (document['id'],))
        execute("UPDATE knowledge_ingest_job SET status='SUCCESS', current_node='DONE', finished_at=NOW() WHERE id=%s", (state['job']['id'],))
        return {**state, 'deduplicated': True}
    return {**state, 'data': data}

def convert(state):
    checkpoint(state, "CONVERT")
    name = state['document']['original_filename'].lower()
    if name.endswith(('.md', '.markdown')): markdown = state['data'].decode('utf-8-sig', errors='replace')
    elif name.endswith('.docx'):
        doc = DocxDocument(io.BytesIO(state['data'])); markdown = '\n\n'.join(p.text for p in doc.paragraphs if p.text.strip())
    elif name.endswith('.pdf'):
        try:
            if not MINERU_URL: raise RuntimeError('MinerU is not configured')
            response = requests.post(MINERU_URL + '/parse', files={'file': ('document.pdf', state['data'], 'application/pdf')}, timeout=120)
            response.raise_for_status(); markdown = response.json().get('markdown', '')
            if not markdown.strip(): raise ValueError('MinerU returned no markdown')
        except Exception as error:
            degrade(state, 'CONVERT', 'MinerU fallback: ' + str(error))
            markdown = '\n\n'.join(page.extract_text() or '' for page in PdfReader(io.BytesIO(state['data'])).pages)
    else: raise ValueError('unsupported document type')
    if not markdown.strip(): raise ValueError('document contains no extractable text')
    key = 'markdown/%s/%s.md' % (state['document']['school_id'] or 'public', state['document']['id'])
    MINIO.put_object(BUCKET, key, io.BytesIO(markdown.encode()), len(markdown.encode()), content_type='text/markdown')
    execute("UPDATE knowledge_document SET markdown_object_key=%s WHERE id=%s", (key, state['document']['id']))
    return {**state, 'markdown': markdown, 'images': extract_images(state['data'], name)}

def extract_images(data, filename):
    if not filename.endswith('.docx'): return []
    values = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for member in archive.namelist():
            if member.startswith('word/media/'):
                values.append((member.rsplit('/', 1)[-1], archive.read(member)))
    return values

def understand_images(state):
    checkpoint(state, "IMAGE_VISION")
    images = []
    for name, data in state.get('images', []):
        digest = hashlib.sha256(data).hexdigest()
        cached = fetch('SELECT description, object_key, model FROM knowledge_document_image WHERE sha256=%s AND status=\'SUCCESS\' LIMIT 1', (digest,))
        key = 'images/%s/%s-%s' % (state['document']['id'], digest, name)
        if not cached:
            MINIO.put_object(BUCKET, key, io.BytesIO(data), len(data), content_type='application/octet-stream')
            description = ''
            if VISION_URL and VISION_KEY and VISION_MODEL:
                encoded = base64.b64encode(data).decode()
                response = requests.post(VISION_URL + '/internal/vision/analyze', headers={'X-Model-Gateway-Key': VISION_KEY}, json={'model':VISION_MODEL,'imageBase64':encoded}, timeout=90); response.raise_for_status(); description = response.json().get('description','')
            status = 'SUCCESS' if description else 'SKIPPED'
            if not description: degrade(state, 'IMAGE_VISION', 'vision model is unavailable or returned no description')
            execute('INSERT INTO knowledge_document_image(document_id,sha256,object_key,description,status,model) VALUES(%s,%s,%s,%s,%s,%s)', (state['document']['id'],digest,key,description,status,VISION_MODEL or None))
        else: key, description = cached['object_key'], cached['description'] or ''
        images.append({'name': name, 'description': description, 'object_key': key})
    markdown = state['markdown']
    for image in images:
        markdown += '\n\n![%s](%s)' % (image['description'] or image['name'], image['object_key'])
    return {**state, 'markdown': markdown, 'image_refs': images}

def split(state):
    checkpoint(state, "CHUNK")
    path, chunks, buffer = [], [], []
    def flush():
        nonlocal buffer
        text = '\n'.join(buffer).strip()
        while text:
            if len(text) > MAX_CHUNK_CHARS:
                boundary = max(text.rfind(mark, 0, MAX_CHUNK_CHARS) for mark in '。！？；\n')
                take = boundary + 1 if boundary >= MAX_CHUNK_CHARS // 2 else MAX_CHUNK_CHARS
                part, text = text[:take].strip(), text[take:].strip()
            else:
                part, text = text, ''
            prefix = (' > '.join(path) + '\n\n') if path else ''
            content = prefix + part
            chunks.append({'title_path': ' > '.join(path), 'content': content, 'token_count': max(1, len(content) // 3)})
        buffer = []
    for line in state['markdown'].splitlines():
        match = re.match(r'^(#{1,6})\s+(.+)', line)
        if match:
            flush(); level, title = len(match.group(1)), match.group(2).strip(); path[level - 1:] = [title]
        else:
            buffer.append(line)
            if len(' '.join(buffer).split()) >= 700: flush()
    flush()
    return {**state, 'chunks': chunks}

def metadata(state):
    checkpoint(state, "METADATA")
    value = {'subject': None, 'subjectType': None, 'tags': []}
    if MODEL_URL and MODEL_KEY and MODEL_NAME:
        try:
            prompt = 'Extract JSON only: {"subject":string|null,"subjectType":string|null,"tags":[string]}. Text:\n' + state['markdown'][:30000]
            response = requests.post(MODEL_URL + '/chat/completions', headers={'Authorization': 'Bearer ' + MODEL_KEY}, json={'model': MODEL_NAME, 'messages': [{'role':'user','content':prompt}], 'temperature':0, 'response_format': {'type':'json_object'}}, timeout=45)
            value = json.loads(response.json()['choices'][0]['message']['content'])
        except Exception as error:
            degrade(state, 'METADATA', 'metadata fallback: ' + str(error))
            value['subject'] = state['document'].get('title') or state['document'].get('original_filename')
            value['tags'] = [state['document'].get('original_filename', '').rsplit('.', 1)[0]][:1]
    return {**state, 'metadata': value}

def embed(texts):
    if not EMBEDDING_URL or not EMBEDDING_KEY: raise RuntimeError('embedding service is not configured')
    r = requests.post(EMBEDDING_URL + '/embeddings', headers={'Authorization':'Bearer ' + EMBEDDING_KEY}, json={'model':EMBEDDING_MODEL, 'input':texts, 'dimensions':DIMENSIONS}, timeout=60); r.raise_for_status()
    return [item['embedding'] for item in r.json()['data']]

def hybrid_embed(texts):
    if EMBEDDING_URL:
        response = requests.post(EMBEDDING_URL + '/internal/embeddings/hybrid', headers={'X-Model-Gateway-Key': EMBEDDING_KEY}, json={'model': EMBEDDING_MODEL, 'texts': texts}, timeout=90)
        if response.ok: return response.json()['items']
    return [{'dense': vector, 'sparse': {'indices': [], 'values': []}} for vector in embed(texts)]

def index(state):
    checkpoint(state, "INDEX")
    doc, meta = state['document'], state['metadata']
    if requests.get(QDRANT + '/collections/' + COLLECTION, timeout=10).status_code == 404:
        requests.put(QDRANT + '/collections/' + COLLECTION, json={'vectors': {'dense': {'size': DIMENSIONS, 'distance':'Cosine'}}, 'sparse_vectors': {'sparse': {}}}, timeout=20)
    execute('DELETE FROM knowledge_chunk WHERE document_id=%s', (doc['id'],))
    points = []
    for i in range(0, len(state['chunks']), 16):
        batch = state['chunks'][i:i+16]; vectors = hybrid_embed([x['content'] for x in batch])
        for offset, (chunk, vector) in enumerate(zip(batch, vectors)):
            point_id = str(uuid.uuid5(uuid.NAMESPACE_URL, 'knowledge:' + str(doc['id']) + ':' + str(i + offset)))
            execute('INSERT INTO knowledge_chunk(document_id,chunk_order,title_path,content,token_count,subject,subject_type,tags,qdrant_point_id) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)', (doc['id'],i+offset,chunk['title_path'],chunk['content'],chunk['token_count'],meta.get('subject'),meta.get('subjectType'),json.dumps(meta.get('tags', []), ensure_ascii=False),point_id))
            points.append({'id':point_id,'vector':{'dense':vector['dense'], 'sparse': vector['sparse']},'payload':{'documentId':doc['id'],'chunk_id':point_id,'entity_key':'knowledge-document:'+str(doc['id']),'schoolId':doc['school_id'],'titlePath':chunk['title_path'],'subject':meta.get('subject'),'subjectType':meta.get('subjectType'),'tags':meta.get('tags', []),'index_version':INDEX_VERSION,'documentTitle':doc['title'],'imageRefs':state.get('image_refs',[])}})
    r = requests.put(QDRANT + '/collections/' + COLLECTION + '/points?wait=true', json={'points':points}, timeout=90); r.raise_for_status()
    return state

def complete(state):
    checkpoint(state, "DONE"); status = 'DEGRADED' if state.get('degradations') else 'SUCCESS'; metadata = json.dumps({'degradations':state.get('degradations', []),'chunkCount':len(state.get('chunks', []))}, ensure_ascii=False); execute("UPDATE knowledge_document SET status=%s, indexed_at=NOW(), published_at=COALESCE(published_at,NOW()) WHERE id=%s", (status,state['document']['id'])); execute("UPDATE knowledge_ingest_job SET status=%s, current_node='DONE', metadata_json=%s, finished_at=NOW() WHERE id=%s", (status,metadata,state['job']['id'])); return state

graph = StateGraph(State)
for name, fn in [('validate',validate),('convert',convert),('image_vision',understand_images),('split',split),('metadata',metadata),('index',index),('complete',complete)]: graph.add_node(name, fn)
graph.add_edge(START,'validate'); graph.add_conditional_edges('validate', lambda s: END if s.get('deduplicated') else 'convert', {'convert':'convert', END:END})
for left, right in [('convert','image_vision'),('image_vision','split'),('split','metadata'),('metadata','index'),('index','complete'),('complete',END)]: graph.add_edge(left,right)
pipeline = graph.compile()

def process(job_id):
    row = fetch('SELECT j.*, d.* FROM knowledge_ingest_job j JOIN knowledge_document d ON d.id=j.document_id WHERE j.id=%s', (job_id,))
    if not row or row['status'] == 'SUCCESS': return
    job = {k: row[k] for k in ('id','document_id','status','current_node','retry_count')}; document = dict(row)
    try: pipeline.invoke({'job':job, 'document':document})
    except Exception as error:
        message = re.sub(r'(?i)(password|token|key)=?[^\s,]+', r'\1=[redacted]', str(error))[:1000]
        execute("UPDATE knowledge_document SET status='FAILED' WHERE id=%s", (document['id'],)); execute("UPDATE knowledge_ingest_job SET status='FAILED', retry_count=retry_count+1, error_summary=%s, finished_at=NOW() WHERE id=%s", (message, job_id))

def delete_vectors(document_id):
    requests.post(QDRANT + '/collections/' + COLLECTION + '/points/delete?wait=true', json={'filter': {'must': [{'key': 'documentId', 'match': {'value': document_id}}]}}, timeout=30).raise_for_status()

if __name__ == '__main__':
    if not MINIO.bucket_exists(BUCKET): MINIO.make_bucket(BUCKET)
    pubsub = REDIS.pubsub(ignore_subscribe_messages=True); pubsub.subscribe('knowledge:delete')
    while True:
        event = pubsub.get_message(timeout=0.1)
        if event:
            try: delete_vectors(int(event['data']))
            except Exception: pass
        item = REDIS.blpop(QUEUE, timeout=5)
        if item: process(int(item[1]))
