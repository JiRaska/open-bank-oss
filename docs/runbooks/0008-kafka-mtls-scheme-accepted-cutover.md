# Runbook 0008 — Kafka mTLS cutover for payment.scheme-accepted (ADR-0137)

Status: Draft (manifests landed; not yet applied to sandbox)
Owner: Payments + Platform + Security
Related: ADR-0137 (this migration), ADR-0108 (rail settlement trust boundary),
transaction-service threat model §2a, #2554 (the premature-ACL removal this
follows up), runbook 0005 (OpenBao/External Secrets).

## Why

`payment.scheme-accepted` drives money-path settlement in transaction-service.
ADR-0137 locks it down with a `tls:9093` listener + per-service mTLS + topic
ACLs, **without** flipping the cluster-global `allow.everyone.if.no.acl.found`
gate (that would break all ~33 cluster clients — out of scope). This is a
coordinated, money-path cluster change: apply it in stages and verify each step,
because a mis-ordered apply leaves payments pods unable to mount their keystores
and a wrong group/ACL re-creates the #2554 "consumer denied" settlement stall.

## Blast radius

- In scope: `sepa-payment`, `sepa-instant`, `domestic-payment`, `swift-service`,
  `transaction-service` (all ns `payments`) + the Strimzi `Kafka` CR (ns
  `messaging`) + one ESO `ClusterSecretStore` + RBAC in `messaging`.
- Untouched: every other Kafka client (they keep using `plain:9092`).

## Pre-flight checks (do BEFORE merging/applying)

1. **Confirm the ESO controller ServiceAccount.** `kafka-scheme-accepted-acl.yaml`
   binds `serviceaccounts/token` create to `external-secrets/external-secrets`.
   Verify the running controller's SA:
   ```
   kubectl -n external-secrets get deploy -o jsonpath='{..serviceAccountName}{"\n"}'
   ```
   If it differs, fix the `RoleBinding` subject before applying.
2. **Confirm the cluster-CA cert secret name** in `messaging`:
   ```
   kubectl -n messaging get secret openbank-cluster-cluster-ca-cert \
     -o jsonpath='{.data.ca\.p12}{"\n"}' | head -c 20
   ```
   (non-empty ⇒ exists). If your Strimzi names it differently, update the
   ExternalSecret + ESO Role resourceName.
3. **Confirm the ESO kubernetes provider is available** (CRD `ClusterSecretStore`
   supports `provider.kubernetes`): `kubectl get crd clustersecretstores.external-secrets.io`.

## Apply order (staged)

Stage 1 — **broker listener** (additive, safe; no client uses it yet):
```
# kafka Application syncs components/kafka → Strimzi rolls the broker to add tls:9093
argocd app sync kafka     # or: kubectl apply -f components/kafka/kafka.yaml
kubectl -n messaging get kafka openbank-cluster -o yaml | yq '.status.listeners[].name'   # expect: plain, tls
```

Stage 2 — **KafkaUsers + ESO reader RBAC** (ns `messaging`) and **ClusterSecretStore**:
```
# These ship in the payments + external-secrets components. Sync them; the
# Strimzi User Operator mints one Secret per KafkaUser in messaging.
kubectl -n messaging get kafkauser                # expect the 5 service-named users, Ready=True
kubectl -n messaging get secret transaction-service sepa-payment sepa-instant \
        domestic-payment swift-service            # 5 keystores exist
kubectl get clustersecretstore kafka-messaging-certs -o jsonpath='{.status.conditions}'  # Valid=True
```

Stage 3 — **project certs into `payments`** (ESO ExternalSecrets):
```
kubectl -n payments get externalsecret | grep kafka     # all SecretSynced=True
kubectl -n payments get secret transaction-service-kafka-keystore kafka-cluster-ca-truststore
# sanity: the projected p12 is non-empty and is valid PKCS#12
kubectl -n payments get secret transaction-service-kafka-keystore \
  -o jsonpath='{.data.user\.p12}' | base64 -d | keytool -list -storetype PKCS12 \
  -storepass "$(kubectl -n payments get secret transaction-service-kafka-keystore -o jsonpath='{.data.user\.password}' | base64 -d)" 2>/dev/null | head
```
> If ExternalSecrets are `SecretSyncErr` with an RBAC list/watch error, the ESO
> kubernetes provider on this version needs `list`+`watch` (not just `get`) —
> widen the `eso-kafka-cert-reader` Role accordingly and re-sync.
> If `user.p12` decodes but keytool rejects it, ESO mangled the binary — switch
> the ExternalSecret from `dataFrom.extract` to a binary-safe `data` mapping or
> bump ESO.

Stage 4 — **roll the services onto mTLS** (the payments Rollouts now point at
`:9093` with SSL env + mounts). transaction-service is a **canary** Rollout
(10%→30%→100% with the money-path AnalysisRun) — watch it:
```
kubectl argo rollouts -n payments get rollout transaction-service --watch
```
Roll the **consumer (transaction-service) first** — it is the only client that
*must* authenticate for the topic to work; if it can't, settlement stalls and
you want to catch that at 10% canary, not after all five cut over.

## Verification (must pass before declaring done)

1. **Authentication** — each service connects on 9093 as its KafkaUser:
   ```
   kubectl -n payments logs deploy/… | grep -iE "SSL|bootstrap|9093"   # no SSL handshake / auth errors
   ```
2. **Consumer reads** — no `TopicAuthorizationException` / `GroupAuthorization…`
   in transaction-service logs; consumer group present:
   ```
   kubectl -n messaging exec … -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
     --describe --group transaction-scheme-accepted-cg
   ```
3. **End-to-end settlement** — run a sandbox payment through each rail and
   confirm it reaches COMPLETED (debit + ledger journal booked), i.e. the
   ADR-0108 leg still works after cutover. This is the real money-path gate.
4. **Negative test (the actual threat)** — an anonymous client on `plain:9092`
   is DENIED write to the topic:
   ```
   kubectl -n messaging exec … -- bin/kafka-console-producer.sh \
     --bootstrap-server localhost:9092 --topic payment.scheme-accepted
   # expect: TopicAuthorizationException (ANONYMOUS not in ACL)
   ```

## Rollback

- **Fast, per-service:** revert that service's Rollout env to `:9092` +
  PLAINTEXT (drop the SSL env/mounts). It reverts to anonymous; because the
  topic is ACL'd it will then be *denied* — so for a true unblock you must also
  remove the topic ACLs (next bullet). Prefer rolling forward (fix certs/ACLs).
- **Full revert to pre-ADR-0137 (unblock settlement like #2554):** delete the 5
  `KafkaUser`s (removes the topic ACLs ⇒ topic reverts to allow-everyone) and
  revert the service manifests/app-config. The `tls:9093` listener is additive
  and can stay. This reproduces the post-#2554 state.
- ExternalSecrets use `deletionPolicy: Retain`, so reverting them does not delete
  a live keystore from under a running pod.

## Out of scope — cluster-wide gate flip

Flipping `allow.everyone.if.no.acl.found=false` is a separate program: repeat
the per-service recipe above for every Kafka client (money-path tier first, then
compliance/audit, then peripheral), verify each, and only then flip the global
flag. ~33 clients across ~23 namespaces — do not attempt as a single change.
Tracked in **#2665**.

## Known follow-up — consumer group / DLQ name not yet converged

The mTLS + ACLs are live and e2e-verified, but the running transaction-service
image lags main, so the consumer still uses group `openbank-transaction-service`
(allowed: no ACL on it while the gate is `true`) and SmallRye's default DLQ topic
instead of the baked `transaction-scheme-accepted-cg` / `payment.scheme-accepted.dlq`.
This is cosmetic today (the topic carries no real traffic) and converges on the
next image rebuild — currently blocked by the Pact `can-i-deploy` gate, **#2664**.
Do **not** try to force it via `MP_MESSAGING_INCOMING_*` env vars: SmallRye cannot
take hyphenated mp.messaging channel config from env and the pod fails to start
(tried and reverted, #2649 → #2659).

## Stage 6 — remove the `plain:9092` listener (issue #3393)

The last stage of this runbook, and the one that makes the rest of it enforceable
rather than merely configured. **Everything above this heading was written while
`allow.everyone.if.no.acl.found` was still `true` and the plaintext listener was
in place; read it as history, not as instructions.** Specifically:

- "Untouched: every other Kafka client (they keep using `plain:9092`)" and the
  "Out of scope — cluster-wide gate flip" section were overtaken by **#2794**,
  which flipped the gate to `false` for the whole cluster.
- The verification commands that exec into the broker with
  `--bootstrap-server localhost:9092` no longer have a listener to reach, and
  the Stage-4 negative test ("an anonymous client on `plain:9092` is DENIED")
  cannot be run at all any more — its outcome is now structural. A broker-local
  check needs `:9093` plus a client `--command-config` carrying a KafkaUser
  keystore.

### What had to be true first

The listener was kept because removing it was assumed to break clients. It did
not grant anything — a client on it authenticates as `User:ANONYMOUS`, which
holds no ACL and is denied on every resource once the gate is `false` — so
"still on 9092" and "cannot use Kafka" were the same statement. Three workloads
still named it on 2026-08-03, each resolved before the removal:

| workload | state before | resolution |
| --- | --- | --- |
| `security-scanner-service` | no `KafkaUser` at all; both topic log segments 0 bytes since creation — it had never published | `KafkaUser security-scanner` + ACLs, moved to `:9093` |
| `kafka-ui` | the only workload actually speaking on `:9092`; every request denied (`Principal = User:ANONYMOUS is Denied operation = DESCRIBE … based on rule DefaultDeny`, once per 30s poll), rendered as an empty cluster | `KafkaUser kafka-ui` + browse-scoped ACLs, moved to `:9093` |
| `settlement-service` | `KAFKA_BOOTSTRAP_SERVERS` env with no `mp.messaging` config and no Kafka client in its source — dead value | env deleted |

### Apply

```
argocd app sync kafka          # Strimzi rolls the broker to drop the listener
kubectl -n messaging get kafka openbank-cluster -o yaml | yq '.status.listeners[].name'   # expect: tls  (only)
```

### Verify

1. **Every client is still connected**, i.e. the roll changed nothing for them:
   ```
   kubectl get pods -A -l app.kubernetes.io/part-of=openbank --field-selector=status.phase=Running
   kubectl logs -n <ns> deploy/<svc> --since=10m | grep -iE "SSL handshake|disconnected|TimeoutException"
   ```
2. **kafka-ui shows a populated cluster** — topic list, per-topic partitions and
   consumer groups with lag. That is the observable this whole issue turned on:
   before, the same screen showed an empty cluster and no error.
3. **The denial log is gone.** The broker authorizer should no longer print
   `User:ANONYMOUS is Denied` at all — previously once per 30s, forever:
   ```
   kubectl -n messaging logs openbank-cluster-dual-0 --since=10m | grep ANONYMOUS   # expect: nothing
   ```
4. **The derived NetworkPolicy names 9093, not 9092.** `gen-network-policies.py`
   used to hardcode the port; it now derives it from the clients' bootstrap URLs,
   so a stale port here is a real (if latent) hole rather than a cosmetic one:
   ```
   kubectl -n messaging get netpol kafka-broker-ingress-allow-list -o jsonpath='{.spec.ingress[*].ports[*].port}{"\n"}'
   ```

### Rollback

Re-add the four listener lines to `components/kafka/kafka.yaml` and sync. Note
what that does and does not buy: it restores *connectivity* on 9092, never
*authorization* — an ANONYMOUS client is still denied on every resource by the
gate. So it is only worth doing to quiet a connection-error loop while a real
client-side problem is fixed, and it is not a path back to a working plaintext
client. There is no such path; that is the point of the stage.
