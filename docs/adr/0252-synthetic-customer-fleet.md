---
date: 2026-08-09
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [testing, observability, notifications, compliance]
summary: "Permanent bank-owned synthetic customers exercise real journeys in production, tainted end to end; their outcome is the availability metric, the alert source and the regulatory evidence."
followup: "#4348 — phases 1-4 (personas + taint, journey catalog, canary devices, evidence store and AI layer) are unbuilt; this ADR ships phase 0 only"
---

# ADR-0252 — Synthetic customer fleet and journey-based production assurance

## Context

Two customer-visible failures were found **by customers**, in production:

1. Push notifications reached exactly one device — the author's phone.
2. A domestic payment between two customers failed, and nothing alerted.

Both had been broken for an extended period. Neither was caused by a coding slip that a
test would plausibly have caught; both survived because nothing in the platform is
positioned to notice them.

What exists today, read off the tree:

- [`probes-synthetic.yaml`](../../openbank-infra/gitops/components/observability/probes-synthetic.yaml)
  defines four blackbox targets (public pages, edge reachability, API, OIDC discovery).
  Single-request liveness of the public ingress hosts, as its own header says: "is the
  front door open?".
- [`prometheus-rules-synthetic.yaml`](../../openbank-infra/gitops/components/observability/prometheus-rules-synthetic.yaml)
  alerts on `probe_success`, `probe_duration_seconds` and TLS expiry — all derived from
  those four probes.
- `k6-synthetic.yaml` was a scripted multi-step journey that ran `iterations: 1` **once on
  sync**. Its header stated plainly that turning it into a periodic monitor was the
  documented follow-up. Phase 2 did that and deleted the file; the periodic replacement is
  [`cronjob-journey-public-edge.yaml`](../../openbank-infra/gitops/components/observability/cronjob-journey-public-edge.yaml).

So the platform continuously verifies that its hosts answer HTTP, and never verifies that a
customer can be paid or notified. A payment between two customers is not covered by any
periodic check, which is the whole explanation for failure (2).

Failure (1) has a second cause that no amount of scheduling fixes. In
[`NotificationConsumer`](../../openbank-notification-service/src/main/kotlin/com/openbank/notification/application/NotificationConsumer.kt)
the PUSH fan-out computes `delivered = results.count { it.second.success }` and records the
notification as `SENT` when that count is non-zero. For APNs, `success` means Apple returned
HTTP 200 — the request was *accepted for delivery*. APNs issues no delivery receipt, so
**whether a device ever received the push is not observable from the server at all**. The
platform was not mistaken about delivery; it never had the fact.

Three further properties made the state unreadable:

- The PUSH path emits **no metric**. Across `openbank-notification-service/src/main` exactly
  one class touches `meterRegistry` (`ContactGateProducer`). There was nothing to alert on.
- [`ApnsPushSender`](../../openbank-notification-service/src/main/kotlin/com/openbank/notification/infrastructure/push/ApnsPushSender.kt)
  is `enabled=false` by default and then returns `PushResult.skipped(...)`, a *successful*
  result. A disabled adapter and a working one produce the same outcome, and the notification
  is stored as `SENT`.
- Delivery depends on a matrix — APNs production versus sandbox gateway, `apns-topic` bundle
  id, per-OS behaviour — in which one correctly-registered device proves nothing about the
  rest. That is precisely why it worked on one phone.

The regulatory frame makes this more than an availability concern. Where push carries the
SCA challenge or the amount-and-payee confirmation required by the dynamic-linking rule of
Commission Delegated Regulation (EU) 2018/389 Art. 5, a broken push channel is a broken
strong-customer-authentication channel. Art. 32(4) of the same regulation obliges the ASPSP
to publish quarterly statistics on the availability and performance of its interfaces, and
those statistics need a measurement source. Regulation (EU) 2022/2554 (DORA) Art. 24-27
requires a digital operational resilience testing programme, and its Art. 17-19 incident
management and reporting duties require the duration of an incident and the number of
affected clients — facts a platform that learns of outages from customers cannot state.

## Decision

We will run a **permanent fleet of bank-owned synthetic customers in production**, whose
lived experience is the availability signal, the alert source and the regulatory evidence.
Not a script pointed at an API: real parties with accounts, cards, consents and devices,
continuously doing what customers do.

**1. Personas.** Canary parties exist permanently in production, owned by the bank, carrying
a `SYNTHETIC` classification. They hold no personal data, so no data subject exists — the
GDPR Art. 5(1)(c) minimisation question is answered at the root rather than by an
after-the-fact exclusion of real customers from testing.

**2. Taint propagation.** One flag travels the whole path: a Kafka header
`x-openbank-synthetic`, OpenTelemetry baggage `openbank.synthetic`, a ledger dimension and
dedicated general-ledger accounts that net to zero. The taint drives three things at once,
and the second is the one that is easy to get backwards:

- it **excludes** synthetic activity from FINREP / COREP / AnaCredit and statistical
  aggregates, and from AML baseline scoring;
- it **must not** exclude synthetic activity from any control path — sanctions screening,
  Verification of Payee, SCA, limits. A canary that bypasses the controls proves nothing
  about them, and removing it from screening would manufacture a screening blind spot;
- it makes sending anything to a real person impossible on a synthetic journey.

**3. Journeys as executable contracts.** One artifact per journey, invoked from three places
— PR end-to-end run, post-deploy gate, and periodic production synthetic — so the test suite
and the production monitor cannot drift apart. The catalog lives in
`openbank-libs/governance/journeys.yaml`. Its **coverage is derived, never hand-kept**: every
customer-visible capability must map to a journey, and the gate fails when one does not. A
gate whose scope is a hand-maintained list reads as passing when the list is short, which is
a defect class this repository has already paid for more than once.

**4. Canary devices.** Because push delivery cannot be observed server-side, we close the
loop at the device: a silent push (`apns-push-type: background`) carries a probe id, the app
on a physical canary device acknowledges it back, and the metric is the real end-to-end
APNs-to-device latency. The fleet spans the delivery matrix (`prod`/`sandbox` gateway, bundle
id, OS major). Every canary keeps a **push-independent heartbeat**, so "the phone is dead" is
distinguishable from "push is dead" — without that, the monitor joins the failure mode it was
built to detect.

**5. Every journey ships a fixture that must turn it red.** A journey that has only ever been
green is unfalsified, and the specific way these things fail is by never reaching their
subject. The `journey-falsifiability` gate asserts the red.

**6. Absence is a first-class alert.** The failure here was silence, not error. Each journey
alerts on `time() - last_success > n * interval`, not only on failure counts, and the boot
state is handled explicitly so that "never ran" is not read as zero.

**7. Evidence outlives the metric store.** Journey outcomes land in an append-only evidence
store; Prometheus retention here is 12 hours, which cannot carry a regulatory claim. The
PSD2 interface-availability statistics are generated from that store rather than hand-kept.

**8. AI where it beats a rule, and only there.** Journey synthesis proposes new journeys as
pull requests and never writes to production; absence detection models the seasonal baseline
of the journey streams because "nothing happened" is the hard signal; and an LLM judge scores
rendered notification content for language and mandatory-disclosure correctness, with amounts
and identifiers asserted deterministically. The judge is measured against a labelled golden
set and publishes its false-negative rate — an unmeasured judge is a gate that has never
failed, which is the state this ADR exists to end.

This ADR ships **phase 0** only: push-delivery observability in
`openbank-notification-service` — per-outcome counters, `skipped` separated from `sent`, and
a skipped push no longer recorded as `SENT`. Phases 1-4 are tracked in #4348.

## Alternatives considered

- **Keep extending blackbox probes.** Cheap, already wired, and structurally incapable of
  covering this: a single unauthenticated request cannot express "Oldrich pays Anna and Anna
  is notified". It stays, as the liveness layer beneath the journeys.
- **Rely on real-customer telemetry (RUM / funnel drop-off alerts).** Detects real breakage
  with real coverage, but only *after* customers hit it, and it is unusable on a low-traffic
  path where a broken journey looks like an idle one. It is the complement, not the answer.
- **Synthetic journeys in a staging environment only.** No production credentials, no
  production data, no risk. Rejected: every one of the failures above is a production
  configuration fact — an APNs gateway and bundle id, a deployed service URL. Staging is
  structurally unable to observe them, which is the same reason a mocked port could not.
- **Trust APNs HTTP 200 and alert on its error rate.** Requires no device fleet. Rejected on
  measurement grounds: 200 is Apple's acceptance, and the observed outage produced no errors
  at all.
- **Buy a commercial synthetic-monitoring product.** Covers the HTTP journey layer quickly.
  Rejected as the primary vehicle because the value here is in the taint propagation through
  the ledger and the regulatory aggregates, and in a device fleet running our own app —
  neither is something an external prober can do. A vendor may still drive the outer HTTP leg.

## Consequences

**Positive**

- Customer-visible breakage is detected by us, on a schedule, instead of by customers.
- Push delivery becomes measurable for the first time: the divergence between accepted and
  acknowledged is the metric that did not exist.
- The interface-availability statistics and the incident duration and impact figures become
  by-products of running the platform, rather than documents assembled by hand.
- One journey artifact serves PR testing, deploy gating and production monitoring, removing
  a class of drift between what is tested and what is watched.

**Negative**

- Synthetic activity is real activity: it touches the ledger, the screening path and the
  event streams. Every aggregate that must not see it is a place the taint has to reach, and
  a missed one corrupts a regulatory report. This is the main risk of the decision.
- A physical device fleet is an operational asset — devices, SIMs, OS upgrades, app builds,
  certificate rotation — and it is itself a thing that can fail silently.
- Production canary credentials are production credentials, with the blast radius that
  implies.

**Neutral**

- The existing blackbox probes and their alert rules are unaffected and remain the layer
  below.
- Journey definitions become governed artifacts under `openbank-libs/governance/`, alongside
  the existing rules.

## Compliance impact

- PCI DSS: not applicable to this decision. A later canary journey that exercises a card
  transaction touches cardholder data and needs its own review before it is built.
- DORA: Regulation (EU) 2022/2554 Art. 24-27 — the journey catalog and its schedule are the
  periodic component of the digital operational resilience testing programme. Art. 17-19 —
  journey timestamps give the incident duration, and the synthetic-to-real traffic ratio gives
  an evidenced estimate of affected clients, both of which the reporting duties require.
- GDPR: Art. 5(1)(c) — canary personas hold no personal data, so synthetic testing processes
  none. The exclusion of synthetic records from analytics baselines is a data-quality measure,
  not a lawful-basis one.
- PSD2: Commission Delegated Regulation (EU) 2018/389 Art. 32(4) — the quarterly interface
  availability and performance statistics are generated from journey evidence. Art. 5 — where
  push carries the amount-and-payee confirmation, its outage is an SCA dynamic-linking outage
  and is graded accordingly.
- CNB: incident reporting under Act No. 370/2017 Sb. (the Czech transposition of PSD2 Art. 96)
  draws on the same journey evidence for the start, end and scope of an incident.

## References

- Issue #4348 — phases 1-4 tracking
- [`probes-synthetic.yaml`](../../openbank-infra/gitops/components/observability/probes-synthetic.yaml),
  [`prometheus-rules-synthetic.yaml`](../../openbank-infra/gitops/components/observability/prometheus-rules-synthetic.yaml)
- [`journeys.yaml`](../../openbank-libs/governance/journeys.yaml) — the phase 2 journey catalog
- [`ApnsPushSender`](../../openbank-notification-service/src/main/kotlin/com/openbank/notification/infrastructure/push/ApnsPushSender.kt),
  [`NotificationConsumer`](../../openbank-notification-service/src/main/kotlin/com/openbank/notification/application/NotificationConsumer.kt)
- Regulation (EU) 2022/2554 (DORA); Commission Delegated Regulation (EU) 2018/389;
  Regulation (EU) 2016/679 (GDPR); Act No. 370/2017 Sb.
