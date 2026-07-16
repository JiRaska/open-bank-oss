# ADR-0176 — Operator-initiated customer messaging

Date: 2026-07-16
Decision-Status: Proposed
Delivery-Status: Planned
Author(s): jiri.raska

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

3. **There is no marketing consent to check, only the appearance of one.** Four sources
   disagree. `notification-service`'s own compliance doc says marketing-style templates require
   Art. 6(1)(a) consent "managed upstream (consent-service)". consent-service has no marketing
   scope — `ConsentScope` covers AISP/PISP/CBPII, the `AGENT_*` scopes, and `TELEMETRY_RUM`. The
   actual `marketing_consent` column lives in party-service and is mapped to no entity, so
   nothing reads or writes it. And the admin-ui compliance page asserts the control is `ok`.
   `grep -rn "consent" openbank-notification-service/src/main/kotlin/` returns zero matches. Any
   marketing send today would rest on a control that does not exist.

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
maker-checker-gated** capability, and codify the classification model the service has been
missing.

### D1 — Template sensitivity is a domain classification

Every `NotificationTemplate` carries a sensitivity class. The first class ships already
(`TemplateSensitivity`, issue #1179): **SECRET** templates (`OTP_CODE`, `PASSWORD_RESET`) render
an authentication secret, are delivered, and are **never stored** — the body column holds a
placeholder and the read path redacts again, so two independent controls must both fail (the
ADR-0059 D3 shape).

This ADR extends the model to the remaining classes, which govern who may read a body and what
may reach a push payload:

| Class | Examples | Stored body | Push payload | Admin-UI body |
|---|---|---|---|---|
| `SECRET` | `OTP_CODE`, `PASSWORD_RESET` | placeholder only | never | never |
| `OPERATIONAL` | `TRANSACTION_FAILED`, `ACCOUNT_FROZEN` | yes | wake signal only | operator + |
| `SERVICE` | `TRANSACTION_COMPLETED`, `ACCOUNT_OPENED` | yes | wake signal only | operator + |
| `MARKETING` | `WELCOME` | yes | wake signal only | operator + |

Classification is a positive allow-list in the domain layer, adjacent to the enum. A template
whose render embeds a secret and is not classified is a **review** failure, not a test failure —
`renderTemplate` and the allow-list must be reviewed together. Stated plainly because the
pinning test cannot catch it: it pins the set's contents, so a new enum constant leaves it
unchanged and passing.

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

**This extends ADR-0155, whose title and scope are "four-eyes enforcement for *money-path*
actions".** That scoping is precisely what force 4 runs into: the mechanism is sound and
fleet-wide, but its trigger is coupled to a service list `notification-service` will never
join. ADR-0176 decouples the trigger from that list without changing the mechanism — ADR-0155
is extended, not superseded, and its money-path defaults stand untouched.

The existing `four_eyes.verbs` list keeps its money-path coupling; the two are read
disjunctively. Adding `notification-service` to `money_path_services` instead is rejected below.

The approval flow itself is reused unchanged from ADR-0155 via `libs/approval/ApprovalStore`:
the maker gets HTTP 202, a second principal decides, the maker retries with `X-Approval-Id`, and
self-approval is refused by `SelfApprovalNotAllowedException`. Enforcement is behind
`AUTHZ_FOUR_EYES_ENFORCE`, `false` fleet-wide today. This action is authored to be enforced from
day one in sandbox; that flip is not gated on the fleet-wide rollout.

### D6 — Purpose is explicit, and marketing is refused until consent is real

Every operator message declares a purpose, which fixes its lawful basis:

| Purpose | Lawful basis | Consent gate |
|---|---|---|
| `SERVICE` | Art. 6(1)(b) contract performance | none |
| `LEGAL` | Art. 6(1)(c) legal obligation | none |
| `MARKETING` | Art. 6(1)(a) consent + Act No. 480/2004 §7 | **hard-denied** |

`MARKETING` is refused at the API — not merely hidden in the UI — until a real consent gate
exists. That gate needs a marketing scope in consent-service, `marketing_consent` mapped in
party-service, a check in notification-service, and the false `ok` on the admin-ui compliance
page corrected. Work on marketing-consent revocation is already in flight; this ADR does not
front-run it.

This is also the repo's first explicit treatment of **purpose limitation** (GDPR Art. 5(1)(b)).
No ADR covers it today, and `AuditEvent` has no purpose or legal-basis field — it infers purpose
from the `operation` string. Carrying purpose as a first-class field on the message, rather than
inferring it from the template, is what makes the consent gate checkable at all.

### D7 — Reading a customer's history is role-split and recorded

The admin-ui party detail page gains a messages tab backed by the existing
`GET /notifications?partyId=`. Metadata (template, channel, status, timestamps) is visible to
`ROLE_OPERATOR`/`ROLE_ADMIN` under a new `notifications:view` permission. Bodies follow D1.

Reads emit an audit event. This is the honest gap in this ADR: the default `AuditEventPublisher`
binding is a log line, no Kafka implementation exists outside agent-service, `@Audited` has no
interceptor, and audit-service consumes no notification topic. Recording operator reads therefore
needs real wiring, tracked separately — a fleet-wide accountability gap this feature surfaces
rather than creates. We will not claim Art. 5(2) coverage for read access until it lands.

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
  coverage floor and a required SLO object pair onto the whole service to gate one action — and
  it still would not work, because the gate matches by *verb*, so a `send`-shaped action would
  sweep in the M2M callers the `rules.yaml` guardrail explicitly warns about.
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
- The catalogue overlaps conceptually with `openbank-document-service` templating (ADR-0162).
  Kept separate: that renders documents, this renders messages, and merging them would couple two
  lifecycles over a superficial similarity.

## Compliance impact

- GDPR Art. 5(1)(b): purpose limitation — first explicit treatment in the repo (D6).
- GDPR Art. 5(1)(c): data minimisation — secret bodies never stored (D1); push payloads carry no
  content (D3).
- GDPR Art. 5(2): accountability — operator reads recorded (D7), **not yet honest**: depends on
  audit wiring that does not exist. Not claimed as covered until it lands.
- GDPR Art. 6(1)(a)/(b)/(c): lawful basis fixed per purpose; marketing hard-denied (D6).
- GDPR Art. 7: demonstrable consent — the precondition marketing is blocked on (D6).
- GDPR Art. 21: right to object — marketing preference; blocked with D6.
- Act No. 480/2004 Coll. §7 (CZ, obchodní sdělení): commercial communications need prior consent
  — the second, independent reason marketing is refused (D6).
- PSD2 Art. 97 / ADR-0021: SCA channel integrity — a stored OTP lets staff complete a customer's
  SCA (D1).
- DORA Art. 9(4)(b): protection of ICT assets — dual control on the operator write path (D5).
- ČNB: four-eyes on customer-facing staff actions, consistent with ADR-0116's KYC role split.

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
- Issue #1179 — secret-bearing bodies stored readable (D1's shipped first slice)
- GDPR (EU) 2016/679, Art. 5, 6, 7, 21
- Act No. 480/2004 Coll. §7 (CZ) — commercial communications
