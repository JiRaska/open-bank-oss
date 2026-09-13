---
date: 2026-09-13
decision-status: accepted
delivery-status: partial
followup: "#9945 — correlate incident windows with observed business workflows and case-level evidence"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [open-bank-oss]
tags: [resilience, observability, admin-ui, privacy-gdpr]
summary: "Incident response uses a privacy-minimized impact lens on the shared context graph to map affected services, workflows and bounded business cases from observed telemetry and event evidence."
---

# ADR-0309 — Incident business impact graph

## Context

During an incident, responders can see technical health but struggle to identify which
business workflows and cases were affected. Service dependency diagrams describe what
could be affected; traces, events and workflow evidence describe what was observed.
Joining them carelessly exposes customer data to infrastructure operators and can turn
missing telemetry into a false all-clear.

This lens is **P1**, alongside payment complaint tracing, after ADR-0308's P0 controls.
Its default aggregate mode has low personal-data exposure and proves shared-stack scale,
lineage and freshness before cross-customer lenses. Case-level drill-down remains gated.

## Decision

We will add `INCIDENT_IMPACT_SUMMARY` and separately authorized
`INCIDENT_CASE_DRILLDOWN` lenses to ADR-0303's shared stack. They reuse the common graph,
policy/audit, ingestion and UI. Technical topology remains sourced from governed service
metadata; observed impact comes from bounded telemetry/event/workflow evidence.

The ontology distinguishes declared dependency from observed execution:

- incident —[OVERLAPS]→ time window and —[AFFECTED]→ service/workflow as a derived claim;
- service —[DECLARES_DEPENDENCY_ON]→ service from governed topology;
- trace/workflow/event —[OBSERVED_IN]→ service and —[BELONGS_TO]→ business flow/case;
- case —[HAS_IMPACT {type, confidence}]→ incident, backed by evidence references.

Declared paths are labelled possible blast radius. Observed failures/latency/data-lag
are labelled measured impact. Missing telemetry is unknown, never healthy. Inference
records algorithm/rule version and supporting evidence. The lens must not state that a
customer was affected solely because their product depends on a service.

The summary permission returns service, business-flow, region/environment, aggregate
counts and confidence/coverage without party IDs or case identifiers. Drill-down needs
an active incident, explicit on-call/business-continuity assignment, purpose, allowed
data domain and a new OPA decision per expansion. Infrastructure admin is not customer-
data permission. Exports are separately approved and minimized. Read access is audited.

Ingestion uses sampled/aggregated telemetry references rather than copying arbitrary log
bodies. Normal logs, trace attributes and vector indexes must not become an unreviewed PII
lake. Stable business correlation IDs may map to context nodes through an allow-listed
schema; raw free-text logs are excluded from semantic search unless explicitly classified,
redacted, retained and approved.

Queries require incident start/end and environment, default to aggregate mode, use at
most two hops/request and ADR-0303 work limits. Large impact reconstruction is an
asynchronous snapshot job with quotas below interactive investigations and production
telemetry pipelines. The graph store consumes exported evidence asynchronously; its
failure must not interfere with observability ingestion or incident declaration.

Acceptance tests declared-vs-observed labeling, missing telemetry, partial sampling,
clock skew, duplicate traces/events, cross-environment/incident leakage, hidden case
counts, revoked assignment, unavailable policy/audit and high-cardinality incidents.
Benchmark concurrent P1 response and payment/control load. Pilot in aggregate summary
mode on synthetic incident replays before enabling case drill-down.

## Delivery status

### Delivered P1 slice

The context projector consumes the existing durable DORA incident stream from security-scanner,
validates its source/schema/version and maintains the current incident-to-service impact edges.
Updates replace stale service impact only when their source version is newer. The protected admin UI
returns aggregate counts by affected type and never returns service or customer identifiers. Workflow
and business-case observations need explicit source correlation contracts and remain follow-up work,
so this ADR stays `partial`.

## Alternatives considered

- **Use the service map as observed impact:** rejected; dependency is possibility, not evidence.
- **Give SRE/admin roles customer drill-down:** rejected; operational authority is not data purpose.
- **Index all logs into vectors:** rejected; unbounded free text has uncontrolled PII,
  retention, prompt-injection and cost risk.
- **Build a separate incident graph stack:** rejected; shared ontology/policy/recovery is preferable.

## Consequences

**Positive**
- Responders can separate possible blast radius from measured business impact.
- Aggregate-first delivery proves scale while minimizing customer disclosure.

**Negative**
- Accurate impact depends on correlation coverage and explicit unknown states.
- Case drill-down needs cross-team assignment and privacy controls.

**Neutral**
- Incident declaration, severity and source observability systems remain authoritative.

## Compliance impact

- PCI DSS: telemetry and graph fields exclude cardholder/authentication data.
- DORA: improves evidence-backed impact assessment while adding a recoverable read model.
- GDPR: aggregate-first access, purpose-bound drill-down, minimization and retention apply.
- PSD2: not applicable — no payment/account capability is granted.
- CNB: auditable impact evidence supports supervisory response; completeness follows
  measured coverage and is never assumed.

## References

- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0308](0308-effective-time-authorization-evidence-graph.md)
- [ADR-0146](0146-incident-response-and-security-operations-framework.md)
- [ADR-0160](0160-end-to-end-integration-liveness-and-drift-detection-standard.md)
- [Implementation roadmap #9945](https://github.com/JiRaska/open-bank-oss/issues/9945)
