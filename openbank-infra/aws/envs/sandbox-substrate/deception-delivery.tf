# Deception programme, slice 2: an identity for the scheduled drill (ADR-0357).
#
# Reuses the GitHub Actions OIDC provider (ci-tofu-apply.tf); adds one IAM role and one inline
# policy, both free. No new service. Delivery of the honeytoken alerts stays SNS email: the
# cluster's Loki/Grafana -> Slack path cannot see CloudTrail or CloudWatch, and adding a path
# would be a new component (owner decision 2026-10-31: reuse what already runs).

# ---------------------------------------------------------------------------------------------
# Scheduled drill identity. Assumable only by .github/workflows/deception-drill.yml on main,
# from a GitHub-HOSTED runner (which always carries job_workflow_ref, so the pin is a plain
# StringEquals — never IfExists). It can read exactly one lure and the two signals that prove the
# tripwire fired, nothing else: no Kubernetes, no other parameter, no write anywhere.
#
# What this identity deliberately CANNOT do, and so what the scheduled drill does not cover:
# the honey ServiceAccount token (reading it needs Kubernetes access to arc-runners, which would
# make this role a credential for that lure too) and the Loki half of the edge decoy (Loki has no
# public ingress). Those stay in the monthly manual drill.
# ---------------------------------------------------------------------------------------------

locals {
  deception_drill_workflow_ref = "JiRaska/open-bank-oss/.github/workflows/deception-drill.yml@refs/heads/main"
}

data "aws_iam_policy_document" "deception_drill_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github_actions.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_substrate_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_substrate_oidc_host}:sub"
      values   = ["repo:${local.ci_substrate_github_repo}:ref:refs/heads/main"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.ci_substrate_oidc_host}:job_workflow_ref"
      values   = [local.deception_drill_workflow_ref]
    }
  }
}

data "aws_iam_policy_document" "deception_drill" {
  statement {
    sid       = "ReadTheCanaryLureOnly"
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.canary_lure.arn]
  }
  statement {
    sid       = "DecryptViaSsmOnly"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }
  # GetMetricStatistics has no resource-level permission; namespace-scoped by condition.
  statement {
    sid       = "ReadTripwireMetrics"
    actions   = ["cloudwatch:GetMetricStatistics"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["AWS/Events", "AWS/SNS"]
    }
  }
}

resource "aws_iam_role" "deception_drill" {
  name                 = "openbank-deception-drill"
  assume_role_policy   = data.aws_iam_policy_document.deception_drill_assume.json
  max_session_duration = 3600
  tags                 = local.deception_tags
}

resource "aws_iam_role_policy" "deception_drill" {
  name   = "deception-drill"
  role   = aws_iam_role.deception_drill.name
  policy = data.aws_iam_policy_document.deception_drill.json
}

output "deception_drill_role_arn" {
  description = "Role the scheduled deception drill assumes (set as repo variable DECEPTION_DRILL_ROLE_ARN)."
  value       = aws_iam_role.deception_drill.arn
}
