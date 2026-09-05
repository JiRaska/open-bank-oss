---
id: card-scheme-bulletin-agent
plane: control
adr: ADR-0283
---

# card-scheme-bulletin-agent

## Mission

Read the card networks' periodic release bulletins, work out which mandates touch this platform,
and file one governance issue per mandate with its effective date and the capability it maps to.
It proposes an issue and nothing else: no port, policy, manifest or adapter is edited by it.

## Why this agent exists

Visa and Mastercard publish mandates on a fixed cadence with hard effective dates. Deciding which
of them apply to a given platform is a manual read of two long documents, and nobody's job covers
it — so the failure mode is not a wrong answer, it is no answer until the date passes and something
that used to work stops. This is the recurring, unglamorous cost of being a card issuer, and it is
exactly the shape a scheduled reader is good at: wide, repetitive, low-stakes to draft and
high-stakes to skip.

The mapping target is `openbank-libs/governance/card-capabilities.yaml` (ADR-0283 phase 2), so an
issue it files names a capability that exists in the registry rather than a free-text guess.

## Human oversight

- `any_mandate_interpretation` — deciding that a mandate applies to us is a judgement, and a wrong
  one applied automatically would change a control nobody reviewed. Every filed issue is a
  proposal for a human to confirm or reject.
- `any_port_or_adapter_change` — the agent never edits a port or an adapter. `tools.deny` blocks
  the whole write and execute tiers explicitly, not merely by omission.
- `runs_per_day: 1`, `tokens_per_run: 40000` — a weekly reader does not need more, and the cap
  stops its own running cost outgrowing the work it saves.

## Why it ships disabled

`enabled: false`, deliberately, and this is the part worth reading before turning it on.

**There is no wired bulletin feed yet.** Neither network publishes a machine-readable public feed;
the portal feeds need a developer account, which belongs to ADR-0283 phase 2's adapter work. A
charter that claims to run while its input does not exist produces the failure this repository has
been burnt by repeatedly: an agent that reports nothing is indistinguishable from an agent that
found nothing, and the silence reads as "no mandates apply".

So the charter lands with the capability registry it maps onto, and the flag flips in the PR that
wires a feed — in the same change, so "it runs" and "it can see anything" become true together.

## Known gaps

- No feed, as above. The agent's *scope* is defined and its *input* is not.
- Mandate text is prose, and the mapping from prose to a capability id is the whole judgement.
  Expect the first weeks of proposals to need correcting; the issue cites the bulletin reference
  and the mapped capability id precisely so a reviewer can check the mapping rather than re-read
  the bulletin.
- It files issues; it does not track whether anyone acted on them. A mandate whose issue is opened
  and ignored still passes its effective date. The governance follow-up rule (ADR-0052) is what
  covers that, not this agent.
