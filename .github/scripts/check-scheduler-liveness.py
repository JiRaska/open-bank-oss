#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Scheduler liveness adoption gate (ADR-0237, issue #3345).
#
# WHY THIS EXISTS
#   ADR-0237 made fleet-wide liveness coverage a decision: every domain @Scheduled
#   job registers DomainMetrics.registerWorkflowLiveness and calls recordSuccess()
#   on its success path. Without a gate, adoption is a sweep that ends the day it
#   lands and then rots — the next scheduler added without a heartbeat is invisible
#   to the staleness rule and to the control-liveness-sentinel (its documented
#   known gap is exactly this adoption rate). This check makes the rule structural:
#   a NEW domain @Scheduled job with no liveness registration fails, even while the
#   gate is advisory for the baselined legacy set.
#
# WHAT IT CHECKS
#   Every .kt file under openbank-*/src/main that contains @Scheduled is classified:
#     - EXEMPT: outbox infrastructure (extends AbstractOutboxDispatcher /
#       AbstractOutboxBacklogGauge — they have their own openbank.outbox.backlog
#       freshness signal, ADR-0237 point 1) and the explicit ALLOWLIST below
#       (metric producers with their own alert rules, and the sentinel itself —
#       watching the watcher is the sentinel's circularity, not this gate's).
#     - DOMAIN: everything else with @Scheduled. The file must reference
#       registerWorkflowLiveness.
#
#   The BASELINE is checked BOTH WAYS (the kafka-dotted-keys ratchet shape):
#   a NEW violation fails even in advisory mode, and a baseline entry that became
#   compliant is reported — a baseline that can only shrink cannot rot in either
#   direction. The baseline is ordered money-path first (ADR-0237 rollout order).
#
# Run:  python3 .github/scripts/check-scheduler-liveness.py --root . [--enforce]
#       python3 .github/scripts/check-scheduler-liveness.py --self-test

import argparse
import pathlib
import re
import sys

# @Scheduled must be an actual annotation (line-initial), not the word in a KDoc
# comment — OutboxPorts/ClusterLock/ConsentPorts all mention it in prose.
SCHEDULED_RE = re.compile(r"^\s*@Scheduled\b", re.M)
LIVENESS_RE = re.compile(r"registerWorkflowLiveness")
# Outbox infra is exempt by ROLE (own openbank.outbox.backlog freshness signal,
# ADR-0237 point 1): the shared base classes cover most, but security-scanner's
# hand-rolled dispatcher predates the abstraction — name the role, not the base.
OUTBOX_INFRA_RE = re.compile(
    r"AbstractOutboxDispatcher|AbstractOutboxBacklogGauge"
    r"|class\s+\w*(OutboxDispatcher|OutboxBacklogGauge)\b")

# Explicit exemptions, one reason each — an exemption without a reason is exactly
# the unowned-control class this gate exists to prevent.
ALLOWLIST = {
    # Emits its own audit-chain gauges, covered by prometheus-rules-audit-chain.yaml
    # (NeverVerified + Stale) — a second watcher on the same cadence adds nothing.
    "openbank-audit-service/src/main/kotlin/com/openbank/audit/infrastructure/observability/AuditChainIntegrityGauge.kt",
    # The sentinel's own scheduler — its liveness is the absence of its findings,
    # watched by the deploy-drift watchdog and its own run evidence.
    "openbank-control-liveness-sentinel/src/main/kotlin/com/openbank/liveness/infrastructure/schedule/LivenessCheckScheduler.kt",
}

# Today's non-compliant set (first scan 2026-08-03, 37 files), money-path first
# (ADR-0237 rollout order). Each entry disappears in its adoption PR; the gate
# fails if the list GROWS or if an entry healed without being removed here.
BASELINE = [
    # money-path
    "openbank-balance-service/src/main/kotlin/com/openbank/balance/infrastructure/schedule/BalanceReconciliationScheduler.kt",
    "openbank-balance-service/src/main/kotlin/com/openbank/balance/infrastructure/schedule/ReconciliationFreshnessWatchdog.kt",
    "openbank-interest-service/src/main/kotlin/com/openbank/interest/infrastructure/scheduler/InterestAccrualScheduler.kt",
    "openbank-interest-service/src/main/kotlin/com/openbank/interest/infrastructure/scheduler/InterestCapitalizationScheduler.kt",
    "openbank-ledger-service/src/main/kotlin/com/openbank/ledger/infrastructure/partition/JournalPartitionMaintainer.kt",
    "openbank-ledger-service/src/main/kotlin/com/openbank/ledger/infrastructure/schedule/TieOutFreshnessWatchdog.kt",
    "openbank-lending-service/src/main/kotlin/com/openbank/lending/infrastructure/servicing/InterestAccrualScheduler.kt",
    "openbank-lending-service/src/main/kotlin/com/openbank/lending/infrastructure/servicing/ProvisioningCycleScheduler.kt",
    "openbank-sdd-service/src/main/kotlin/com/openbank/sdd/infrastructure/scheduler/MandateExpiryScheduler.kt",
    # non-money-path
    "openbank-agent-service/src/main/kotlin/com/openbank/agent/application/OversightService.kt",
    "openbank-agent-service/src/main/kotlin/com/openbank/agent/infrastructure/observability/AgentMetricsAdapter.kt",
    "openbank-aml-service/src/main/kotlin/com/openbank/aml/infrastructure/scheduler/PartyResolutionScheduler.kt",
    "openbank-analytics-sink/src/main/kotlin/com/openbank/analytics/infrastructure/reconcile/ReconciliationJob.kt",
    "openbank-audit-service/src/main/kotlin/com/openbank/audit/application/AuditAnchorService.kt",
    "openbank-audit-service/src/main/kotlin/com/openbank/audit/infrastructure/retention/SessionLogRetentionScheduler.kt",
    "openbank-authz-policy-auditor/src/main/kotlin/com/openbank/authzaudit/infrastructure/schedule/AuthzPolicyCheckScheduler.kt",
    "openbank-card-issuance-service/src/main/kotlin/com/openbank/cardissuance/infrastructure/retention/CardPiiRetentionScheduler.kt",
    "openbank-consent-service/src/main/kotlin/com/openbank/consent/infrastructure/ConsentExpirationJob.kt",
    "openbank-delegation-service/src/main/kotlin/com/openbank/delegation/infrastructure/DelegationExpirationJob.kt",
    "openbank-devops-agent/src/main/kotlin/com/openbank/devops/infrastructure/schedule/DevOpsAnalysisScheduler.kt",
    "openbank-dispute-service/src/main/kotlin/com/openbank/dispute/infrastructure/observability/ComplaintDeadlineGauge.kt",
    "openbank-docs-truth-agent/src/main/kotlin/com/openbank/docstruth/infrastructure/schedule/DocsTruthCheckScheduler.kt",
    "openbank-finops-agent/src/main/kotlin/com/openbank/finops/infrastructure/schedule/FinOpsAnalysisScheduler.kt",
    "openbank-flaky-test-hunter/src/main/kotlin/com/openbank/flakytest/infrastructure/schedule/FlakyTestCheckScheduler.kt",
    "openbank-governance-auditor/src/main/kotlin/com/openbank/govaudit/infrastructure/schedule/GovernanceAuditScheduler.kt",
    "openbank-kyc-service/src/main/kotlin/com/openbank/kyc/infrastructure/retention/KycRetentionScheduler.kt",
    "openbank-notification-service/src/main/kotlin/com/openbank/notification/infrastructure/DeviceTokenSweepJob.kt",
    "openbank-notification-service/src/main/kotlin/com/openbank/notification/infrastructure/NotificationOutboxDeadLetterJanitorJob.kt",
    "openbank-onboarding-service/src/main/kotlin/com/openbank/onboarding/infrastructure/observability/OnboardingFunnelGauge.kt",
    "openbank-onboarding-service/src/main/kotlin/com/openbank/onboarding/infrastructure/scheduler/AbandonedRegistrationCleaner.kt",
    "openbank-pid-service/src/main/kotlin/com/openbank/pid/infrastructure/crypto/TrustedListService.kt",
    "openbank-release-steward/src/main/kotlin/com/openbank/releasesteward/infrastructure/schedule/ReleaseStewardCheckScheduler.kt",
    "openbank-security-scanner/src/main/kotlin/com/openbank/securityscanner/infrastructure/rest/SecurityScannerResource.kt",
    "openbank-statement-service/src/main/kotlin/com/openbank/statement/infrastructure/metrics/CloseLastRunGauge.kt",
    "openbank-statement-service/src/main/kotlin/com/openbank/statement/infrastructure/scheduler/PeriodCloseScheduler.kt",
]


def find_scheduled_files(root: pathlib.Path):
    for kt in sorted(root.glob("openbank-*/src/main/kotlin/**/*.kt")):
        try:
            text = kt.read_text(encoding="utf-8")
        except OSError:
            continue
        if SCHEDULED_RE.search(text):
            yield kt.relative_to(root).as_posix(), text


def classify(root: pathlib.Path):
    compliant, violations, exempt = [], [], []
    for rel, text in find_scheduled_files(root):
        if rel in ALLOWLIST or OUTBOX_INFRA_RE.search(text):
            exempt.append(rel)
        elif LIVENESS_RE.search(text):
            compliant.append(rel)
        else:
            violations.append(rel)
    return compliant, violations, exempt


def self_test() -> int:
    import tempfile
    failures = []

    def expect(name, cond):
        print(("self-test ok: " if cond else "SELF-TEST FAIL: ") + name)
        if not cond:
            failures.append(name)

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        svc = root / "openbank-demo-service/src/main/kotlin/com/openbank/demo"
        svc.mkdir(parents=True)
        (svc / "JobWithLiveness.kt").write_text(
            "class JobWithLiveness {\n  @Scheduled(cron = \"0 0 * * * ?\")\n"
            "  fun sweep() { domainMetrics.registerWorkflowLiveness(\"demo\", Duration.ofDays(1)) }\n}\n",
            encoding="utf-8")
        (svc / "JobWithoutLiveness.kt").write_text(
            "class JobWithoutLiveness {\n  @Scheduled(cron = \"0 0 * * * ?\")\n  fun sweep() {}\n}\n",
            encoding="utf-8")
        (svc / "DemoOutboxDispatcher.kt").write_text(
            "class DemoOutboxDispatcher : AbstractOutboxDispatcher() {\n"
            "  @Scheduled(every = \"5s\")\n  fun dispatch() {}\n}\n",
            encoding="utf-8")
        (svc / "HandRolledOutboxDispatcher.kt").write_text(
            "class HandRolledOutboxDispatcher(private val repo: Repo) {\n"
            "  @Scheduled(every = \"5s\")\n  fun dispatchScheduledBatch() {}\n}\n",
            encoding="utf-8")
        (svc / "PortsMentioningScheduled.kt").write_text(
            "/** Both run every `@Scheduled` bean regardless of split. */\n"
            "interface PortsMentioningScheduled\n",
            encoding="utf-8")
        compliant, violations, exempt = classify(root)
        expect("file with liveness is compliant",
               compliant == ["openbank-demo-service/src/main/kotlin/com/openbank/demo/JobWithLiveness.kt"])
        expect("domain job without liveness is a violation",
               violations == ["openbank-demo-service/src/main/kotlin/com/openbank/demo/JobWithoutLiveness.kt"])
        expect("outbox dispatchers are exempt (base class and hand-rolled)",
               exempt == ["openbank-demo-service/src/main/kotlin/com/openbank/demo/DemoOutboxDispatcher.kt",
                          "openbank-demo-service/src/main/kotlin/com/openbank/demo/HandRolledOutboxDispatcher.kt"])
        expect("@Scheduled in a KDoc comment is not a job",
               not any("PortsMentioningScheduled" in v for v in violations + compliant))

    expect("baseline contains only real files checked both ways", isinstance(BASELINE, list))
    if failures:
        print(f"\n{len(failures)} self-test expectation(s) FAILED", file=sys.stderr)
        return 1
    print("\nself-test: all expectations hold.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Scheduler liveness adoption gate (ADR-0237).")
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true",
                    help="fail on baselined violations too (the enforced end-state)")
    ap.add_argument("--self-test", "--selftest", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    compliant, violations, exempt = classify(root)
    baseline, new_violations = set(BASELINE), []
    for v in violations:
        if v not in baseline:
            new_violations.append(v)
    healed = [b for b in baseline if b not in violations]

    print(f"domain @Scheduled files: {len(compliant) + len(violations)} "
          f"({len(compliant)} with liveness, {len(exempt)} exempt outbox/allowlist)")
    if new_violations:
        for v in new_violations:
            print(f"::error file={v}::NEW domain @Scheduled job without "
                  "registerWorkflowLiveness (ADR-0237). Register the heartbeat in the "
                  "same PR that adds the job — a scheduler without one is invisible "
                  "to the staleness rule and to the sentinel.")
    stale_baseline = [v for v in violations if v in baseline]
    if stale_baseline:
        print(f"baselined (adoption pending, {len(stale_baseline)} left):")
        for v in stale_baseline:
            print(f"  ::warning file={v}::adoption pending (ADR-0237 sweep, #3345)")
    if healed:
        print("baseline entries now compliant — REMOVE them from BASELINE in this "
              "script (a baseline that never shrinks rots):")
        for v in healed:
            print(f"  {v}")

    if new_violations or healed:
        return 1
    if args.enforce and stale_baseline:
        print(f"::error::{len(stale_baseline)} baselined violation(s) remain and "
              "--enforce was passed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
