import json
import threading
import time
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
import ingest_runtime as runtime


def event(**changes):
    return dict(schemaVersion=1, eventId='event-1', jobId=8, documentId=3, generation=1, **changes)


@pytest.fixture(autouse=True)
def reset_stop():
    runtime.STOP.clear()
    yield
    runtime.STOP.clear()


def fake_worker(status='PENDING', attempts=0, generation=1, locked=True):
    connection = MagicMock()
    connection.__enter__.return_value = connection
    worker = SimpleNamespace(db=MagicMock(return_value=connection), execute=MagicMock(), pipeline=MagicMock())
    worker.fetch = MagicMock(side_effect=[{'acquired': int(locked)},
        {'id': 8, 'document_id': 3, 'generation': generation, 'status': status, 'execution_attempts': attempts}, {'id': 3}])
    return worker


@pytest.mark.parametrize('status', ['SUCCESS', 'DEGRADED', 'FAILED'])
def test_completed_delivery_does_not_execute_again(status):
    worker = fake_worker(status=status)
    assert runtime.process(event(), worker)
    worker.pipeline.invoke.assert_not_called()


def test_stale_generation_and_busy_document_do_not_execute():
    worker = fake_worker(generation=2)
    assert runtime.process(event(), worker)
    worker.pipeline.invoke.assert_not_called()
    worker = fake_worker(locked=False)
    assert not runtime.process(event(), worker)
    worker.pipeline.invoke.assert_not_called()


def test_failure_retries_then_becomes_terminal():
    worker = fake_worker()
    worker.pipeline.invoke.side_effect = RuntimeError('provider unavailable')
    assert not runtime.process(event(), worker)
    worker = fake_worker(attempts=2)
    worker.pipeline.invoke.side_effect = RuntimeError('provider unavailable')
    assert runtime.process(event(), worker)
    assert any("status='FAILED'" in call.args[0] for call in worker.execute.call_args_list)


def test_dead_letter_is_persisted_without_execution():
    worker = fake_worker()
    assert runtime.process(event(), worker, dead_letter=True)
    worker.pipeline.invoke.assert_not_called()
    assert any("status='FAILED'" in call.args[0] for call in worker.execute.call_args_list)


def test_job_and_document_ids_are_distinct():
    worker = fake_worker()
    assert runtime.process(event(), worker)
    state = worker.pipeline.invoke.call_args.args[0]
    assert state['job']['id'] == 8
    assert state['document']['id'] == 3


def test_mismatched_reference_is_rejected_without_touching_another_job():
    worker = fake_worker()
    worker.fetch.side_effect = [{'acquired': 1}, {'id':8,'document_id':999}, {'id':3}]
    assert runtime.process(event(),worker)
    worker.execute.assert_not_called()
    worker.pipeline.invoke.assert_not_called()


def test_invalid_message_rejected():
    for bad in (b'no json', b'{}', b'{"schemaVersion":2}'):
        with pytest.raises(ValueError): runtime.parse_event(bad)


def test_renewal_failure_prevents_ack(monkeypatch):
    monkeypatch.setattr(runtime, 'RENEW_SECONDS', .01)
    consumer = MagicMock()
    consumer.change_invisible_duration.side_effect = RuntimeError('disconnected')
    def finish(*args):
        time.sleep(.04)
        return True
    monkeypatch.setattr(runtime, 'process', finish)
    runtime.handle(consumer, SimpleNamespace(body=json.dumps(event()), message_id='m'), None)
    consumer.ack.assert_not_called()


@pytest.mark.parametrize('terminal', [True, False])
def test_ack_only_terminal(monkeypatch, terminal):
    monkeypatch.setattr(runtime, 'process', lambda *args: terminal)
    consumer = MagicMock()
    runtime.handle(consumer, SimpleNamespace(body=json.dumps(event()), message_id='m'), None)
    assert consumer.ack.called is terminal


def test_connection_loss_never_reconnects():
    connection = MagicMock()
    connection.ping.side_effect = RuntimeError('connection lost')
    token = runtime.CONNECTION.set(connection)
    try:
        with pytest.raises(RuntimeError): runtime.guard()
        connection.ping.assert_called_once_with(reconnect=False)
    finally:
        runtime.CONNECTION.reset(token)
