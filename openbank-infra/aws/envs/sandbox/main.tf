# ---------------------------------------------------------------------------
# RETIRED (2026-06-01, ADR-0053). This env used to provision two persistent
# self-hosted EC2 GitHub runners (arm64 t4g.xlarge + x86_64 t3.large). CI has
# moved to per-job *ephemeral* ARC runner scale sets on EKS+Karpenter spot
# (`openbank-build` / `openbank-deploy`, scaled to zero) — see ADR-0053 and
# envs/sandbox-platform/arc-runners.tf. The bespoke EC2 runners are redundant
# (workflows target runs-on: openbank-build) and were billing ~$25-30/mo idle.
#
# Both `module.runner` and `module.runner_x64` are removed here so `tofu apply`
# terminates the instances and tears down their reg-token SSM params / VPCs.
# This file is intentionally left with no resources; the state object
# (sandbox/runner.tfstate) is kept empty rather than deleted so the backend and
# history remain intact. Reinstate from git history if a persistent runner is
# ever needed again (ADR-0053 keeps Macs as opt-in accelerators meanwhile).
# ---------------------------------------------------------------------------
