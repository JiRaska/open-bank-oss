# Runbook 0013 — RUM cardinality budgets (and reading an empty Mobile RUM board)

Status: Active
Owner: Platform / Observability
Related: ADR-0088 (mobile RUM, D4 + O2), ADR-0147 (cross-repo delivery status),
`docs/threat-models/rum-ingest-gateway.md`, issue #5735

Both alerts in `openbank-infra/gitops/components/observability/prometheusrule-rum-cardinality.yaml`
point their `runbook_url` here. Until #5735 that URL pointed at a repo path that has never existed,
in an org that is not this one.

## What these alerts actually watch

Tempo's metrics-generator turns every ingested span into `traces_spanmetrics_*`, labelled with
exactly:

```
service, span_name, span_kind, status_code   (+ __metrics_gen_instance)
```

`openbank-infra/gitops/apps/tempo.yaml` configures **no** `dimensions:` customisation, so that list
is the whole vocabulary. There is no `route` label and no `screen_name` label, in this cluster,
for any service.

The mobile app names its screen spans `screen.<NAME>` (observed: `screen.HOME`), so **the screen
name is the span name**. That is why `RumScreenCardinalityHigh` counts `by (span_name)`.

| Alert | Expression subject | Budget |
|---|---|---|
| `RumSpanMetricsCardinalityHigh` | total RUM series for `service=~"openbank-app.*"` | 100 |
| `RumScreenCardinalityHigh` | distinct `span_name` values for the same selector | 50 |

## If one fires

1. Find the offending values:
   ```bash
   kubectl -n observability port-forward svc/kube-prometheus-stack-prometheus 19090:9090 &
   curl -sG --data-urlencode \
     'query=topk(30, count by (span_name) (traces_spanmetrics_calls_total{service=~"openbank-app.*"}))' \
     http://127.0.0.1:19090/api/v1/query | python3 -m json.tool
   ```
2. The usual cause is a dynamic id interpolated into a screen name
   (`screen.ACCOUNT_1f0c…` rather than `screen.ACCOUNT_DETAIL`). The fix belongs in the app
   (`JiRaska/openbank-app`), not here.
3. The stop-gap that *is* in this repo: add a `transform` processor entry in
   `openbank-infra/gitops/apps/rum-gateway.yaml` to normalise the span name before it reaches
   Tempo. Note the gateway already runs `probabilistic_sampler` at 25%, so cardinality is being
   measured on a quarter of the real traffic — a firing alert means roughly 4x that many distinct
   values were actually produced.

## If the Mobile RUM dashboard is EMPTY (the common case, and not these alerts' job)

**Do not read an empty board as an incident.** These are *budget* alerts; they are silent when
there is no data, by design. Absence is signalled by the weekly `rum-attribute-audit` CronJob,
which exits 1 with `rum-mobile-signal-absent`.

Measured 2026-08-20 against the sandbox, this is the state to expect:

- `openbank-app` **does** reach Tempo, and its spans **do** join the backend trace — a single trace
  contained `screen.HOME` (from the app) and `POST /api/v1/copilot/chat/stream` (from
  `openbank-copilot-service`, `user_agent.original: ktor-client`). Tap → backend context
  propagation works.
- It arrives in **bursts of one or two spans**, hours apart, because RUM is off by default,
  OIDC/consent-gated, and only debug builds with a logged-in session emit at all. One trace in six
  hours is the normal reading, not a fault.
- The measured 2026-08-20 span carried only `party_id`; its resource carried `service.name` plus
  `redaction.redacted.count=3` — the gateway's strict allow-list dropped the app's three
  `telemetry.sdk.*` attributes. That is the redaction processor working as designed.
- Current Android and iOS source sends bounded `screen.name`, `app.version`, `os.*` and
  `device.model` attributes (mobile PR #555). Until a newly built, consented client emits a sampled
  trace, zero live values still means **not observed**, not that the source change is absent.

### Why `openbank-admin-ui` is absent, deliberately

ADR-0088 D4 rejected browser/JS RUM as the "wrong surface": an internal operator console with a
handful of users. The gateway is built to match that decision and would reject admin-ui even if
the SDK were added — it emits no CORS headers at all (so a cross-origin browser export is blocked
before auth), and its OIDC authenticator accepts only customer-realm tokens with the RUM audience.
Admin-ui's own CSP `connect-src` (`openbank-admin-ui/src/proxy.ts`) does not list the gateway
either. Wiring admin-ui RUM is therefore an ADR-level reversal, not a config change.

## Verifying a change to these rules

Any edit must keep a positive control green. The expression *shape* over a service that certainly
emits has to return a value:

```bash
curl -sG --data-urlencode \
  'query=count(count by (span_name) (traces_spanmetrics_calls_total{service="openbank-ledger-service"}))' \
  http://127.0.0.1:19090/api/v1/query
```

This returned `6` on 2026-08-20. If your edit makes that return nothing, the expression is broken —
not the data. Checking only the RUM selector cannot tell those two apart, which is precisely how
the pre-#5735 rules survived for two months while matching zero series.
