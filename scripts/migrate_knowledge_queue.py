"""Inventory legacy queue; opt-in, idempotent backfill of unfinished MySQL jobs.

Stop uploads and all old/new workers first. Requires PyMySQL and redis packages.
Never pops/deletes Redis entries. SQL schema migration must already be applied.
"""
import argparse
import json
import os
import uuid
import pymysql
import redis


def backfill(connection, apply=False):
    with connection.cursor() as cursor:
        cursor.execute("SELECT id,document_id,generation,status FROM knowledge_ingest_job WHERE status IN ('PENDING','RUNNING') ORDER BY id")
        jobs = cursor.fetchall()
        if apply:
            for job in jobs:
                cursor.execute("""INSERT IGNORE INTO knowledge_ingest_outbox(event_id,job_id,document_id,generation)
                    VALUES(%s,%s,%s,%s)""", (str(uuid.uuid4()), job['id'], job['document_id'], job['generation']))
            connection.commit()
        return jobs


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    queue = redis.Redis.from_url(os.environ['REDIS_URL'], decode_responses=True)
    # LRANGE is read-only; preserve the complete legacy queue for reconciliation/rollback.
    ids = queue.lrange('knowledge:ingest', 0, -1)
    with pymysql.connect(host=os.getenv('MYSQL_HOST','localhost'), port=int(os.getenv('MYSQL_PORT','3306')),
            user=os.environ['MYSQL_USER'], password=os.environ['MYSQL_PASSWORD'], database=os.environ['MYSQL_DATABASE'],
            charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor) as connection:
        jobs = backfill(connection, args.apply)
    print(json.dumps({'applied':args.apply,'redisJobIds':ids,'unfinishedJobs':jobs}, ensure_ascii=False))
