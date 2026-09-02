# openbank-tax-reporting-service — agent notes

## This service is NOT DEPLOYED, and that is deliberate

`grep -rn "tax-reporting" openbank-infra/gitops/` returns **zero hits**. There is no Deployment,
no Rollout, no KafkaUser, no auto-deploy entry and no ECR repository. This is known and recorded
outside this file too — `openbank-infra/aws/envs/sandbox-platform/ecr-service-repositories.tf`
explains why the ECR list is not derived from release-please's package list and names this module
as "a released component with no gitops workload, no auto-deploy entry and — correctly — no
repository".

It is nevertheless a **released component**: it has a `version.txt`, is registered in
`release-please-config.json` + `.release-please-manifest.json`, and accrues version bumps and
changelog entries for a workload nobody runs.

### What follows from that, so nobody re-derives it a fourth time

- **Nothing here has ever executed in an environment.** The §38d withholding consumer, the ports,
  the schema and the migrations are real code with no runtime. `db/migration` has never been
  applied anywhere, so — unusually for this repo — the Flyway "never edit an applied migration"
  rule does not bind yet.
- **The production-readiness matrix reports this service as `NOT-DEPLOYED`, not NO-GO** (#5706).
  Its C5 ("no CNPG cluster"), C7 (no NetworkPolicy) and C8 ("not deployed") are consequences of
  the absent workload rather than controls anyone skipped, and **none of them can be closed by a
  repo change**. Do not try to raise them.
- **C3 has no contract test and cannot honestly get one.** Nothing in the repo consumes this
  service's HTTP API and it ships no outbound rest-client, so there is no consumer whose
  expectations a pact could encode. Authoring one against an invented consumer would be a scoring
  artifact of exactly the kind the C3 probe was repaired to reject.
- **The absent KafkaUser is a consequence, not an independent gap** — that framing was corrected
  in #5760. `check-incoming-dlq-wiring.py` carries a baseline entry for
  `openbank.dlq.tax-reporting.withholding-remitted-in` for the same reason (#5751), and it comes
  off the day this deploys.
- **`docs/runbooks/svc-tax-reporting.md` is generated and carries a NOT DEPLOYED banner.** Every
  `kubectl` line in it names a namespace that does not exist. Never hand-edit it (ADR-0029 rule
  #6); the banner comes from `generate-service-runbooks.py` and disappears on its own once a
  workload exists.

### The open decision (#5760) — an owner's, not an agent's

Whether this service should be deployed at all. If yes, it needs the full gitops set: workload,
KafkaUser with Read on its source topic and Write on its DLQ, auto-deploy entry, ECR repository —
and the DLQ baseline entry comes off. If no, it should be said out loud here and the release
registration reconsidered, because a component that releases forever and runs never is a standing
source of exactly this confusion.
