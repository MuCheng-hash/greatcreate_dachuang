"""RocketMQ 投递生命周期与连接级 MySQL 执行保护。"""
import json
import logging
import os
import signal
import threading
from contextvars import ContextVar

CONNECTION = ContextVar('ingest_connection', default=None)
CANCELLED = ContextVar('ingest_cancelled', default=None)
STOP = threading.Event()
LOG = logging.getLogger(__name__)
INVISIBLE_SECONDS = int(os.getenv('INGEST_INVISIBLE_SECONDS', '120'))
RENEW_SECONDS = int(os.getenv('INGEST_RENEW_SECONDS', '30'))
MAX_ATTEMPTS = int(os.getenv('INGEST_MAX_ATTEMPTS', '3'))
REQUEST_TIMEOUT_SECONDS = int(os.getenv('ROCKETMQ_REQUEST_TIMEOUT_SECONDS', '10'))


def guard():
    """在每次外部 I/O 前确认当前投递仍持有执行权和原始数据库连接。

    消费者续租失败、进程收到停止信号或连接已断开时立即中止流水线，防止
    已失去消息可见性控制权的工作进程继续写入文档、向量或任务状态。
    """
    cancelled = CANCELLED.get()
    # 停止信号和续租失败都意味着当前消费者不再拥有这条消息，后续写入必须停止。
    if STOP.is_set() or (cancelled is not None and cancelled.is_set()):
        raise InterruptedError('ingestion ownership lost or shutdown requested')
    connection = CONNECTION.get()
    if connection is not None:
        # 禁止连接自动重连：重连后的会话不再拥有此前取得的 MySQL 命名锁。
        connection.ping(reconnect=False)


def parse_event(body):
    """校验 RocketMQ 投递载荷并返回可安全用于数据库查询的事件标识。

    只接受版本为 1 且三个业务标识均为正整数的事件；畸形事件会在消息层
    直接确认，避免同一无法解析的投递无限重试。
    """
    # 先解析 JSON，再校验结构，避免把任意字典直接传给后续 SQL 查询。
    event = json.loads(body)
    if not isinstance(event, dict) or event.get('schemaVersion') != 1:
        raise ValueError('unsupported message schema')
    for key in ('jobId', 'documentId', 'generation'):
        # bool 是 int 的子类，使用 type 精确排除布尔值等非业务编号。
        if type(event.get(key)) is not int or event[key] <= 0:
            raise ValueError('invalid message identifier')
    if not isinstance(event.get('eventId'), str) or not event['eventId']:
        raise ValueError('missing event id')
    return event


def fail(worker, job, document, summary):
    """在同一 MySQL 事务中把任务和文档同步标记为不可恢复失败。

    任务、文档终态必须同时提交，避免用户看到文档仍可用而后台任务已经终止。
    该函数只处理确定性失败或达到最大执行次数后的终态迁移。
    """
    connection = CONNECTION.get()
    # 两张表的终态是同一业务事实，必须放入同一事务提交。
    connection.begin()
    try:
        worker.execute("UPDATE knowledge_ingest_job SET status='FAILED', error_summary=%s, finished_at=NOW() WHERE id=%s", (summary, job['id']))
        worker.execute("UPDATE knowledge_document SET status='FAILED' WHERE id=%s", (document['id'],))
        connection.commit()
    except BaseException:
        # 提交失败时撤销两张表的更新，让消息重投后重新收敛状态。
        connection.rollback()
        raise


def process(event, worker, cancelled=None, dead_letter=False):
    """在文档级互斥锁下执行一次可重放的入库任务。

    返回 ``True`` 表示此次事件已得到持久化终态，可以确认消息；返回 ``False``
    表示任务仍可重试，调用方必须保留或缩短消息可见性而不能确认。generation
    和文档锁共同避免旧投递覆盖新任务，死信投递则直接落为失败而不执行流水线。
    """
    with worker.db() as connection:
        token = CONNECTION.set(connection)
        cancel_token = CANCELLED.set(cancelled)
        locked = False
        key = 'knowledge-document:' + str(event['documentId'])
        try:
            # 锁以文档为粒度；同一文档的重复投递只允许一个消费者进入流水线。
            locked = worker.fetch('SELECT GET_LOCK(%s,0) AS acquired', (key,))['acquired'] == 1
            if not locked:
                # 其他消费者仍在处理同一文档；不确认以便稍后由 Broker 重投。
                return False
            # 锁已取得后才读取任务与文档，确保读取到的状态不会被并行投递推进。
            job = worker.fetch('SELECT * FROM knowledge_ingest_job WHERE id=%s', (event['jobId'],))
            document = worker.fetch('SELECT * FROM knowledge_document WHERE id=%s', (event['documentId'],))
            if not job or not document:
                # 已删除或不存在的业务对象无法恢复，确认毒消息避免无意义重试。
                return True
            if job['document_id'] != document['id']:
                # eventId 只用于日志关联；任务与文档关系必须以数据库所有权为准。
                LOG.error('Rejected mismatched task reference: eventId=%s', event['eventId'])
                return True
            # generation 不一致说明投递来自已被重新提交的旧任务，绝不能回写新状态。
            if job['generation'] != event['generation']:
                return True
            if job['status'] in ('SUCCESS', 'DEGRADED', 'FAILED'):
                # 终态任务可重复收到消息，但不得重跑并覆盖已完成或已失败的结果。
                return True
            if dead_letter or job['execution_attempts'] >= MAX_ATTEMPTS:
                # 死信消息及已耗尽尝试次数的任务直接收口为 FAILED，阻止再次进入外部流水线。
                fail(worker, job, document, 'delivery or execution attempts exhausted')
                return True
            # 每次真正尝试先计数并置为 RUNNING，重投时才能依据持久化次数决定是否止损。
            worker.execute("UPDATE knowledge_ingest_job SET execution_attempts=execution_attempts+1, status='RUNNING', finished_at=NULL WHERE id=%s", (job['id'],))
            worker.execute("UPDATE knowledge_document SET status='RUNNING' WHERE id=%s", (document['id'],))
            try:
                # 图内部不吞没致命异常；运行时统一决定重试、失败和消息确认。
                worker.pipeline.invoke({'job': job, 'document': document})
                # 图执行结束后再次确认租约，避免在失权窗口内确认消息。
                guard()
                return True
            except Exception as error:
                # 续租失败或停止时 guard 会抛出 InterruptedError，交给调用方保留消息重投。
                guard()
                summary = type(error).__name__
                if isinstance(error, ValueError) or job['execution_attempts'] + 1 >= MAX_ATTEMPTS:
                    # 参数、格式和业务校验错误不可通过重试修复；达到上限也必须结束任务。
                    fail(worker, job, document, summary)
                    return True
                # 可恢复异常回到 PENDING；状态更新必须提交后才允许 Broker 重新投递。
                connection.begin()
                try:
                    worker.execute("UPDATE knowledge_ingest_job SET status='PENDING', retry_count=retry_count+1, error_summary=%s WHERE id=%s", (summary, job['id']))
                    worker.execute("UPDATE knowledge_document SET status='PENDING' WHERE id=%s", (document['id'],))
                    connection.commit()
                except BaseException:
                    connection.rollback()
                    raise
                return False
        finally:
            if locked:
                try:
                    # 释放失败不覆盖主流程结果；数据库连接关闭也会回收会话级命名锁。
                    with connection.cursor() as cursor:
                        cursor.execute('SELECT RELEASE_LOCK(%s)', (key,))
                except Exception:
                    pass
            # ContextVar 恢复上层上下文，防止下一条消息复用当前连接或取消事件。
            CONNECTION.reset(token)
            CANCELLED.reset(cancel_token)


def handle(consumer, message, worker, dead_letter=False):
    """管理单条消息的续租、处理和确认时机。

    后台线程持续延长不可见时间；任何续租失败均视为执行权丢失，即使本地处理
    已返回终态也不确认消息，让 Broker 通过重新投递恢复一致性。
    """
    try:
        event = parse_event(message.body)
    except (ValueError, TypeError, UnicodeError):
        # 载荷本身无业务恢复路径，立即确认以避免消费者被同一坏消息卡住。
        LOG.error('Rejected malformed ingestion message: messageId=%s', message.message_id)
        consumer.ack(message)
        return
    done, lost = threading.Event(), threading.Event()
    def renew():
        """在流水线完成前维持消息租约，避免长文档处理期间被其他消费者获取。"""
        while not done.wait(RENEW_SECONDS):
            try:
                # 处理耗时可能超过初始不可见时间，续租维持这条消息的独占消费权。
                consumer.change_invisible_duration(message, INVISIBLE_SECONDS)
            except Exception:
                # Broker 不可达时不能再相信本地执行权；由主线程放弃确认并等待重投。
                lost.set()
                return
    thread = threading.Thread(target=renew, daemon=True)
    thread.start()
    try:
        terminal = process(event, worker, lost, dead_letter)
        # 通知续租线程退出，再决定确认或缩短可见性，避免与确认操作并发。
        done.set()
        thread.join(timeout=15)
        if thread.is_alive():
            # 无法在有限时间内停止续租线程时保守地视为失权，避免错误确认。
            lost.set()
        if terminal and not lost.is_set() and not STOP.is_set():
            # 仅持有有效租约且终态已提交时确认，保证确认不早于持久化结果。
            consumer.ack(message)
        elif not STOP.is_set() and not lost.is_set():
            # 可恢复失败缩短不可见时间，尽快触发重投而无需等待完整租约。
            consumer.change_invisible_duration(message, 10)
    finally:
        done.set()
        thread.join(timeout=15)


def main(worker):
    """启动正常主题和死信主题消费者，并在进程关闭时有序停止。

    启动看门狗专门处理 RocketMQ SDK 在错误代理端点下可能无限等待的问题；
    服务尚未可用时进程退出可让编排平台重新创建健康实例。
    """
    from rocketmq import ClientConfiguration, Credentials, FilterExpression, SimpleConsumer
    logging.basicConfig(level=logging.INFO)
    if not 0 < RENEW_SECONDS < INVISIBLE_SECONDS:
        # 续租必须在消息重新可见前发生，否则长任务会产生并发处理。
        raise ValueError('renew interval must be less than visibility duration')
    if not 1 <= MAX_ATTEMPTS <= 3 or REQUEST_TIMEOUT_SECONDS <= 0:
        raise ValueError('execution attempts must be 1..3 and request timeout must be positive')
    for sig in (signal.SIGTERM, signal.SIGINT):
        # 信号处理器只置位，避免在异步中断上下文直接关闭 SDK 连接。
        signal.signal(sig, lambda *_: STOP.set())
    config = ClientConfiguration(os.getenv('ROCKETMQ_ENDPOINTS', 'localhost:18081'), Credentials(), request_timeout=REQUEST_TIMEOUT_SECONDS)
    group = os.getenv('ROCKETMQ_CONSUMER_GROUP', 'knowledge-ingest-worker')
    topic = os.getenv('ROCKETMQ_TOPIC', 'knowledge-ingest')
    consumer = SimpleConsumer(config, group, {topic: FilterExpression()}, await_duration=5)
    dead = SimpleConsumer(config, group + '-dead', {'%DLQ%' + group: FilterExpression()}, await_duration=1)
    # SDK 启动会等待遥测配置，但自身没有截止时间。如果公布的端点错误，
    # 可能导致启动永久卡住，因此应在认领任务前终止进程。
    def startup_timeout():
        LOG.error('RocketMQ startup timed out; check proxy advertised endpoint')
        os._exit(1)
    watchdog = threading.Timer(60, startup_timeout)
    watchdog.daemon = True
    watchdog.start()
    try:
        consumer.startup()
        dead.startup()
        watchdog.cancel()
        if not worker.MINIO.bucket_exists(worker.BUCKET):
            # 首次部署初始化存储桶；已存在时保持原有权限和内容不变。
            worker.MINIO.make_bucket(worker.BUCKET)
        LOG.info('Ingestion consumer ready: topic=%s, group=%s', topic, group)
        while not STOP.is_set():
            for current, is_dead in ((consumer, False), (dead, True)):
                if STOP.is_set(): break
                try:
                    # 正常主题和死信主题共用同一处理语义，死信标记决定是否直接失败收口。
                    for message in current.receive(1, INVISIBLE_SECONDS) or []:
                        handle(current, message, worker, is_dead)
                except Exception as error:
                    # 接收阶段错误不改变任务状态，短暂退避后由 SDK/Broker 恢复连接和投递。
                    LOG.warning('Ingestion delivery deferred: error=%s', type(error).__name__)
                    STOP.wait(1)
    finally:
        # 无论启动还是消费阶段失败，都主动停止两个消费者，释放网络和线程资源。
        watchdog.cancel()
        consumer.shutdown()
        dead.shutdown()
