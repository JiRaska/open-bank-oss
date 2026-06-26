# ─────────────────────────────────────────────────────────────────────────────
# ECR scan-on-push (registry level)
# ─────────────────────────────────────────────────────────────────────────────
# Defense-in-depth pair to the CI-side trivy image scan in auto-deploy.yml:
# the CI scan gates a deploy at push time; ECR basic scanning re-evaluates the
# stored image whenever AWS refreshes its CVE database, so an image that was
# clean on push but acquires a CRITICAL a month later still surfaces (the
# weekly finops/vuln audits read these findings — vuln_management SLAs in
# rules.yaml apply from detection, ADR-0079).
#
# Why registry-level and not per-repository scan_on_push: the openbank-*
# service repositories are created on first push by the deploy pipeline, not
# owned by OpenTofu — a registry-level wildcard rule covers repos that do not
# exist yet, with no per-repo resource to forget. BASIC (free) not ENHANCED
# (Inspector, paid): the sandbox FinOps posture (ADR-0054) — flip to ENHANCED
# with a continuous-scan rule when a prod account exists.
#
# This is an account-level singleton: ownership lives here in sandbox-platform
# next to the other ECR concerns (mirror, kyverno verify IAM).

resource "aws_ecr_registry_scanning_configuration" "this" {
  scan_type = "BASIC"

  rule {
    scan_frequency = "SCAN_ON_PUSH"
    repository_filter {
      filter      = "openbank-*"
      filter_type = "WILDCARD"
    }
  }
}
