---
date: 2026-10-29
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [security-ops, observability, infrastructure, gitops]
followup: "#14163 — layers 0-1, the manual and weekly drills and an armed HoneytokenFilter ship; regional EventBridge coverage, OpenBao/S3/Keycloak/DB lures, Slack/GoAlert delivery of the AWS signals and Stratus are open"
summary: "Deception is a layered programme on existing telemetry: edge decoys, then cloud and cluster honeytokens whose any use is an alert, each mapped to ATT&CK, gated as wired and powerless, and proven by a timed drill. Under 1 USD/month."
---

# ADR-0357 — Deception and detection-validation programme: edge decoys, honeytokens, timed drills

## Context

ADR-0279 (WS2, security observability) decided that honeytokens and honey endpoints run in the
sandbox wired to alerts, and shipped `HoneytokenFilter` plus the `HoneyEndpointHit` Loki rule.
This ADR is needed because that control is write-only as built, and because the first draft of
this ADR would have repeated the mistake one layer up.

Measured 2026-10-29 against `main` and the sandbox:

- `openbank.security.honey.paths` is configured in no service and no manifest, the
  `openbank_security_honeytoken_hits_total` series does not exist, and `HoneyEndpointHit`
  fired 0 times in 7 days. It could not have fired.
- A one-day sample of the edge access log shows only commodity reconnaissance (`.env`,
  `.git/HEAD`, WordPress and PHP artefacts, `actuator/heapdump`) from rotating sources. No
  targeted activity.
- GuardDuty raised 50 findings in 30 days. The only runtime findings (docker socket access, a
  new binary, a suspicious tool) all came from pods in `arc-runners`, which executes code from
  pull requests to a public repository.
- Falco, the in-cluster runtime sensor (ADR-0081), was crash-looping on 15 of 17 nodes after
  the 2026-10-12 node image moved to a kernel its driver cannot initialise on.

**Why an edge-only answer is not enough.** Decoy paths on a public host catch scanners, and
scanners are what every public host already sees. They do not distinguish a nuisance from an
intrusion. What does is the attacker's behaviour after initial access: reading credentials,
reusing tokens, enumerating the cluster and the cloud account. Mature deception practice puts
its lures there, where a legitimate user has no reason to be, so that any interaction is a
true positive and needs no tuning. A programme that stops at the edge reads as decoration to
anyone who has run one.

**Constraints.** The repository is public, so lure definitions are readable. The platform is a
sandbox under FinOps scrutiny, so the programme must ride on telemetry the account already
pays for. The 2026-09-29 decision keeps the edge open with no source allow-list.

## Decision

Deception is run as a layered programme. Each layer is mapped to the ATT&CK technique it
detects, wired to a signal source that already exists, guarded by a gate against the two silent
failure modes, and proven by a timed drill. The programme is only as good as its last drill.

### Layers

| Layer | Lure | ATT&CK | Signal source (already paid for) | Alert path | Status |
|---|---|---|---|---|---|
| 0 Edge | targeted decoy paths on the shared Gateway, answered by Envoy `directResponse` | T1595.003, T1190 | Envoy access log, Loki ruler | Alertmanager, Slack | shipped (#14164) |
| 0 Edge | commodity decoy paths (`/.env`, `/.git/HEAD`, `/actuator/*`) | T1595.003 | Envoy access log, recording rule | dashboard only | shipped (#14164) |
| 1 Cloud | canary IAM access key: explicit deny-all user, placed in an SSM lure | T1552.001, T1078.004 | CloudTrail via EventBridge, read-only events opted in | SNS email | this change |
| 1 Cluster | honey ServiceAccount token in `arc-runners`, zero RBAC | T1528, T1552.007 | EKS audit log, CloudWatch metric filter on ingest | SNS email | this change |
| 2 Data | honey party and IBAN rows; a payment or notification to them trips the outbox | T1530, T1005 | outbox and notification events | Alertmanager critical | planned |
| 2 Identity | honey Keycloak user; any login attempt | T1110, T1078 | Keycloak events in Loki | Alertmanager critical | planned |
| 2 Secrets | honey OpenBao path; honey object in the backup bucket | T1552, T1530 | OpenBao audit device, S3 data events | Alertmanager critical | planned |

Layer 0 is telemetry and context, not the headline. Layers 1 and 2 are where a hit means an
incident.

### Rules every lure follows

1. **Powerless by construction.** The canary IAM user carries one explicit `Deny "*"` and nothing
   else. The honey ServiceAccount has no binding anywhere and does not automount. A lure that
   could do anything would be a credential, not a lure.
2. **Wired to a tripwire on the lure's use, never on its being read.** The ARC controller reads
   Secrets in `arc-runners` about 41,000 times a day (measured in the EKS audit log), so an
   alarm on reads would be noise. The token's *use* has no legitimate source.
3. **Delivered outside the blast radius.** Layer 1 alerts go through SNS email, not through the
   cluster, because when the lure fires the cluster is the suspect. Alertmanager has no
   internet-facing receiver and gets none for this. Wiring the same topics into GoAlert is a
   follow-up.
4. **Values never in git.** The canary key exists in tofu state (encrypted) and in the SSM lure.
   The SA token is minted by the token controller in the cluster. A key committed to a public
   repository would be quarantined by AWS within minutes, which would silently disarm the lure.
5. **Gated.** `honeytoken-wiring` (enforced) fails when a honey SA has no metric filter naming
   it, when a filter names no honey SA, when any RoleBinding or ClusterRoleBinding has a honey
   SA as subject, when the canary user gains any attachment or loses its deny, or when the
   canary EventBridge rules stop opting in to read-only events. `edge-decoy-rule-parity`
   (enforced) does the same pairing for layer 0.

### Detection validation

`openbank-infra/scripts/deception-drill.sh` fires every tripwire once, on purpose, with a
user agent naming the drill, and reports seconds-to-signal per tripwire. It is the
known-positive for each control and the source of the mean-time-to-detect figure.

- Cadence: monthly, and after any change to the Gateway, the EKS logging config, the trail or
  the substrate root. A drill result older than 35 days means the programme is unverified.
- Promotion: a tripwire whose last drill passed is eligible for `critical` and paging.
- Next step (planned): Stratus Red Team for AWS and Kubernetes techniques, so validation covers
  techniques beyond our own lures, and a coverage matrix on the SOC dashboard.

### What it costs

| Item | Monthly |
|---|---|
| IAM user and key, SSM Standard parameter, EventBridge rules, SNS email | 0 USD |
| CloudWatch metric filter (evaluated on existing ingest) | 0 USD |
| CloudWatch standard alarm | 0.10 USD |
| Envoy decoy routes, Loki rules (existing pods and storage) | 0 USD |
| Drill run (a few API calls, a short metric query) | under 0.01 USD |
| **Total** | **about 0.10 USD** |

Nothing new runs: no pod, no load balancer, no instance, no added log ingestion.

## Alternatives considered

- **Per-service honey paths (the ADR-0279 shape as built).** These sit inside the JAX-RS chain, so
  they see only paths routed to a service that opted in, and no service ever did. Kept as a
  second layer, and armed in slice 2 (below), because it sees what Envoy cannot.
- **Commercial canary platform.** The category leader is the
  benchmark for the "any interaction is a true positive" design this ADR copies. Hardware or
  virtual canaries are a recurring licence and a third-party data processor. The same tripwires
  are buildable from CloudTrail, EKS audit, Envoy and Loki at near-zero cost, with lures that
  match this estate exactly. Rejected on cost and data-processing grounds, not on merit.
- **Free hosted canary-token service.** Zero cost and quick, but alerts are processed
  by a third party, and the AWS token type is generated in that party's account, not ours.
  Rejected; the self-hosted canary key gives the same signal with full control.
- **Interactive honeypot distribution on its own instance.** Rich protocol emulation that
  reports what GuardDuty and the edge log already show, for a recurring instance, patching, and a
  new exposed host outside GitOps and admission policy. Rejected.
- **Blocking WAF on every host.** AWS WAF does not attach to an NLB, and an ALB in front would break
  the client-address model the per-client rate limits rely on (ADR-0324). Coraza in detection-only
  mode remains a candidate telemetry source for the real routes, tracked in #14163.
- **Alarm on Secret reads instead of token use.** Measured noise: ~41,000 reads a day by the ARC
  controller. Rejected in favour of keying on use.

## Consequences

**Positive**
- Every layer 1 or 2 hit is a true positive by construction, so these alerts can page without the
  tuning cycle the rest of the estate's alerts need.
- The two silent failure modes, unwired and armed, are build failures instead of surprises.
- Detection claims carry a measured, dated seconds-to-signal figure.
- About 0.10 USD a month.

**Negative**
- EventBridge is regional. The canary rules cover eu-north-1 and us-east-1, which includes the
  global STS endpoint and IAM. A call through another region's regional STS endpoint is in the
  trail but alerts nothing until per-region rules land (free, tracked in #14163). Stated here so
  it is not mistaken for full coverage.
- Lure placement is public. A reader of this repository knows the SSM path and the SA name.
  They are tripwires either way: using them is the alert. Placements that rely on secrecy belong
  in OpenBao, not in git.
- The drill sends two real alert emails per run. That is intended.
- Runtime sensing is degraded until Falco runs on the current kernel. Honeytokens do not replace
  a runtime sensor; they bound how long an intrusion stays quiet.

**Neutral**
- Gates: `honeytoken-wiring` and `edge-decoy-rule-parity`, both group `gitops`, mode `enforced`.
- Applying layer 1 is an owner step (`tofu apply` in `aws/envs/sandbox-substrate`, targeted to
  `deception.tf`'s resources; the plan is 16 to add, 0 to change, 0 to destroy) plus confirming
  the two SNS email subscriptions.

### Slice 2 (2026-10-31): HoneytokenFilter armed, paging and cadence measured

**HoneytokenFilter could never fire, in any service.** It was a post-matching
`ContainerRequestFilter`, and JAX-RS calls those only after a resource method has matched; a
honey path has none, so RESTEasy answered its own routing 404 and the filter was skipped. Shown
by `HoneyEndpointIT` in customer-edge: with the paths configured and the filter post-matching,
the hit counter stayed at 0.0. It is now `@PreMatching`, and it no longer answers the request
itself: it counts, logs and lets the platform's own `NOT_FOUND` 404 answer. The old empty-bodied
404 differed from every real 404, which told a prober the path was special. customer-edge sets
two honey paths under its public `/customer/v1` prefix. That is a signal the edge decoys cannot
give: the request is seen inside the service's namespace whether it came through Envoy or from an
in-cluster caller that skipped it. `honeytoken-wiring` now fails if `HoneyEndpointHit` exists
while no workload sets `OPENBANK_SECURITY_HONEY_PATHS`, or if the filter loses `@PreMatching`.

**Paging stays email (owner decision 2026-10-31).** GoAlert is cluster-internal (ADR-0056) and
an SNS HTTPS subscription needs a public endpoint, so the honeytoken topics reach a human by
email. The estate's existing Slack path is Loki/Grafana -> Alertmanager, and it cannot see
CloudTrail or CloudWatch: no CloudTrail data is ingested into Loki and Grafana has no CloudWatch
datasource. Bridging them would be a new component (a managed chat integration, a function, an
exporter pod or new log ingestion), and the rule for this programme is to reuse what already
runs and add a component only when it is free or lowers cost, after a written evaluation and the
owner's approval. A managed chat integration was built and withdrawn on that rule: it duplicated
the Slack path and saved nothing. The in-cluster signals (`EdgeDecoyTargetedHit`,
`HoneyEndpointHit`) already reach Slack through that path and stay `warning` until a drill hit
has been seen there end to end.

**Scheduled drill (owner decision 2026-10-31).** `.github/workflows/deception-drill.yml` runs
weekly and on dispatch, never on a pull request, on a GitHub-hosted runner. It assumes
`openbank-deception-drill`, trusted only for that file on main (`sub` and `job_workflow_ref`,
both `StringEquals`; hosted runners always carry the claim, so no `IfExists`). The role can read
the one SSM lure (decrypt only via SSM) and `GetMetricStatistics` in `AWS/Events` and
`AWS/SNS`, nothing else. The drill fires the canary key and fails, posting on #14163, unless
EventBridge matched it and the topic delivered it within ten minutes. The honey ServiceAccount
and the Loki half of the decoy need cluster access this role deliberately lacks; they print
`SKIPPED`, never `PASS`, and stay in the monthly manual drill. `deception-drill-selftest.sh`
proves on the runner itself that silence exits 1 and a signal exits 0. Stratus Red Team is not
run: every technique that adds coverage beyond our own lures needs rights to create or change
AWS resources, which a lure-reading role must not hold. `honeytoken-wiring` fails if the trust
loses either pin, gains `IfExists` or a wider `sub`, or if the workflow gains any other trigger.

### Delivery check

```bash
# both gates green, i.e. every lure is wired and powerless
python3 .github/scripts/check-honeytoken-wiring.py
python3 .github/scripts/check-edge-decoy-rule-parity.py
# the honey SA and its token exist and carry no binding
kubectl -n arc-runners get sa deploy-prod && kubectl -n arc-runners get secret deploy-prod-token
kubectl auth can-i --list --as=system:serviceaccount:arc-runners:deploy-prod -n arc-runners   # only self-review rules
# end-to-end known-positive with timings; every row PASS
AWS_PROFILE=openbank openbank-infra/scripts/deception-drill.sh --loki http://localhost:3100
```

If any drill row fails, or the last passing run is older than 35 days, the programme is not
delivered, whatever the status field says.

## Compliance impact

- PCI DSS: not applicable. The sandbox holds no cardholder data, and the lures carry none.
- DORA: strengthens ICT-related incident detection and testing evidence, in the sense ADR-0279 already claims for its security observability work stream. No new obligation is asserted.
- GDPR: not applicable. The tripwires record caller address and user agent, which the access log and audit log already record.
- PSD2: not applicable.
- CNB: not applicable.

## References

- #14163: tracking issue, measurements, follow-ups
- #14164: layer 0, edge decoys and the parity gate
- ADR-0279: security roadmap, WS2 honey endpoints
- ADR-0324: Envoy Gateway as the shared edge
- ADR-0081: runtime sensor baseline (Falco)
- `openbank-infra/aws/envs/sandbox-substrate/deception.tf`
- `openbank-infra/gitops/components/platform/honey-serviceaccount.yaml`
- `openbank-infra/scripts/deception-drill.sh`
- `.github/scripts/check-honeytoken-wiring.py`, `.github/scripts/check-edge-decoy-rule-parity.py`
