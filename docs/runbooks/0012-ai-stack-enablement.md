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
only proof is the manual check in step 4.

## 1. Seed the secrets (operator, break-glass)

All values are generated locally; none is a third-party credential.

```
bao kv patch openbank/langfuse \
  NEXTAUTH_SECRET=$(openssl rand -base64 32) \
  SALT=$(openssl rand -base64 32) \
  ENCRYPTION_KEY=$(openssl rand -hex 32) \
  LANGFUSE_PUBLIC_KEY=pk-lf-$(uuidgen | tr 'A-Z' 'a-z') \
  LANGFUSE_SECRET_KEY=sk-lf-$(uuidgen | tr 'A-Z' 'a-z') \
  INIT_USER_EMAIL=<operator email> \
  INIT_USER_PASSWORD=$(openssl rand -base64 24)
```

`ENCRYPTION_KEY` must be exactly 64 hex chars — `-hex 32`, **not** `-base64`. Langfuse refuses to
start otherwise, and on a fresh install that reads as a crashloop with one validation line buried in
the first seconds of log.

The guardrail and embedding routes reuse the copilot's **existing** LiteLLM virtual key
(`litellm/KEY_COPILOT_SERVICE`); nothing new to seed for those.

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

# Langfuse ingestion — the manual check, because no metric exists yet (#5671).
# Drive one LLM call, then ask Langfuse whether it stored a trace:
kubectl -n ai-platform exec deploy/langfuse -- \
  sh -c 'curl -s -u "$LANGFUSE_INIT_PROJECT_PUBLIC_KEY:$LANGFUSE_INIT_PROJECT_SECRET_KEY" \
    localhost:3000/api/public/traces?limit=1'
```

A `data: []` from that last command after a known LLM call means the callback is not landing — check
the LiteLLM pod log for a rejected key, and that `litellm-config-revision` was bumped so the pod
actually re-read its config (LiteLLM parses `--config` once at startup; editing the ConfigMap alone
changes nothing).

## 5. Rollback

Each layer is one env var back to `"false"`, and the fallback behaviour is the pre-ADR-0265
behaviour in every case — keyword-only search, no classifier, no traces. Nothing to migrate back.
The pgvector table and the Langfuse database can stay: both hold derived data
(`help_passage_embedding` is regenerable from the repo's markdown; traces are observability).
