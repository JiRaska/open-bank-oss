# Kafka Streams velocity shadow

The opt-in topology computes one-hour and UTC-day bucket snapshots from transaction
initiations into `openbank.fraud.shadow.velocity.v1`. It is independent of the
current PostgreSQL/Redis feature path and has no consumer in fraud scoring.

## Wire and time semantics

The producer's `ce-type` is `openbank.transactions.transaction.initiated`; its body
`eventType` is `TransactionInitiated`. Other lifecycle messages sharing that topic
are ignored. The outbox `ce-id` supplies the deduplication identity; body `eventId`
is a different UUID. Missing identities, malformed payloads and missing/invalid or
more-than-five-minutes-future business timestamps are rejected with bounded metric
labels. Designated synthetic traffic is excluded from real feature buckets.

Business time is assigned at the source boundary before Streams advances its
watermark. Records are explicitly repartitioned by source account. Both windows
allow one hour after their end for late updates; the hourly window can close while
the daily one remains open. Identity state lasts two days. Original broker
topic/partition/offset survives repartition; it is never replaced by epoch millis.

The output schema is version 1 in the fleet AsyncAPI document. Kafka key is
`accountId:window:windowStartEpochMillis`; count is the latest observed bucket total.
The last contributing event time is **not** an as-of watermark. Values may be
updated out of order across buckets and must not be fed directly into an arbitrary
point-in-time feature query. Exactly-once Kafka transactions do not replace the
separate event-ID redelivery guard.

## Running the pilot

The default `openbank.fraud.streams-shadow.enabled=false` creates no Kafka Streams
client. Enabling it requires both an absolute external connection-properties file
and an absolute writable state directory, outside the application artifact path:

```properties
openbank.fraud.streams-shadow.enabled=true
openbank.fraud.streams-shadow.properties-file=/etc/openbank-shadow/client.properties
openbank.fraud.streams-shadow.state-directory=/var/lib/openbank-shadow
openbank.fraud.streams-shadow.partitions=1
```

Keep connection values and credentials in environment-owned secrets, never in the
repository. The external file accepts Kafka bootstrap and allowlisted TLS/SASL
connection keys only. SSL and SASL_SSL validate endpoint identity; PLAINTEXT is
accepted only for explicitly loopback bootstrap addresses in local experiments.
Application ID, processing guarantee, thread count and serdes cannot be supplied
through that file. The application ID is `openbank-fraud-shadow-velocity-v1` and
processing uses `exactly_once_v2`.

The dedicated `fraud-streams-shadow` Kafka identity has separate source/output,
group, transaction and internal-topic ACLs. The GitOps topic definitions pre-create
the one-partition output/repartition/changelog topics when the fraud Argo application
syncs the merged change. Resource provisioning does not enable the client. The
repartition topic alone permits Delete for consumed-record cleanup; no Create grant
is needed. Keep the partition setting
aligned; changing it under an existing application ID requires a separately planned
state migration. Mount the dedicated identity's credentials into the external file.
Do not reuse the normal fraud consumer's group or Redis keys.

The process exposes `openbank_fraud_streams_shadow_state` (0 disabled/starting,
1 running, -1 failed) and outcome-tagged `openbank_fraud_streams_shadow_records_total`.
No shadow health check gates the HTTP API. Runtime failure is sticky and counted;
configuration errors fail startup with sanitized diagnostics. Client/native logs,
resource pressure and local-state volume must be assessed in the pilot environment.
The state directory and changelogs contain account-derived data and inherit the
existing retention/erasure obligations; review that lifecycle before real-data
activation. Three-day changelog retention covers the store window plus recovery
margin, while output snapshots expire after seven days.

## Evidence and rollback

```sh
./gradlew :openbank-fraud-service:test \
  --tests '*ShadowVelocityTopologyTest' \
  --tests '*ShadowVelocityRuntimeTest' \
  --tests '*ShadowVelocityBrokerIT'
```

Topology tests exercise duplicates, equal timestamps, out-of-order arrivals,
window boundaries, late records and malformed/future input. A controlled completed
input is compared with the existing shared H1/H24 feature definitions, and emitted
payloads are checked against the published version-one schema. The broker test uses
two input partitions and a read-committed observer, then restarts the same
application with a fresh local directory to require changelog restoration. It also
asserts the exact four internal topics declared in GitOps. The
runtime tests cover disabled startup, configuration and failure isolation.

Disable the flag to stop this projection; normal fraud scoring keeps its existing
inputs. Retain the application ID and changelogs for a compatible restart. Do not
delete state, reset offsets or rename the application as a routine rollback: those
operations change which history contributes to a bucket. Promotion requires
measured parity/late-data rates, consumer lag, CPU/heap/native-memory/disk budgets
and a reviewed serving contract (ADR-0140 amendment, #13888).
