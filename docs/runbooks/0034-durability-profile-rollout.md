# Production durability profile: staged rollout (ADR-0373)

Applies the durability profile of ADR-0373 to the ledger-class databases and to Kafka, in four
steps. Each step is separately mergeable, separately verifiable and separately reversible. **Do not
merge two steps in one PR and do not start a step before the previous one's verification passed.**
The order exists so that no step makes anything unwritable: the minimum in-sync value is raised last.

Ledger-class clusters (label `openbank.io/durability-class: ledger-class`): the bank's ledger
database and every separate-entity ledger deployment (ADR-0337). All other clusters keep ADR-0159.

All verification commands are read-only. Apply and rollback commands are Git changes that ArgoCD
reconciles; nothing here is run against the cluster by hand except the two partition-reassignment
operations in step 3, which are marked.

## Design constraint that shapes step 1 (Kafka)

Strimzi uses a static KRaft controller quorum. Its documentation lists scaling a node pool that has
the controller role, and adding or removing the controller role, as unsupported. The existing pool
`dual` is controller+broker, so it stays at one replica. Extra brokers are a second, broker-only pool
`brokers`. Result: **three brokers, still one controller.** Replicated partitions survive the loss of
a broker, but the metadata quorum is a single point of failure until the controllers are moved to
three, which is a separate migration and not part of this runbook.

## Step 1: third database instance and two more Kafka brokers

Change: `instances: 3` on each ledger-class Cluster, the durability-class label, and the new
`KafkaNodePool` `brokers` (2 replicas). No synchronous setting, no replication factor, no
`min.insync.replicas` change. Synchronous replication stays off, so the write path is unchanged.

Verify (read-only):

```bash
# every ledger-class cluster has 3 ready instances and a streaming standby set
kubectl get clusters.postgresql.cnpg.io -A -l openbank.io/durability-class=ledger-class \
  -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,WANT:.spec.instances,READY:.status.readyInstances
# replication state: three rows per cluster, two in streaming state, lag small and not growing
kubectl -n ledger exec ledger-db-1 -c postgres -- psql -U postgres -Atc \
  "select application_name, state, sync_state, replay_lag from pg_stat_replication"
# the three pods sit on three different nodes
kubectl -n ledger get pods -l cnpg.io/cluster=ledger-db -o wide
# brokers: three Ready pods on distinct nodes, the cluster Ready, no partition under-replicated
kubectl -n messaging get kafkanodepools,kafka
kubectl -n messaging get pods -l strimzi.io/name=openbank-cluster-kafka -o wide
kubectl -n messaging exec openbank-cluster-dual-0 -- bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --under-replicated-partitions   # expect: no output
```

(Adjust the bootstrap address and client config to the internal listener actually exposed; the
plaintext port was removed, so the command needs the broker's own client properties file.)

Also confirm the new broker nodes hold no partitions yet (expected: topics still RF 1 on the old
broker) and that producer and consumer error rates did not move.

Rollback: revert the PR. For the databases CNPG scales back to 2 and removes the newest instance
and its volume. For Kafka, **do not** revert once step 3 has placed replicas on the new brokers:
reassign partitions back to the original broker first (step 3 rollback), then delete the pool.

Not covered by this step, and a precondition for step 2: the stateful node pool is sized from
measured requests (`stateful_load_by_zone`); the third instances add requests there. Check the
infrastructure root's measurement is still within the limit before merging.

## Step 2: synchronous quorum commit on ledger-class clusters

Precondition: step 1 verified, every instance of both clusters streaming and caught up.

Change, per ledger-class Cluster:

```yaml
spec:
  postgresql:
    synchronous:
      method: any
      number: 1
      dataDurability: required
```

CloudNativePG documents this as quorum-based synchronous replication (`ANY 1 (...)`), with
`required` meaning writes pause when the needed number of standbys is unavailable, and `preferred`
meaning writes continue with fewer standbys. `required` is the ADR's intent (an acknowledged commit
survives the loss of any one instance); with three instances, losing one still leaves one standby to
acknowledge. Do not combine with the deprecated `minSyncReplicas`/`maxSyncReplicas`: they are
mutually exclusive with this block.

Verify (read-only):

```bash
kubectl -n ledger exec ledger-db-1 -c postgres -- psql -U postgres -Atc "show synchronous_standby_names"
# expect an ANY 1 (...) list naming the two standbys
kubectl -n ledger exec ledger-db-1 -c postgres -- psql -U postgres -Atc \
  "select application_name, sync_state from pg_stat_replication"            # expect: quorum
```

Measure and record ledger write latency before and after (commit latency now includes a standby round
trip). The decision to keep `required` rests on that number.

Rollback: remove the `synchronous` block (revert the PR). Async replication resumes; nothing else
changes. If writes stall because standbys are unavailable, the same revert restores write
availability, at the cost of the durability guarantee until the standbys are healthy again.

## Step 3: reassign existing Kafka partitions to three replicas

Precondition: step 1 verified, three brokers Ready.

Per the Strimzi documentation, changing `spec.replicas` on a `KafkaTopic` is not an operation the
Topic Operator performs by itself; it requires Cruise Control. Cruise Control is **not deployed in
this repository today**. Choose one path and record which:

- **A. Cruise Control.** Add `cruiseControl` to the `Kafka` CR (a small separate PR; adding it may
  roll the brokers, including the only controller, so schedule it as a brief maintenance window and
  expect producers to retry). Then raise `spec.replicas` to 3 in the `KafkaTopic` manifests and let
  the operator drive the reassignment.
- **B. Partition reassignment tool** (`kafka-reassign-partitions.sh`), per topic or in batches,
  with throttling. Required in either path for the internal topics (offsets, transaction state),
  which no `KafkaTopic` manifest manages. This is a hand-run operation: write the reassignment plan
  to the change ticket first.

Under either path, change the 67 declared topic replicas to 3 in the manifests only once their
actual replica sets are 3 (so Git never claims a state the cluster does not have), and keep
`min.insync.replicas` at 1 during this step.

Verify (read-only):

```bash
kubectl -n messaging exec openbank-cluster-dual-0 -- bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe | awk '/ReplicationFactor/ {print $5, $6}' | sort | uniq -c
# expect every topic, internal ones included, at ReplicationFactor: 3
kubectl -n messaging exec openbank-cluster-dual-0 -- bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --under-replicated-partitions   # expect: no output
```

Rollback: a reassignment is reversible by reassigning to the previous replica set; the topic
manifests revert with the PR. Do this before removing the `brokers` pool.

## Step 4: raise replication defaults and minimum in-sync replicas

Precondition: step 3 verified: no topic below three replicas and none under-replicated. Raising
`min.insync.replicas` before that makes every under-replicated topic reject writes.

Change on the `Kafka` CR `config` (and on topic manifests that set it explicitly):

```yaml
offsets.topic.replication.factor: 3
transaction.state.log.replication.factor: 3
transaction.state.log.min.isr: 2
default.replication.factor: 3
min.insync.replicas: 2
```

Producers on the outbox path must send with `acks=all`; confirm that setting in the service
configuration before this step, because `min.insync.replicas` only protects writes acknowledged
by all in-sync replicas.

Verify (read-only):

```bash
kubectl -n messaging get kafka openbank-cluster -o jsonpath='{.spec.kafka.config}'
kubectl -n messaging exec openbank-cluster-dual-0 -- bin/kafka-configs.sh \
  --bootstrap-server localhost:9092 --entity-type brokers --entity-default --describe
# producer error rate and outbox attempt counts flat for an hour after the broker roll
```

Rollback: revert the config change; the brokers roll back to the previous values. Replica sets stay
at three, which is harmless.

## Final drill (ADR-0373 point 6)

Run after step 4, in the environment where the manifests live, under write load:

1. Kill the ledger primary pod while a load generator posts journals and records each
   acknowledgement. After recovery, compare the acknowledged set with the committed set.
2. Kill one Kafka broker while a producer on the outbox path publishes and records each
   acknowledgement. After recovery, compare the acknowledged set with what a consumer reads.
3. Record, in `docs/bcp/dr-test-log.md`: the date, the number of acknowledged-but-lost writes
   (expected zero for both), and the time to recover for each. Use the literal phrase
   `acknowledged-but-lost` so the delivery check in ADR-0373 can find it.
4. Do not kill the broker that also holds the controller role as part of this drill and call it a
   broker test: that exercises the single controller, which this profile does not make redundant.
