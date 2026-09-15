"""RocketMQ delivery lifecycle and connection-scoped MySQL execution protection."""
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
    cancelled = CANCELLED.get()
    if STOP.is_set() or (cancelled is not None and cancelled.is_set()):
        raise InterruptedError('ingestion ownership lost or shutdown requested')
    connection = CONNECTION.get()
    if connection is not None:
        connection.ping(reconnect=False)


def parse_event(body):
    event = json.loads(body)
    if not isinstance(event, dict) or event.get('schemaVersion') != 1:
        raise ValueError('unsupported message schema')
    for key in ('jobId', 'documentId', 'generation'):
        if type(event.get(key)) is not int or event[key] <= 0:
            raise ValueError('invalid message identifier')
    if not isinstance(event.get('eventId'), str) or not event['eventId']:
        raise ValueError('missing event id')
    return event


def fail(worker, job, document, summary):
    connection = CONNECTION.get()
    connection.begin()
    try:
        worker.execute("UPDATE knowledge_ingest_job SET status='FAILED', error_summary=%s, finished_at=NOW() WHERE id=%s", (summary, job['id']))
        worker.execute("UPDATE knowledge_document SET status='FAILED' WHERE id=%s", (document['id'],))
        connection.commit()
    except BaseException:
        connection.rollback()
        raise


def process(event, worker, cancelled=None, dead_letter=False):
    """True means terminal state is durable and this delivery may be acknowledged."""
    with worker.db() as connection:
        token = CONNECTION.set(connection)
        cancel_token = CANCELLED.set(cancelled)
        locked = False
        key = 'knowledge-document:' + str(event['documentId'])
        try:
            locked = worker.fetch('SELECT GET_LOCK(%s,0) AS acquired', (key,))['acquired'] == 1
            if not locked:
                return False
            job = worker.fetch('SELECT * FROM knowledge_ingest_job WHERE id=%s', (event['jobId'],))
            document = worker.fetch('SELECT * FROM knowledge_document WHERE id=%s', (event['documentId'],))
            if not job or not document:
                return True
            if job['document_id'] != document['id']:
                LOG.error('Rejected mismatched task reference: eventId=%s', event['eventId'])
                return True
            if job['generation'] != event['generation']:
                return True
            if job['status'] in ('SUCCESS', 'DEGRADED', 'FAILED'):
                return True
            if dead_letter or job['execution_attempts'] >= MAX_ATTEMPTS:
                fail(worker, job, document, 'delivery or execution attempts exhausted')
                return True
            worker.execute("UPDATE knowledge_ingest_job SET execution_attempts=execution_attempts+1, status='RUNNING', finished_at=NULL WHERE id=%s", (job['id'],))
            worker.execute("UPDATE knowledge_document SET status='RUNNING' WHERE id=%s", (document['id'],))
            try:
                worker.pipeline.invoke({'job': job, 'document': document})
                guard()
                return True
            except Exception as error:
                guard()
                summary = type(error).__name__
                if isinstance(error, ValueError) or job['execution_attempts'] + 1 >= MAX_ATTEMPTS:
                    fail(worker, job, document, summary)
                    return True
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
                    with connection.cursor() as cursor:
                        cursor.execute('SELECT RELEASE_LOCK(%s)', (key,))
                except Exception:
                    pass
            CONNECTION.reset(token)
            CANCELLED.reset(cancel_token)


def handle(consumer, message, worker, dead_letter=False):
    try:
        event = parse_event(message.body)
    except (ValueError, TypeError, UnicodeError):
        LOG.error('Rejected malformed ingestion message: messageId=%s', message.message_id)
        consumer.ack(message)
        return
    done, lost = threading.Event(), threading.Event()
    def renew():
        while not done.wait(RENEW_SECONDS):
            try:
                consumer.change_invisible_duration(message, INVISIBLE_SECONDS)
            except Exception:
                lost.set()
                return
    thread = threading.Thread(target=renew, daemon=True)
    thread.start()
    try:
        terminal = process(event, worker, lost, dead_letter)
        done.set()
        thread.join(timeout=15)
        if thread.is_alive():
            lost.set()
        if terminal and not lost.is_set() and not STOP.is_set():
            consumer.ack(message)
        elif not STOP.is_set() and not lost.is_set():
            consumer.change_invisible_duration(message, 10)
    finally:
        done.set()
        thread.join(timeout=15)


def main(worker):
    from rocketmq import ClientConfiguration, Credentials, FilterExpression, SimpleConsumer
    logging.basicConfig(level=logging.INFO)
    if not 0 < RENEW_SECONDS < INVISIBLE_SECONDS:
        raise ValueError('renew interval must be less than visibility duration')
    if not 1 <= MAX_ATTEMPTS <= 3 or REQUEST_TIMEOUT_SECONDS <= 0:
        raise ValueError('execution attempts must be 1..3 and request timeout must be positive')
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: STOP.set())
    config = ClientConfiguration(os.getenv('ROCKETMQ_ENDPOINTS', 'localhost:18081'), Credentials(), request_timeout=REQUEST_TIMEOUT_SECONDS)
    group = os.getenv('ROCKETMQ_CONSUMER_GROUP', 'knowledge-ingest-worker')
    topic = os.getenv('ROCKETMQ_TOPIC', 'knowledge-ingest')
    consumer = SimpleConsumer(config, group, {topic: FilterExpression()}, await_duration=5)
    dead = SimpleConsumer(config, group + '-dead', {'%DLQ%' + group: FilterExpression()}, await_duration=1)
    # SDK startup waits for telemetry settings without its own deadline. Fail the process
    # before claiming work if a wrong advertised endpoint would leave startup stuck forever.
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
            worker.MINIO.make_bucket(worker.BUCKET)
        LOG.info('Ingestion consumer ready: topic=%s, group=%s', topic, group)
        while not STOP.is_set():
            for current, is_dead in ((consumer, False), (dead, True)):
                if STOP.is_set(): break
                try:
                    for message in current.receive(1, INVISIBLE_SECONDS) or []:
                        handle(current, message, worker, is_dead)
                except Exception as error:
                    LOG.warning('Ingestion delivery deferred: error=%s', type(error).__name__)
                    STOP.wait(1)
    finally:
        watchdog.cancel()
        consumer.shutdown()
        dead.shutdown()
