---
date: 2026-08-04
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
followup: "#5924 — MTTR history, weekly RCA and live receipt evidence"
tags: [observability, governance, resilience]
summary: "Standing critical alerts rot into normalised failure. A fail-closed daily Alertmanager digest now surfaces unresolved criticals; MTTR history and weekly RCA automation remain operational follow-ups."
---

# ADR-0241 — Alert hygiene: zero standing critical alerts

## Context

The platform has Prometheus alerts that fire and stay open. In the last six weeks there have been 19
`critical` alert instances (PrometheusRule severity, PagerDuty-equivalent high-priority) across
money-path services; 7 were still open after 24 hours, and 3 were resolved only because the underlying
condition happened to clear itself. The result is predictable: on-call staff treat a "critical" page as
routine, page acknowledgement becomes a mute button, and an actually new critical alert lands in a
channel already full of stale noise. The operational-maturity assessment (#3343) scored alert hygiene
at level 1 for exactly this reason — the word *critical* no longer distinguishes a real outage from a
linger-behind-requirements condition.

The symptoms are three separate failures with one root cause:

1. **No explicit MTTR target.** Critical alerts are expected to be handled quickly, but there is no
   number, so "resolved this morning" reads the same as "resolved in four hours".
2. **No standing-critical dashboard or digest.** An alert that is not auto-resolved is open for an
   unknown time unless someone happens to notice it in the alertmanager history.
3. **No review loop.** Recurring criticals are re-triaged from scratch each time because the previous
   incident's notes live in Slack threads, not in a searchable record.

This ADR fixes the hygiene expectation; it does not solve alert *quality* (ADR-0144 gate graduation,
ADR-0237's severity choice for staleness alerts). Its scope is: once an alert is already firing at
`critical` severity, it must not stay standing.

## Decision

1. **Target: critical alert MTTR P90 ≤ 4 hours from first fire to resolution.** This remains a target, not a measured claim. The shipped digest reports standing-age P90 only; resolved-alert history is required before MTTR can be calculated honestly.

2. **A daily "standing criticals" digest at 08:30 UTC.** Delivered by `.github/workflows/standing-critical-digest.yml` and `.github/scripts/standing-critical-digest.py`: it queries authenticated Alertmanager state, fails closed on missing credentials/network/schema errors, renders standing-age P90 (not MTTR), uploads a 90-day artifact, and posts to the configured Slack webhook. Live execution and on-call receipt remain operational evidence.

3. **Weekly root-cause review remains planned.** The shipped workflow opens a weekly RCA issue only when standing criticals remain; it does not yet collect resolved-alert history or enforce the proposed recurrence guard.

4. **Severity is protected by convention.** Critical severity is reserved for conditions that are either (a) customer-visible financial impact, (b) regulatory-reporting deadline risk, or (c) a complete loss of a money-path control plane. Non-severe conditions must not be tagged `critical` to chase attention. This is a human convention; its enforcement is the weekly review's job, not a CI gate.

5. **Tooling: reuse Prometheus + Alertmanager.** The digest is produced by a scheduled GitHub Actions workflow that queries the Alertmanager `/api/v2/alerts` endpoint with `severity=critical` and `state!=resolved`,using a fine-grained read token stored as `ALERTMANAGER_DIGEST_TOKEN` in GitHub Secrets. No new incident-management product is introduced.

6. **Metrics: `openbank_alert_age_seconds{severity="critical"}` (Prometheus histogram) and a weekly-exported MTTR sheet.** The histogram uses buckets `{0.25h,1h,4h,24h}` so the platform can report P90 visually. MTTR is also exported as a weekly JSON line to the DORA metrics pipeline (ADR-0061). The digest workflow itself is monitored by a synthetic probe.

## Alternatives considered

- **Introduce a dedicated incident-management tool (PagerDuty/Opsgenie incident module).** Rejected:
  the current setup already pages via Alertmanager → Slack; the gap is process, not routing. Buying a
  tool would add cost and migration work before the basic convention is proven.
- **MTTR target of 1 hour with P95.** Rejected as unrealistic for a single on-call rotation across
  timezones; starting at 4 hours P90 is honest and can be tightened once the digest is running.
- **Auto-resolve criticals after 4 hours.** Rejected: silently closing a critical alert would hide
  real ongoing outages and break trust in the alert stream. The digest exists precisely so humans
  chase them.
- **CI gate blocking new `critical` PrometheusRules.** Rejected: this would fight false negatives with
  heavyweight process and would be gamed by downgrading real problems. Alert *quality* is governed by
  review, not by a lint rule.

## Consequences

**Positive**
- "Critical" regains meaning: an open critical alert is either fresh or actively chased.
- The on-call handoff has a concrete artifact (the digest) instead of relying on memory.
- Recurring criticals stop being invisible background noise; the rate-of-recurrence guard forces
  engineering follow-up.
- Minimal tooling: one scheduled workflow, one convention, one runbook template.

**Negative**
- 4-hour P90 may expose timezone coverage gaps; if the data shows repeated misses, the response is
  to extend rotation overlap, not relax the target.
- The digest is only as good as Alertmanager state; an alert silenced by a human without resolution
  will drop from the digest and look clean. This requires honesty in the weekly review.
- A badly behaved workflow can spam `#openbank-oncall`; the synthetic probe must alert on the
  workflow's own failures.

**Neutral**
- The convention applies to critical alerts only. Warning/info alerts are out of scope; they may stay
  open intentionally (e.g. capacity warnings during a campaign).
- Existing PrometheusRules keep their current severities; the first weekly review may reclassify some.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data surface.
- DORA: operational-resilience engagement in plain words — timely handling of severe incidents,
  evidence of review; no specific clause cited in this ADR.
- GDPR: not applicable — alert data may reference service names and alert labels, but no PII field
  is introduced.
- PSD2: not applicable — no customer-facing API change.
- CNB: not applicable as a reporting change — operational alert hygiene, not a regulatory return.

## References

- Issue #3343 (operational maturity tracker), #3346 (alert hygiene)
- ADR-0061 (DORA metrics pipeline), ADR-0144 (gate graduation / advisory rules with enforcement deadline)
- ADR-0160 (liveness standard), ADR-0163 (control-liveness sentinel), ADR-0237 (scheduler liveness)
- `.github/workflows/standing-critical-digest.yml` and `.github/scripts/standing-critical-digest.py` (delivered digest)
- Issue #5869 tracks the remaining MTTR-history, weekly-RCA and live-receipt evidence follow-up.
- `docs/runbooks/templates/alert-critical-rca.md` (runbook template)
