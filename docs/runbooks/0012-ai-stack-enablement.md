# Runbook 0012 — Enabling the AI stack layers (guardrails, Langfuse, semantic retrieval)

Status: Active
Owner: Platform
Related: ADR-0265 (the plan), ADR-0031 (agent governance), ADR-0174/0175 (gateway + egress),
ADR-0183 (pgvector retrieval), issue #5671 (the tail)

## Why this runbook exists

Three of the four AI-stack layers ship **off by default**, and each needs one secret that only an
operator can seed. Merging the manifests is therefore not the same as the feature being live — and
the difference is invisible from the outside, because every one of these layers is designed to
degrade quietly rather than crash:

| Layer | Off/unseeded behaviour | How you can tell from outside |
|---|---|---|
| Content safety | every classification reports `unavailable` | `openbank_guardrail_classifications{decision="unavailable"}` is 100 % of volume |
| Langfuse ingestion | LiteLLM logs a rejected callback per request | **no metric** — see the gap below |
| Semantic retrieval | help search is keyword-only | `openbank_copilot_retrieval_total{mode="keyword_only"}` |

**The Langfuse row is a real gap, not an oversight to be argued away.** Langfuse v2 exposes no
Prometheus endpoint and LiteLLM's callback metrics are an Enterprise feature, so a green Langfuse
pod with a rejected key is indistinguishable from an idle gateway. Until that is closed (#5671) the
only proof is the manual check in step 4 — which is now an exact lookup rather than a sample, because
the caller chooses the trace id, but is still a check somebody has to run rather than one that fires.

## 1. Seed the secrets (operator, break-glass)

```bash
./openbank-infra/scripts/seed-ai-stack-secrets.sh
```

Idempotent: it seeds only what is missing, and `--rotate` regenerates the rest. All values are
generated locally, none is a third-party credential, and none is ever echoed — the UI password is
read out of OpenBao only when you ask for it, by the command the script prints.

What it does that a hand-typed `bao kv patch` does not:

- uses `kv patch`, not `kv put` — `put` replaces the whole secret and would drop the other fields;
- generates `ENCRYPTION_KEY` as **64 hex chars** (`-hex 32`, *not* `-base64`); Langfuse refuses to
  start on any other length, and on a fresh install that reads as a crashloop with one validation
  line buried in the first seconds of log — the script also re-checks the **projected** length,
  since that is what the pod actually receives;
- refuses to continue if Langfuse is **already running** while the project keys are unseeded, which
  is the unrecoverable ordering case in step 2;
- checks that `litellm/KEY_COPILOT_SERVICE` exists — the guardrail and embedding routes reuse the
  copilot's existing virtual key on purpose (a second copy is a second place to rot), and without it
  both degrade **silently**;
- forces the ESO sync and verifies the projection, rather than reporting that a pod exists.

## 2. Order matters in exactly one place

`LANGFUSE_INIT_*` is consumed on **first boot only** — the operator creates the org, project, API
keys and user, and later edits do nothing because the rows already exist. So seed the secret
*before* the Langfuse pod first starts. If it started first, the project exists with keys nobody
holds: delete the `langfuse-db` cluster's data (it holds no source of truth yet) or create a second
project in the UI and update the secret. Same shape as the ClickHouse init ConfigMap.

## 3. pgvector — the extension is not created by the migration

The copilot's V3 migration runs as the database owner, which is **not** allowed to create `vector`
(it is not a trusted extension). The CloudNativePG `Database` resource
(`components/copilot/copilot-db-vector-extension.yaml`) does it, over the operator's superuser
connection. Confirm before flipping `COPILOT_SEMANTIC_RETRIEVAL_ENABLED`:

```
kubectl -n platform get database copilot-db-openbank-copilots -o jsonpath='{.status.applied}{"\n"}'
```

If that is not `true`, the copilot pod will fail its Flyway migration and crashloop — read
`.status.message` on the same object first.

## 4. Verify by effect, not by pod status

```
# Guardrails: a real verdict, not "unavailable"
kubectl -n platform exec deploy/copilot-service -c copilot-service -- \
  sh -c 'curl -s localhost:8085/q/metrics | grep guardrail_classifications'

# Semantic retrieval: mode="hybrid" appears after a help search
kubectl -n platform exec deploy/copilot-service -c copilot-service -- \
  sh -c 'curl -s localhost:8085/q/metrics | grep copilot_retrieval'

# Langfuse ingestion — still manual, but now ADDRESSED rather than sampled (#5671).
#
# `OpenAiCompatibleLlmGatewayClient` sends the caller's own W3C trace id as LiteLLM
# `metadata.trace_id`, and LiteLLM passes metadata through to its logging callback — so the
# Langfuse trace carries the SAME id as the calling service's OTel span. That turns the check
# below from "is there any trace at all" into "is the trace for the call I just made there",
# which is the difference between a sample and a probe.
#
# 1. Drive one real LLM call through a traced service and capture its trace id from the log
#    line Quarkus already stamps with it (`traceId=`), e.g. a copilot chat request:
kubectl -n platform logs deploy/copilot-service -c copilot-service --tail=200 \
  | grep -oE 'traceId=[0-9a-f]{32}' | tail -1

# 2. Ask Langfuse for THAT id (substitute it for $TID). 200 = ingestion works; 404 = it does not.
kubectl -n ai-platform exec deploy/langfuse -- \
  sh -c 'curl -s -o /dev/null -w "%{http_code}\n" \
    -u "$LANGFUSE_INIT_PROJECT_PUBLIC_KEY:$LANGFUSE_INIT_PROJECT_SECRET_KEY" \
    localhost:3000/api/public/traces/'"$TID"
```

A 404 for an id you know was sent means the callback is not landing — check the LiteLLM pod log for
a rejected key, and that `litellm-config-revision` was bumped so the pod actually re-read its config
(LiteLLM parses `--config` once at startup; editing the ConfigMap alone changes nothing).

**Two ways this probe can still answer "no" for the wrong reason, and both are checkable.** If the
calling service does not apply `quarkus-opentelemetry` there is no trace id to send, so LiteLLM mints
its own and the lookup 404s while ingestion is fine — fall back to `?limit=1` to separate the cases.
And an unsampled span yields OpenTelemetry's all-zero id, which the client refuses to send by design
(it would fuse every untraced call onto one shared trace); step 1 will simply find no `traceId=` line.

**What this still is not.** It is a probe a human runs, not a control that watches. Nothing alerts
when ingestion stops, because there is no series to alert on — that half of #5671 is open.

## 5. Rollback

Each layer is one env var back to `"false"`, and the fallback behaviour is the pre-ADR-0265
behaviour in every case — keyword-only search, no classifier, no traces. Nothing to migrate back.
The pgvector table and the Langfuse database can stay: both hold derived data
(`help_passage_embedding` is regenerable from the repo's markdown; traces are observability).
