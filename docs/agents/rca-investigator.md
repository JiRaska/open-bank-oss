---
id: rca-investigator
plane: control
adr: ADR-0088
---

# rca-investigator

## Mission

HolmesGPT-based root-cause investigator. On a firing alert — or an operator's free-text description
of one, via the "Holmes RCA" panel on `/iaops` — it pulls Prometheus metrics, logs and Kubernetes
cluster state, then proposes a probable root cause. It is read-only end to end: it triggers nothing,
changes nothing, and its output is a finding for a human or on-call engineer to act on.

## Why this agent exists

Root-cause investigation during an incident is exactly the kind of work that benefits from an
always-available first pass — gathering metrics and cluster state, correlating them, and proposing a
hypothesis — while the actual remediation stays entirely with the human on-call. It shortens the
"what do I even look at first" phase of an incident without touching the "what do I do about it"
phase at all.

## Human oversight

`requires_human: every: proposal` — the investigation is a finding, not an action. There is nothing
in this charter's tool-allow list that can change state (`money.*`, `gh.pr.*`, any `*.write` tool,
and raw secret reads are all explicitly denied). The model backing it is currently NVIDIA NIM
`meta/llama-3.1-8b-instruct`; a typical investigation takes 30-60 seconds and the result is not
persisted — copy anything worth keeping into the actual incident record.

## Known gaps

- Investigation results aren't stored anywhere the way a proposal is — there's no history of past
  RCA runs to look back on. If that turns out to matter operationally, it's a candidate to route
  through the same proposal queue as the other control-plane agents rather than staying a one-shot
  panel.
