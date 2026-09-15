#!/bin/sh
set -eu
# mqadmin may return exit code zero after printing an exception: verify its result too.
run_admin() {
    result=$(sh mqadmin "$@")
    printf '%s\n' "$result"
    printf '%s\n' "$result" | grep -q 'success'
}
topic=${ROCKETMQ_TOPIC:-knowledge-ingest}
group=${ROCKETMQ_CONSUMER_GROUP:-knowledge-ingest-worker}
state_dir=${ROCKETMQ_INIT_STATE_DIR:-/home/rocketmq/store/ingestion-bootstrap}
mkdir -p "$state_dir"
bootstrap_group() {
    target_topic=$1
    target_group=$2
    marker="$state_dir/$target_group.initialized"
    if [ -f "$marker" ]; then
        printf 'Offsets already initialized: %s\n' "$target_group"
        return
    fi
    # Proxy POP starts a previously unseen queue at MAX, regardless of consumeFromMinEnable.
    # Seed every queue before enabling publishers. Never reset established groups on restart.
    result=$(sh mqadmin resetOffsetByTime -n rocketmq-namesrv:9876 -g "$target_group" -t "$target_topic" -s 0)
    printf '%s\n' "$result"
    printf '%s\n' "$result" | grep -q '#offset'
    printf '%s\n' "$result" | grep -q '^knowledge-broker'
    touch "$marker"
}
run_admin updateTopic -n rocketmq-namesrv:9876 -c KnowledgeCluster -t "$topic" -r 4 -w 4 -a +message.type=NORMAL
run_admin updateSubGroup -n rocketmq-namesrv:9876 -c KnowledgeCluster -g "$group" -r 16 -m true
bootstrap_group "$topic" "$group"
run_admin updateTopic -n rocketmq-namesrv:9876 -c KnowledgeCluster -t "%DLQ%$group" -r 1 -w 1 -p 6
# Failure reconciliation must survive a long MySQL outage without promptly creating
# an unmonitored second-level DLQ. This group never executes the ingestion pipeline.
run_admin updateSubGroup -n rocketmq-namesrv:9876 -c KnowledgeCluster -g "$group-dead" -r 1000000 -m true
bootstrap_group "%DLQ%$group" "$group-dead"
