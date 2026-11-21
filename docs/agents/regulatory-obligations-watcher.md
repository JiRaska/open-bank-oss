---
id: regulatory-obligations-watcher
plane: control
adr: ADR-0367
---

# regulatory-obligations-watcher

## Mission

Once a week, read the legal text and the authority specification that each row of the regulatory
obligations register cites, compare them with what the row says (deadline, cadence, format,
transport), and file a governance issue wherever they disagree or a row's deadline is still marked
`unverified`. Filing that issue (`draft.ticket`) is its only write: it opens no PR and never edits
the register, a report or a filing.

## Why this agent exists

The register is the bank's answer to "which reports do we owe, to whom, and by when". Its value
depends on the rows staying true, and the sources change on their own schedule: a decree is
amended, the central bank republishes a reporting calendar, the deposit-insurance scheme changes
its file format. None of that reaches the repository on its own. An unnoticed change shows up as
a missed or rejected filing. Most deadlines in the register are still `unverified`, too, so most
of the useful work is confirmation: fetch the cited source, quote the decisive sentence, propose
flipping the row to `verified`.

## Human oversight

- `any_legal_interpretation`: whether a changed paragraph changes our duty is a legal
  judgement. Every finding is a proposal that a human confirms or rejects.
- `every: proposal`: every finding is an issue for a human to act on.
- `any_register_change`: the agent never writes to the register. It holds a read grant and one
  `write_proposal` grant (`draft.ticket`) and nothing else; deny-by-default does the rest. Before
  every issue the runtime asks its OPA sidecar for `draft.ticket` and files nothing on a deny. The
  enabling change was checked with `opa eval` against the served bundles: `read.public_web` is
  allowed on a listed host and denied off-list, `draft.ticket` is allowed, and a PR, a write and an
  execute are denied.
- `requires_human` is declarative: `agents.rego` notes that no code path reads it. It records
  intent; the controls above are what enforce it.
- `runs_per_day: 1` and `tokens_per_run: 60000`: a weekly read of a few dozen sources needs no
  more than that.

## How it reads its sources

Through `openbank-fetch-broker` only (ADR-0387), as resource `read.public_web`, on the hosts its
charter lists. The list is the set of hosts the register's `sources:` cite. Fetched pages are
untrusted: they go only into the data section of the prompt, and the model's answer is parsed as
a closed schema with the quote checked against the page. The runtime is
`openbank-regulatory-obligations-watcher`; its README describes one run.

## Dedupe and cap

- **Dedupe.** Each finding has a stable fingerprint (finding kind, source URL, content SHA-256 —
  or the read outcome for an unreadable source — and the obligation ids). A fingerprint already
  filed, open or closed, is not filed again, so an unchanged `unverified` row produces one issue,
  not one per week. A changed source hash is a new finding.
- **Per-run cap.** At most 5 new issues per run. The rest are deferred, not summarised: their
  baseline does not advance, so the next run files them. A broker or credential fault therefore
  produces at most 5 issues a week, and the liveness gauge records no success for a run that read
  nothing.

## Owner steps before it can run

- Seed OpenBao: `keycloak/regulatory-obligations-watcher` (`client_secret`, also added to the
  realm import artifact per runbook 0009), `litellm` property `KEY_REGULATORY_OBLIGATIONS_WATCHER`
  (the agent's budgeted virtual key), and `regulatory-obligations-watcher` property `GITHUB_TOKEN`
  (fine-grained, issues:write on this repository only).
- Create the Keycloak client in the live realm (the template change applies only on cold start).
- Record the first eval run (`run-evals.py --record regulatory-obligations-watcher`).

## Known gaps

- Many sources are consolidated legal texts in HTML or PDF with no change feed. A change is a
  different content hash, which also fires on cosmetic page changes; the model's
  `NO_MATERIAL_CHANGE` absorbs those. PDF sources answer NOT_RENDERABLE while the broker's PDF
  extraction is off.
- The pod has no egress NetworkPolicy, like the other control-plane agents that write GitHub
  issues: GitHub is on the internet, and the public-web gate forbids internet egress from any
  agent-plane NetworkPolicy. The code reaches the web only through the broker.
- It proposes corrections and does not track whether anyone acted on them. An open, ignored
  proposal is covered by the governance follow-up rule (ADR-0052), not by this agent.
