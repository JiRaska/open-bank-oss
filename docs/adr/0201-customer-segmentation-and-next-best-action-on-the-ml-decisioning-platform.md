---
date: 2026-07-25
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ml, analytics, compliance, product-catalog]
summary: "Segments and next-best-action reuse the ADR-0139/0140 platform as its second consumer, which forces the unbuilt offline half; NBA may rank communications but is barred from credit outcomes, keeping it out of AI Act Annex III.5(b)."
---

# ADR-0201 — Customer segmentation and next-best-action on the ML decisioning platform

## Context

ADR-0200 makes a campaign executable but says nothing about how a cohort is chosen. The two obvious
answers are both bad: hand-written SQL per campaign (unversioned, untestable, and silently divergent
between the count a marketer previews and the set that is sent to), or a second ML stack alongside
the one ADR-0139/0140 already committed to.

What actually exists on that platform must be stated precisely, because the reuse argument is only
honest if the gaps are named:

- **The online store is real but narrow.** `com.openbank.libs.domain.feature` defines
  `FeatureDefinition` (`compute(asOf, events)`, `ttl`, `isStale`) and a single port,
  `OnlineFeatureStore`, with exactly two methods (`read`, `incrementWindowed`). The adapter is
  `RedisOnlineFeatureStore` in `openbank-libs-runtime`. There is no `OfflineFeatureStore` port —
  and ADR-0140's own status note records that a duplicate unused `FeatureStore`/`RedisFeatureStore`
  pair was once built and reverted, so those names must not be described as existing.
- **The catalogue is two features.** `PHASE1_FEATURES` holds `VELOCITY_TXN_COUNT_H1` and
  `VELOCITY_TXN_COUNT_H24`. Nothing else. The only consumer anywhere is fraud-service, wired
  per-service by `FeatureStoreConfig`'s `@Produces`.
- **The offline half is unbuilt.** There is no snapshotter, no feature-log topic and no as-of join.
  The only offline materialisation in the tree is a test — `FeatureParityIT` — reconstructing values
  through `FeatureDefinition.compute`. ADR-0140's promise that point-in-time correctness "holds by
  construction" currently rests on one pure function and one test, which is the right design and an
  incomplete delivery.
- **ONNX serving is real, and it is not shared.** `OnnxFraudModel` uses real ONNX Runtime with a
  bundled signed-ish artifact, but `com.microsoft.onnxruntime` is declared in
  `openbank-fraud-service/build.gradle.kts` only, and the adapter sits in that service's
  `infrastructure/ml`. There is no shared serving component to reuse — reuse means promoting it.
- **The registry hook is digest-pinning, not signing.** `verifyCard` checks a content SHA-256 and
  that `scope ∈ {"fraud-shadow"}`. There is no detached signature yet (the ADR-0141 follow-up), and
  the scope allow-list means a non-fraud model is rejected until a scope value is added.

Regulatory force, and it is the sharp one: **the AI Act boundary runs straight through
"next-best-action".** ADR-0142 already classifies credit decisioning as planned high-risk under
Annex III.5(b) (creditworthiness evaluation of natural persons). A model that ranks which product to
tell a customer about is limited-risk; the *same* model, if its output gates who is offered credit or
on what terms, is inside III.5(b) and inherits every obligation ADR-0142 accepted. The distinction is
not the model, it is what consumes the output. If that boundary is not written down before the model
exists, it will be crossed by a later feature that looks like a small extension.

Why now: ADR-0200 needs a cohort definition, and the offline half of ADR-0140 has had one consumer
and therefore no forcing function. A second consumer is what turns a phase-2 promise into a
requirement.

## Decision

**D1 — A segment is a versioned artifact, not a query.** A segment definition is code in
campaign-service, reviewed and released like any other code, evaluated against the ADR-0199 view and
the feature store. Its materialisation records the definition version and an `asOf` timestamp, so the
cohort a marketer previewed and the cohort that was sent to are the same set or provably a different
version. No free-form SQL from a UI.

**D2 — Deterministic segments first; the model is additive.** A rule-based segment (product holdings,
tenure, channel activity, lifecycle stage) is the floor and is sufficient for launch. This mirrors
ADR-0139's permanent rule floor for fraud, and it means ADR-0200 can ship with no model at all — the
sequencing that keeps the LLM and ML cost optional rather than structural.

**D3 — Extend the feature catalogue rather than starting a second one.** New `FeatureDefinition`
instances for campaign-relevant behaviour (channel engagement recency, product-page views, statement
opens) join the existing catalogue in `openbank-libs-domain`, computed by the same pure `compute`
function that serves both stores. This is ADR-0140's central claim — one definition, therefore no
training/serving skew — and it only stays true if the second consumer uses the same mechanism.
Consequence to accept deliberately: `RedisOnlineFeatureStore` is a GET-then-SET without the Lua CAS
that ADR-0140 defers to phase 2, and a second writer raises the cost of that race.

**D4 — This ADR is the forcing function for the offline half, and says so.** Segment backtesting and
model training both need an as-of join, so ADR-0140's phase-2 offline snapshotter becomes a
dependency of this work rather than a standing intention. If it is not built, D2's deterministic
segments still work and D5 does not — that is the honest partition, and it must not be papered over
by training on the online store, which would reintroduce exactly the skew ADR-0140 exists to prevent.

**D5 — Next-best-action ranks communications, and is barred from credit outcomes.** The model outputs
a ranked list of *which existing catalogue message is most relevant*, consumed only by
campaign-service to order or suppress steps. It is explicitly **not** permitted to determine
creditworthiness, pricing, limits, or eligibility for a credit product, and it is not permitted to
suppress a product a customer has actively asked about. Any such use is a different system requiring
its own ADR under ADR-0142's high-risk controls (adverse-action reasons, deterministic affordability
supremacy, four-eyes on declines). This boundary is the load-bearing sentence of this ADR, and a
sentence alone is not an enforcement mechanism — the concrete gate is a type boundary: the NBA
port's return type (a ranked list of catalogue message ids) is structurally incapable of expressing a
credit decision, and `MlModelPort`/its NBA equivalent must never share an interface, a DTO, or a
`scope` value with anything ADR-0142's credit engine calls. This is tracked as its own follow-up
issue at implementation time rather than left as prose here, so the boundary has an owner before code
exists to cross it.

**D6 — Serving reuses ADR-0139 by promotion, not duplication.** The in-process ONNX serving path moves
from `openbank-fraud-service/infrastructure/ml` to `openbank-libs-runtime` with the `onnxruntime`
dependency, so fraud and NBA share one loader, one card-verification path and one failure semantic
(load or verify failure means the port returns null forever and the deterministic layer decides).
ADR-0141's `scope` allow-list gains a value for this model class — the allow-list is doing its job by
rejecting a foreign model, and widening it is a reviewed act rather than a workaround.

**D7 — Shadow before champion, per ADR-0139.** NBA ships in shadow: scored, logged, metric-recorded,
output discarded, deterministic ordering wins. Promotion needs measured shadow agreement, a model card
(ADR-0141), and the AI Act registry entry from D8 already merged.

**D8 — A registry entry that is actually generated.** `openbank-libs/governance/ml-systems.yaml` gains
`campaign-next-best-action` with `risk_class` limited/minimal and a `basis` field stating D5's
boundary as the reason, alongside today's four entries. Two cautions carried forward: the entry must be
produced by `gen-eu-ai-act.py` and the generated inventory regenerated in the same PR, because
`eu-ai-act-registry` has gone red on a PR that merged anyway (#2216) — an advisory check over a
*generated* artifact leaves drift mergeable, and a registry that omits a live AI system is the exact
failure it exists to prevent. And `deployed: false` must flip to `true` in the PR that deploys, not
later. The existing registry already distinguishes two things the `campaign-next-best-action` entry
must not conflate: `ml-decisioning-platform` (the shared feature-store/ONNX substrate) stays
`deployed: false`, because it is infrastructure, not a standalone decision system, while
`fraud-real-time-scoring`, the system that actually consumes that substrate, is `deployed: true`
(that entry's `basis` describes the fleet's rule-engine decision outcome and does not separately
distinguish the ML shadow score within it — a pre-existing registry granularity question this ADR
does not need to resolve). `campaign-next-best-action` follows the second pattern: it is a consuming
system, not infrastructure, so `deployed: true` applies once campaign-service runs it in shadow per
D7 — shadow running in production is deployed, and this entry should not wait for champion promotion
to say so.

**D9 — Licensing.** Segment and NBA logic lives in campaign-service and `openbank-libs`, neither of
which is agent-plane, so both stay Apache-2.0 (ADR-0197). The campaign-copilot *agent* of ADR-0203 is
separate and is AGPL.

## Alternatives considered

- **Hand-written SQL segments in an admin UI.** Fastest to ship, infinitely flexible, and what most
  campaign tools actually offer. Rejected: an unversioned query is not reproducible, so the preview
  count and the sent set can differ with nobody able to prove which was right; it grants ad-hoc read
  access across customer data from a UI; and it cannot be backtested. D1's cost — a code change per new
  segment — is the same trade ADR-0176 D4 already made for message templates, and for the same reason.
- **A second, campaign-specific feature pipeline.** Independent of fraud's, so no shared-code risk and
  no coordination. Rejected precisely because it recreates training/serving skew: two pipelines
  computing "engagement recency" will disagree, and ADR-0140's one-definition rule exists to make that
  impossible. It would also duplicate the online store's operational surface.
- **Train on the online Valkey store and skip the offline half.** Removes D4's dependency entirely and
  is very tempting, because the data is already there. Rejected as the specific error ADR-0140 was
  written to prevent: the online store holds current windowed counters, not point-in-time values, so a
  model trained on it learns from feature values that did not exist at the decision moment. The failure
  is invisible in offline metrics and appears as unexplained live underperformance.
- **A managed recommendation service (AWS Personalize or similar).** No modelling work, good cold-start
  handling. Rejected on the ADR-0175 residency boundary and the ADR-0174 exit position, as in ADR-0199
  — and additionally because an opaque third-party ranker cannot supply the AI Act transparency and
  documentation obligations that the in-repo model card and registry give for free.
- **Let an LLM choose the next action directly.** Attractive and cheap to prototype, and adjacent to
  the ADR-0203 campaign-copilot. Rejected for the runtime path: a per-customer LLM call is both a cost
  per recipient that scales with the cohort and a non-reproducible decision with no model card, no
  shadow mode and no champion/challenger record. The LLM's place is helping a human *author* a campaign
  (ADR-0203), where a human approves the output; the runtime ranker stays a deterministic-plus-ONNX
  path.
- **Skip NBA entirely and ship only D1/D2 deterministic segments.** Genuinely sufficient for the first
  release and the cheapest correct answer. Not rejected — it is the sequencing this ADR mandates in D2.
  The decision recorded here is the boundary and the platform reuse, so that when a model is added it
  cannot be added the wrong way.

## Consequences

**Positive**
- ADR-0140's offline half gets a consumer and therefore a deadline, and its one-definition claim gets
  a second user, which is what makes it more than an aspiration.
- ONNX serving becomes fleet infrastructure instead of one service's private adapter, so the next
  model-using service pays nothing.
- The AI Act boundary between communication ranking and credit decisioning is written down before a
  model exists to cross it, which is far cheaper than reclassifying a deployed system.
- Segments become reproducible artifacts, so a marketer's preview and the audit trail agree.

**Negative**
- Promoting ONNX serving into `openbank-libs-runtime` puts a native-library dependency into the shared
  runtime module, which every service then carries in its dependency graph whether or not it scores a
  model. Image size and CVE surface both grow fleet-wide; the alternative is duplication, and this is
  the lesser cost but it is not zero.
- Building the offline snapshotter is real work that this ADR depends on but does not do; D4's honest
  partition means the ADR can be half-delivered, and a half-delivered state must not be described as
  shipped.
- A second writer to the online store makes the deferred Lua CAS a sharper problem.
- D5's boundary is a policy, and policies are crossed by well-meaning increments. It needs a test or a
  guard rather than only this paragraph — the repo's own lesson is that a hard checkable rule belongs in
  `rules.yaml` with a CI guard, and "NBA output must not reach a credit decision path" should become
  one.

**Neutral**
- Which specific features join the catalogue is an implementation matter; the mechanism is what is
  decided here.
- Whether NBA eventually earns a champion promotion depends on measured shadow performance, which this
  ADR deliberately does not predict.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is a feature input.
- DORA: not applicable beyond the ADR-0174 register position already recorded for rejecting a managed
  recommendation service.
- GDPR: Art. 22 is **not** engaged, and the reason is D5: ranking which existing message a customer
  sees is not a decision producing legal effects or similarly significantly affecting them. Were the
  output to gate credit access, Art. 22 and Annex III.5(b) would both engage — which is why D5 is a
  prohibition rather than a guideline. Art. 5(1)(c) minimisation applies to the feature catalogue: a
  feature is added because a decision needs it, not because the event exists. Art. 13/14 transparency
  for profiling, and Art. 30 for the new purpose.
- PSD2: not applicable — no account access, no initiation. Feature inputs derive from events the bank
  already holds as controller, not from TPP-granted access.
- CNB: not applicable — nothing here feeds a statutory report.

EU AI Act: this system is classified limited/minimal risk on the strength of D5's boundary, registered
in `ml-systems.yaml` per D8, and inherits the ADR-0148 assurance path (prompt registry, evals gate,
Annex IV inventory). Transparency obligations under Art. 50 are met by disclosing personalised
communication in the app rather than by the registry entry alone. The 2026-08-02 milestone applies, so
the registry entry should not lag the build.

## References

- [ADR-0139](0139-ml-decisioning-platform.md) — the platform, the shadow-to-champion path, and the
  permanent deterministic floor.
- [ADR-0140](0140-feature-store-topology.md) — one definition, two stores; the offline half D4 depends
  on, and the reverted duplicate-port incident.
- [ADR-0141](0141-model-registry-provenance.md) — model cards, digest pinning and the `scope`
  allow-list D6 widens.
- [ADR-0142](0142-credit-decisioning-engine.md) — the high-risk controls D5 exists to stay outside of.
- [ADR-0148](0148-ai-assurance-prompt-registry-evals-gate-and-eu-ai-act-mapping.md) — the assurance and
  Annex IV inventory path.
- [ADR-0199](0199-customer-360-read-model-in-a-new-crm-service.md) — the view segments are evaluated
  against.
- [ADR-0200](0200-campaign-journeys-as-temporal-workflows-with-consent-gated-delivery.md) — the only
  consumer of an NBA ranking.
- [ADR-0175](0175-data-residency-and-sovereignty.md) and
  [ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md) — why a managed recommender was
  rejected.
- `openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/feature/` — `FeatureDefinition`,
  `OnlineFeatureStore`, `VelocityFeatures`.
- `openbank-fraud-service/src/main/kotlin/com/openbank/fraud/infrastructure/ml/OnnxFraudModel.kt` — the
  serving path D6 promotes.
- `openbank-libs/governance/ml-systems.yaml` — the four current entries D8 adds a fifth to.
- Issue #2216 — an advisory check over a generated artifact leaves drift mergeable.
