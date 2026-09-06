---
date: 2026-07-16
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [notifications, admin-ui, governance]
summary: "Operator-initiated customer messaging ships as a catalogue-driven, service-message-only, maker-checker-gated capability with a closed per-template variable schema, because template-keyed secrecy flags cannot catch secret-shaped variables."
---

# ADR-0176 — Operator-initiated customer messaging

**Correction note (2026-07-16, issue #1331).** The first version of this ADR shipped with a false
premise. Force 3 claimed marketing consent did not exist; it does — live, revocable and audited
(#1157/#1161), merged seven hours before this ADR. The claim came from a grep run against a local
checkout hundreds of commits behind `origin/main`, and a broken probe's silence became a stated
fact. Force 3, D6 and D1 are rewritten below; the false rejection of an alternative is removed; two
compliance citations are downgraded to unverified. **What is corrected is recorded, not
overwritten** — the errors are as instructive as the decisions, and an ADR that quietly rewrites its
own reasoning teaches nothing.

**Delivery note (updated 2026-07-17):** D1/D4/D5/D7 shipped; D2 partial; D3/D6 pending.
- **D1 (closed template variable schema)** — ✅ Shipped: `NotificationTemplate` closed schema, exhaustive
  `renderTemplate` (no `else`), undeclared-key rejection.
- **D2 (operator compose + approval)** — ⬜ Partial: the backend is real — `OperatorMessageService`/
  `OperatorMessageResource` (`POST /api/v1/notifications/messages`), `OperatorMessageTemplate` catalogue, no
  free-text body. But the admin-ui compose UI (`opsMessageApi`) targets a non-existent `/opsmessages` contract
  (wrong paths, template `OPERATOR_ACCOUNT_NOTICE`, extra `purpose` field) and 404s through the pass-through
  BFF — an operator cannot yet compose from the console. Tracked as a bug separately.
- **D3 (push wake-signal)** — ⬜ Pending: `sendPush` still ships full rendered content; operator PUSH is
  refused (EMAIL-only), blocked on #1182. Deferred to `openbank-app`.
- **D4 (`opsmessage.*` authz namespace)** — ✅ Shipped: `opsmessage.compose` / `opsmessage.approval.decide`;
  `rest.rego` excludes service-accounts and the edge; pinned in `rest_test.rego`.
- **D5 (four-eyes on compose)** — ✅ Shipped: `rules.yaml four_eyes.actions=[opsmessage.compose]`,
  `RedisApprovalStore`, `ApprovalResource` with self-approval guard.
- **D6 (purpose / lawful-basis binding + marketing hard-deny)** — ⬜ Pending: no purpose field; marketing is
  only implicitly excluded (no template), with no API hard-deny. Blocked on the #1331 consent-authority decision.
- **D7 (per-customer message history)** — ✅ Shipped: admin-ui `MessagesTab`, metadata-only,
  `notifications:view` gate.

## Context

Support staff need to send a customer a message from the admin console, and to see that
customer's message history on their party detail page. Neither exists today, and the gap is not
a missing button — the service has no operator-facing write path at all.

**What `openbank-notification-service` does today.** The only ingress that creates a
notification is the `openbank.notification.requests` Kafka topic. `NotificationRequest.template`
is typed as the closed `NotificationTemplate` enum, so an arbitrary string is rejected as a
poison payload. Subject and body are never caller-supplied: `renderTemplate` produces both from
the template plus a `variables` map. There is no `POST /notifications` and no
`notification.send`-style action anywhere in the repo. Read and manage endpoints do exist
(`GET /api/v1/notifications?partyId=`, `GET /{id}`, mark-read, device registration, and the
`ops/dispatch` break-glass surface), and `openbank-admin-ui` already renders a fleet-wide,
read-only notification log — but nothing per-customer, and nothing that composes.

This is the first ADR to cover communication *architecture*. ADR-0021 treats push as an SCA
transport; ADR-0135 secures push **tokens**; ADR-0059 governs Slack/Teams **oversight** egress.
None of them says what a customer message is, who may send one, or on what lawful basis.

Five forces make the naive version — a textarea wired to a new endpoint — the wrong build:

1. **Free text from a bank is a phishing primitive.** A message rendering operator-supplied
   prose, arriving in the bank's own trusted channel, is indistinguishable from an attack if the
   operator is compromised, coerced, or malicious. This is the one channel a customer is trained
   to trust.

2. **Push payloads may not carry content.** ADR-0135 §3 forbids account numbers, amounts,
   merchant names, and PII beyond a non-identifying reference in the APNs/FCM payload, because
   notification content is visible on a locked device. Operator free text cannot be shown to
   satisfy that by inspection. **The rule is already violated in code**: `sendPush` sends
   `htmlToPlain(body)` as the push text, so `TRANSACTION_COMPLETED` — the one template a real
   producer emits — puts amount and currency into the payload today, while the ADR-0135 delivery
   note records payload minimisation as complete. Adding a compose path on top of an unenforced
   rule would entrench the violation.

3. **Marketing consent exists, but nothing in the send path reads it — and it lives in the wrong
   service.** Two distinct problems, previously (and wrongly) stated as one.

   *The consent is real.* `consent_marketing` + `consent_marketing_updated_at` (party-service
   `V12__consent_capture.sql` / `V13__marketing_consent_updated_at.sql`, #1157/#1161) are mapped on
   `PartyEntity`, read and written by `PartyRepositoryImpl`, exposed as `PATCH` on `PartyResource`
   via `UpdateMarketingConsentCommand` with an ADR-0086 audit event, and revocable by the customer
   through `PATCH /customer/v1/profile/consent` on customer-edge. There is also a **dead**
   `marketing_consent` column (`V2__compliance_fields.sql`) with zero readers — the earlier draft
   cited that one and concluded no control existed.

   *Nothing checks it.* `grep -rin "consent"` over notification-service's Kotlin returns four
   matches, all `CONSENT_GRANTED`/`CONSENT_REVOKED` template names. None is a check. That is a
   wiring gap in one service, not a missing control.

   *It is in the wrong place.* A marketing opt-in is a GDPR Art. 7 consent — ADR-0126's regime 2,
   for which ADR-0126 declares `openbank-consent-service` the **single consent authority**. But
   `ConsentScope` has no marketing scope (`grep -rin marketing` over consent-service → zero), and
   #1161 put the consent in party-service instead. **Two ADRs say one thing and the code does
   another**, and that conflict outranks this ADR — see D6.

4. **Four-eyes cannot currently express this gate.** The ADR-0155 mechanism is wired fleet-wide
   (`AuthorizeInterceptor.requireFourEyes` → HTTP 202 + `X-Approval-Id`), but `rest.rego`
   computes `four_eyes_required` from *money-path scope × verb name*. `notification-service` is
   not a money-path service, so no verb it emits can be gated. The `rules.yaml` guardrail is
   explicit that the computation is by action name alone with no awareness of the caller — so the
   verb vocabulary cannot simply be widened either: `send` already exists (for `swift.send`), and
   any notification action reusing it would sweep in automated callers.

5. **The read side leaks by construction.** `rest.rego`'s `operator-read-any` grants `.read` and
   `.list` on *any* resource to every `ROLE_OPERATOR`/`ROLE_ADMIN`, and `GET /notifications/{id}`
   returns `body` to `ROLE_VIEWER` upward. Reading a customer's message history is itself a
   processing activity, and no notification topic reaches audit-service's consumed list — so it
   happens unrecorded.

## Decision

We will add operator-initiated messaging as a **catalogue-driven, service-message-only,
maker-checker-gated** capability, and close the input-validation gap the service has always had:
templates accept whatever variables a caller sends.

### D1 — A closed variable schema per template; sensitivity is the second control

**Secrecy is a property of the variables, not of the template.** The first draft missed this, and
so does the code that shipped for #1179. `TemplateSensitivity` (#1180) is a **binary predicate** —
`SECRET_TEMPLATES: Set<NotificationTemplate>` + `isSecret()` + `bodyForStorage()` — keyed on the
template. But `renderTemplate` maps only 6 of the 13 constants; the other 7 fall to an `else` branch
that dumps every caller-supplied variable into the body. So:

```
template: ACCOUNT_FROZEN   (not SECRET → stored)
variables: { "code": "483920" }
→ else branch renders "code: 483920" → bodyForStorage(ACCOUNT_FROZEN, …) → stored in cleartext
```

A template-keyed allow-list cannot express *"this correctly-classified template received a
secret-shaped variable"*. Worse, the review gate the first draft proposed is blind here: the leak
lives in the branch that has **no per-template code to review** (issue #1325).

The primary control is therefore D2's own reasoning applied to the enum:

1. **A closed variable schema per template.** Declare the variables each template accepts; reject
   unknown keys at the consumer boundary. A `code` on `ACCOUNT_FROZEN` becomes a poison payload.
2. **Delete the `else` branch; make the `when` exhaustive.** A new constant then fails to
   *compile* until someone writes its render and declares its variables — a compiler error, not a
   review failure. That is the only guard that does not depend on a human noticing.

`TemplateSensitivity` stays as the **second, independent control** (ADR-0059 D3 shape) — it is
useful, it just cannot be the only one.

**And it stays a predicate.** The first draft proposed replacing the shipped boolean with a
four-class lattice — `SECRET` / `OPERATIONAL` / `SERVICE` / `MARKETING`. That model was invented
complexity, and writing the table out honestly is what exposes it:

| Class | Stored body | Push payload | Admin-UI body |
|---|---|---|---|
| `SECRET` | placeholder only | never | never |
| `OPERATIONAL` | yes | wake signal only | operator + |
| `SERVICE` | yes | wake signal only | operator + |
| `MARKETING` | yes | wake signal only | operator + |

The bottom three rows are **byte-identical across every column**. They classify nothing: no
behaviour anywhere reads the difference. Sensitivity has exactly two meaningful values — *does the
rendered body carry an authentication secret, or not* — which is precisely
`TemplateSensitivity.isSecret()` as it already exists. **We keep the shipped predicate unchanged**
and drop the lattice.

Two further corrections the collapse makes moot but which are worth recording. `WELCOME` is **not**
marketing: its rendered body ("Thank you for joining OpenBank, {name}") promotes no product. The
first draft inherited that label from an unexamined comment in `OversightWebhook.kt` — and had it
been right, D6's marketing hard-deny would have refused it.

> **Correction, 2026-09-05 (#8568).** That sentence originally said the hard-deny would have made
> "the live onboarding flow non-compliant today". It would not have: `WELCOME` has **no producer**
> — nothing anywhere emits it, verified by emission shape rather than by name — so there is no live
> flow to make non-compliant. The correction above stands on its own merits; only the claim about
> live impact was wrong. Kept in the enum for now: the onboarding moment it would occupy already
> carries `KYC_APPROVED` and then `ACCOUNT_OPENED`, so a third greeting is a product decision about
> replacing one of those, not an omission to fill. And the first draft used the name `MARKETING` on both this
axis and D6's, so one template was simultaneously deliverable (D1) and refused (D6). Sensitivity and
purpose are orthogonal, and now only one of them uses the word.

The lesson generalises past this ADR: a classification whose rows share every column is decoration.
If adding a class changes no behaviour, it is a comment with extra steps — and it costs a migration,
a type, and a reviewer's attention to learn that.

### D2 — Operator messages are catalogue entries, not free text

An operator selects an approved template and fills its parameters. We will **not** ship a
free-text body. The catalogue is versioned, reviewed, and rendered server-side exactly as
today's enum templates are — so no operator-supplied prose ever reaches a customer, and force 1
is closed by construction rather than by a filter.

Rejecting free text is the load-bearing choice here. The alternatives (URL stripping, `PiiMask`
over the composed text, link allow-lists) are all filters over an attacker-controlled string,
and each is a bypass hunt. A catalogue is an allow-list of *meanings* — the same reason
ADR-0059 D2 chose an allow-listed schema over scrubbing a rendered one.

The cost is real and accepted: a new message shape needs a code change and a release, so this is
not a campaign tool. That is the intended blast radius.

### D3 — Push carries a wake signal, never content

`PushMessage` for any operator-initiated message carries a generic title, no body text, and a
`notificationId` in the data payload. The app fetches detail via an authenticated
`GET /api/v1/notifications/{id}` on tap — exactly the design ADR-0135 §3 already prescribes and
which the current `htmlToPlain(body)` path contradicts.

This makes ADR-0135 §3 true by construction for the new path. Retrofitting the *existing*
templates onto the wake-signal shape (force 2) is a prerequisite tracked separately: it changes
what the customer sees on the lock screen and therefore depends on the customer app's
fetch-on-tap handling, which lives in the `openbank-app` repository. Until that lands, the
ADR-0135 delivery note must stop claiming payload minimisation is complete.

### D4 — A distinct action namespace: `opsmessage.*`

Operator messaging actions are `opsmessage.compose` / `opsmessage.approve` / `opsmessage.reject`
— deliberately **not** under `notification.*`.

`rest.rego`'s `edge-service-notification` rule grants the customer-edge M2M identity every action
matching `startswith(input.action, "notification.")`. Any new `notification.*` action is
therefore auto-granted to customer-edge the moment it is created. A separate namespace sidesteps
that entirely, and follows the precedent already in this service: `dispatch.halt` /
`dispatch.approve` sit outside `notification.*` for the same reason.

Tightening that `startswith` to an explicit action list is worthwhile hardening on its own and is
tracked separately — but D4 does not depend on it, and must not: a namespace that is safe only
while a *different* rule stays correct is not safe.

### D5 — Four-eyes by exact action name

We will add `four_eyes.actions` to `rules.yaml`: a list of **exact action names** requiring a
second approver, evaluated by `rest.rego` independently of money-path scope. `opsmessage.compose`
is its first entry.

**This generalizes the `featureflag.flip` precedent, not ADR-0155's scope.** The first draft framed
D5 as extending ADR-0155 and decoupling a trigger "coupled to a service list". That oversells it:
`four_eyes_required` already has **two** clauses, and the second is not money-path-scoped at all —

```rego
four_eyes_required if {
	input.action == "featureflag.flip"
	input.attributes.flag in data.rules.feature_flags.money_path_flags
}
```

an exact action name gated on a `rules.yaml` list, shipped with ADR-0067. D5 is a third clause in
that same shape. Less novel, and correspondingly less risky.

The authoritative argument is one the first draft never cited — `rules.yaml`'s own four-eyes
guardrail ends: *"the risky path needs its own distinct action (e.g. a dedicated operator-only
`enrollOnBehalf` endpoint) before it can be four-eyes gated safely — do not reuse the shared
customer/M2M-facing action."* D4+D5 is exactly that prescription.

The existing `four_eyes.verbs` list keeps its money-path coupling; the two are read disjunctively.

**Prerequisite, and it is a fail-open one.** `AuthorizeInterceptor` proceeds *without* the gate when
no `ApprovalStore` bean is wired — it logs an error and calls `ctx.proceed()`. notification-service
has no `ApprovalStore` producer, no decide endpoint, no `SelfApprovalNotAllowedMapper`, no
`authz.four-eyes.enforce` key, and **no Redis client** (the only `ApprovalStore` impl is
`RedisApprovalStore`). Flip `AUTHZ_FOUR_EYES_ENFORCE=true` today and `opsmessage.compose` would
execute unapproved with a log line as the only trace. So D5 is not "reused unchanged": it is five
files plus a Redis dependency, and **the wiring must land before the flag, never after.**

**Bundle ripple.** `gen-notification-opa-bundle.sh` hashes `rules.yaml` into its checksum, as do
~25 of the 27 generators. Adding `four_eyes.actions` rolls every one of those pods and turns the
`opa-policy.yml` verify job red unless all are regenerated and committed in the same PR.

### D6 — Purpose is a property of the catalogue entry, and marketing stays refused

Every message carries a purpose, which fixes its lawful basis and its channel rules:

| Purpose | Lawful basis | Channel rule | Consent gate |
|---|---|---|---|
| `LEGAL` | Art. 6(1)(c) legal obligation | any | none |
| `SERVICE` | Art. 6(1)(b) **only where genuinely necessary** to perform the contract; otherwise Art. 6(1)(f) | any | none, but Art. 21(1) objection applies to the 6(1)(f) cases |
| `MARKETING` | Art. 6(1)(a) consent + Art. 7 | §7(3) soft opt-in is **email-only**; push/SMS always need consent | **hard-denied** |

**Purpose binds to the catalogue entry, not to the message.** This reverses the first draft, which
argued for an operator-declared per-message field. Under D2 an operator can only pick a versioned,
reviewed entry — so purpose is a property of that entry's *meaning*, fixed at review time by the
people who write it, and unfalsifiable by the sender. Make it operator-declared and the `MARKETING`
hard-deny becomes an honour system: label a promo-shaped template `SERVICE` and the gate passes
silently. D2 and D6 were in direct tension; D6 loses.

**`SERVICE` is not a blanket 6(1)(b).** EDPB Guidelines 2/2019 read "necessary for the performance
of a contract" strictly, and most service messages are *useful*, not *necessary*. This is not
paperwork: the Art. 21(1) right to object exists **only** for 6(1)(e)/(f), so labelling a
legitimate-interest message 6(1)(b) deletes the customer's right to object and writes a false
Art. 30 record. Each catalogue entry states its basis explicitly and defends it; there is no default.
(Art. 21(2) — objection to direct marketing — applies regardless of basis, so this loss is specific
to service messages.)

**Art. 6(1)(a) for marketing is a deliberate choice, not the only lawful one.** Recital 47 says
direct-marketing processing *"may be regarded as carried out for a legitimate interest"* — permissive,
case-by-case. Art. 6(1)(f) is therefore available. We take consent anyway, because #1161 already
collects a revocable one and a consent the customer can withdraw from a Profile screen is the
posture we want for a bank. Choosing the stricter basis is the decision; it is recorded here so it
is not mistaken for the only option.

**`MARKETING` stays hard-denied at the API** — not merely hidden in the UI. Not because the consent
is missing (it is not, see force 3), but because **notification-service does not read it and the
authoritative location is undecided**. The gate needs: the split-brain below resolved, then a check
in notification-service against whichever service wins, then the admin-ui compliance page's note
corrected (`docs/compliance/page.tsx` still cites the dead `marketing_consent` V2 column — its `ok`
status is now defensible, its reason is stale).

**The split-brain this ADR must not paper over.** ADR-0126 declares consent-service the single
authority for all three consent regimes, including GDPR Art. 7 — which is what a marketing opt-in
is. #1161 put it in party-service. Asking for a consent-service scope (as this ADR does) sides with
ADR-0126 against the shipped code; that is a real decision and it is **not this ADR's to make**.
Someone must choose: consent-service grows a marketing scope and the party-service column becomes a
projection, or ADR-0126's "single authority" is amended to carve out simple per-party opt-ins.
Tracked in #1331. Until then D6's marketing row is a placeholder pointing at an unsettled address.

This is also the repo's first explicit treatment of **purpose limitation** (GDPR Art. 5(1)(b)).
No ADR covers it today, and `AuditEvent` has no purpose or legal-basis field — it infers purpose
from the `operation` string. Binding purpose to the catalogue entry is what makes the gate
checkable: the value is fixed by review, carried into the audit record, and cannot be chosen by
the person the gate exists to constrain.

### D7 — The history tab shows metadata only, because the read gate is not ours to set

The admin-ui party detail page gains a messages tab backed by the existing
`GET /notifications?partyId=`, showing **metadata only** (template, channel, status, timestamps).
Bodies are not fetched: the list endpoint returns none, and the page does not call
`GET /notifications/{id}`, which does.

**`notifications:view` is UX gating, not a security control.** The first draft claimed this
permission role-splits read access. It does not, and the reason is the same force 5 already named:
`operator-read-any` grants `.read`/`.list` on *any* resource to every `ROLE_OPERATOR`, and the
admin-ui BFF relays the operator's own bearer with **no permission check of its own** (`grep -cE
"hasPermission|requirePermission"` over the proxy → 0). A UI permission decides what we *render*,
never what an operator can *fetch* — anyone who can open the console can already call the endpoint
from devtools. The tab therefore adds **convenience, not exposure**, and the permission's comment
in `roles.ts` says so, so nobody later mistakes it for a boundary.

Real metadata/body separation needs a `rest.rego` change — either splitting the read into a
metadata action and a body action, or carving the namespace out of `operator-read-any` (which is
prefix-blind). That is issue #1326 and it gates showing bodies at all.

**The role gates disagree with each other, in both directions.** `@RolesAllowed` on
`NotificationResource` lists `ROLE_VIEWER`, whom OPA then denies (no rule fires for a pure viewer);
`rest.rego`'s `compliance-read-any` admits `ROLE_COMPLIANCE`, whom `@RolesAllowed` then denies.
Neither is a hole — the intersection fails closed at `ROLE_OPERATOR`/`ROLE_ADMIN` — but both are
dead grants that mislead anyone reading one file alone. Also `classifyBffFailure` has no 403 branch,
so either denial surfaces as a generic "error". Catalogued in #1326.

**Reads are not audited, and this ADR does not pretend otherwise.** The default
`AuditEventPublisher` binding is a log line, no Kafka implementation exists outside agent-service,
the `@Audited` annotation had no interceptor and has since been removed (#4011), and
audit-service consumes no notification topic. Recording operator
reads needs real wiring, tracked separately — a fleet-wide accountability gap this feature surfaces
rather than creates. We do not claim Art. 5(2) coverage for read access until it lands.

One consequence worth naming: the tab converts dormant, over-retained rows into a live staff-facing
lookup surface. That is an Art. 5(1)(b) purpose shift (delivery record → staff retrieval), and it
raises the stakes of the unenforced 2-year retention (`governance.yaml` declares it; no purge job
exists) even though it does not create that breach.

Erasure already works (`PartyErasureConsumer` hard-deletes on `PARTY_ERASED`); the tab renders
the empty state and does not distinguish "erased" from "never messaged".

## Alternatives considered

- **Free text with server-side controls** (URL stripping + `PiiMask` + four-eyes + rate limit).
  Flexible, and each control is individually reasonable. Rejected: every one is a filter over an
  attacker-controlled string, so the security argument reduces to "our regex is complete", which
  it never is. It also worsens retention — operator prose is unclassifiable PII in a table with
  no purge job. Revisit only given an operational need the catalogue provably cannot serve.
- **Add `notification-service` to `money_path_services`** so the existing four-eyes computation
  applies. Rejected: it drags 2-approval review, a mandatory threat model, mutation testing, a
  coverage floor and a required SLO object pair onto the whole service to gate one action.
  (The first draft also claimed it "still would not work". That was false and is withdrawn:
  `money_path_scopes` would derive the scope `notification`, and `notification.send` ends in a
  `four_eyes.verbs` entry, so the gate *would* fire. The cost above is reason enough on its own —
  a rejection does not need a second, wrong argument propping it up.)
- **Reuse `notification.send` as the action name.** Rejected: `send` is already a `four_eyes`
  verb (`swift.send`), and `notification.*` is auto-granted to customer-edge by the
  `edge-service-notification` rule. Two independent traps in one name.
- **Deliver operator messages over EMAIL only**, avoiding push entirely. Tempting, since it
  sidesteps D3 — but it puts message content in an unauthenticated channel and leaves the
  notification centre inconsistent. Rejected in favour of the wake-signal design ADR-0135 already
  mandated.
- **A campaign / segment tool** (send to a cohort). Out of scope by design: it is a marketing
  capability, and D6 refuses marketing until consent is real. Revisit afterwards, as its own ADR.

## Consequences

**Positive**
- No operator-supplied prose ever reaches a customer: the phishing surface is closed by
  construction, not by filtering.
- ADR-0135 §3 becomes true by construction for the new path, and the existing violation gets an
  owner instead of being inherited silently.
- Four-eyes becomes expressible for non-money-path actions — a reusable governance primitive
  rather than a one-off.
- Purpose limitation gets its first explicit treatment, and the marketing-consent fiction is
  documented and blocked rather than depended upon.

**Negative**
- Every new message shape needs a code change and a release. This is not a campaign tool, and
  operators will ask for one.
- `four_eyes.actions` is a second mechanism alongside `four_eyes.verbs`, and two lists that both
  gate approvals can drift out of sync — the failure mode `money_path_action_prefixes` already
  demonstrated when its `domestic-payment` override went stale and silently disabled the gate it
  was meant to apply. They are kept disjoint (verbs = money-path scope, actions = exact names)
  and `rest_test.rego` must pin both.
- The `IN_APP` channel is a logging stub that never leaves `PENDING`. It has to become real
  delivery before any of this works.

**Neutral**
- **The catalogue should reuse `openbank-document-service`'s template registry, not reinvent it.**
  The first draft rejected this as "coupling two lifecycles over a superficial similarity". That
  does not survive contact with the code: the PDF coupling is one line in `DocumentRenderService`,
  and everything worth reusing is already PDF-free — `TemplateStatus { DRAFT, PUBLISHED, RETIRED }`,
  `TemplateRepositoryPort.findLatestPublished(code)`, a **DB-enforced** partial unique index
  (one PUBLISHED per code), and `TemplateRenderPort.renderHtml(template, data)`, which does no I/O
  and no PDF. ADR-0162 states the engine is swappable without touching the domain, and an HTML-only
  path already ships (`previewRender`). Meanwhile D2 promises a catalogue "versioned, reviewed, and
  rendered server-side exactly as today's enum templates are" — but today's templates are a
  hardcoded Kotlin `when` with **no versioning at all**, which is the one property `DocumentTemplate`
  exists to provide. Reuse `TemplateRepositoryPort` + `TemplateRenderPort` + the one-published-per-code
  invariant; do **not** reuse `DocumentRenderUseCase`, `Document` (PDF/WORM-shaped, SHA-256 addressed,
  `retain_until`) or `PdfRenderPort`. A message must never become a `Document`. Roughly ten new lines
  are needed for a `code → HTML` path, since no such use case exists today.

## Compliance impact

Read from the primary texts, at engineering grade — not legal advice. The 6(1)(b)-vs-6(1)(f)
classification of service messages (D6) in particular should be confirmed with counsel before it
is encoded in an Art. 30 record.

- GDPR Art. 5(1)(b): purpose limitation — first explicit treatment in the repo (D6). Also the
  purpose shift D7 introduces: delivery record → staff lookup surface.
- GDPR Art. 5(1)(c): data minimisation — secrets never stored (D1); push payloads carry no
  content (D3).
- GDPR Art. 5(1)(e): storage limitation — **not met**. `governance.yaml` declares 2 years; no purge
  job exists. Pre-existing, not created here, but D7 raises its stakes.
- GDPR Art. 5(2): accountability — **not claimed**. Operator reads are unrecorded; the audit
  wiring does not exist (D7).
- GDPR Art. 6(1)(a): marketing — a deliberate choice over the 6(1)(f) that Recital 47 permits (D6).
- GDPR Art. 6(1)(b)/(f): service messages — 6(1)(b) only where genuinely necessary per EDPB
  Guidelines 2/2019; otherwise 6(1)(f), which preserves the Art. 21(1) objection right (D6).
- GDPR Art. 7: demonstrable, withdrawable consent — satisfied by #1161's revocable opt-in; the open
  question is *which service owns it* (D6).
- GDPR Art. 21: (1) objection on particular grounds — only for 6(1)(e)/(f), hence the D6 care over
  `SERVICE`. (2) objection to direct marketing — applies regardless of basis.
- Act No. 480/2004 Coll. (CZ): §7(2) consent for commercial communications by electronic means —
  §2's *"zejména"* makes that non-exhaustive, so it reaches push and SMS. §7(3)'s soft opt-in is
  *"pro elektronickou poštu"* — email only (D6).
- ČNB: four-eyes on customer-facing staff actions, consistent with ADR-0116's KYC role split.
- **Unverified, flagged rather than claimed.** The first draft hooked "a stored OTP breaks SCA" to
  PSD2 Art. 97 (which governs *when* SCA applies; confidentiality of personalised security
  credentials is plausibly RTS (EU) 2018/389 Art. 22), and dual control to DORA Art. 9(4)(b) (which
  concerns data corruption/loss and unauthorised access, and does not obviously mandate it). Both
  look over-hooked. Neither has been checked against the primary text; do not cite them onward
  until someone has.

## References

- ADR-0021 (SCA decoupled device approval) — why a stored OTP is an SCA break
- ADR-0034 (unified OPA authz) — `rest.rego`, the PEP, `operator-read-any`
- ADR-0059 (outbound oversight webhooks) — the allow-list + defense-in-depth shape D1/D2 copy
- ADR-0116 (KYC engine) — precedent for a maker/checker role split (`ROLE_KYC_OPENER`/`REVIEWER`)
- ADR-0118 (GDPR data lifecycle) — PII classification, retention, erasure cascade
- ADR-0126 (unified consent lifecycle) — the consent authority, and the missing marketing scope
- ADR-0135 (push token security) — §3 payload minimisation, violated today by `sendPush`
- ADR-0155 (four-eyes enforcement for money-path actions) — **extended by D5**: same mechanism
  (`ApprovalStore`, `requireFourEyes`, HTTP 202), trigger decoupled from the money-path list
- ADR-0162 (document templating) — the adjacent templating engine, deliberately not reused
- `openbank-libs/governance/rules.yaml` — `four_eyes`, `money_path_services`, the verb guardrail
- Issue #1179 / PR #1180 — secret bodies stored readable; the shipped `TemplateSensitivity` predicate
- Issue #1325 — secret-bearing *variables* bypass that predicate via `renderTemplate`'s `else`
  branch; the gap D1's variable schema closes
- Issue #1326 — operator read of message history is unconstrained; a UI permission cannot gate it (D7)
- Issue #1331 — this ADR's false premise, and the party-service vs consent-service split-brain (D6)
- PR #1303 — the `openapi.yaml` drift correction the D7 tab's `page`/`size` paging depends on
- GDPR (EU) 2016/679, Art. 5, 6, 7, 21
- Act No. 480/2004 Coll. §7 (CZ) — commercial communications
