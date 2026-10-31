# Deception programme, slice 2: delivery to Slack and an identity for the scheduled drill
# (ADR-0357).
#
# SLACK, WITHOUT INGRESS OR CODE. GoAlert is cluster-internal (ADR-0056) and an SNS HTTPS
# subscription needs a public endpoint, so the honeytoken topics reach a human by email only.
# AWS Chatbot subscribes to the same topics from AWS's side and posts into Slack: no Lambda, no
# webhook secret, nothing listening on the internet. It renders CloudWatch alarms natively and
# anything else only in its "custom notification" JSON shape, which is why deception.tf's
# EventBridge input template emits that shape.
#
# LEAST PRIVILEGE. Chatbot's channel role carries no policy at all (notifications need none),
# and the guardrail caps whatever anyone types in the channel at read-only CloudWatch. Without
# an explicit guardrail the service applies AdministratorAccess.
#
# ONE-TIME OWNER STEP. The Slack workspace must be authorised in the Chatbot console (an OAuth
# consent in the browser) before this resource can be created; the first apply fails otherwise.
# Workspace and channel ids are variables with no default and never enter git.

locals {
  deception_slack_enabled = var.deception_slack_team_id != null && var.deception_slack_channel_id != null
}

data "aws_iam_policy_document" "chatbot_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["chatbot.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "deception_chatbot" {
  count              = local.deception_slack_enabled ? 1 : 0
  name               = "openbank-deception-chatbot"
  assume_role_policy = data.aws_iam_policy_document.chatbot_assume.json
  tags               = local.deception_tags
}

resource "aws_chatbot_slack_channel_configuration" "deception" {
  count                 = local.deception_slack_enabled ? 1 : 0
  provider              = aws.chatbot
  configuration_name    = "openbank-honeytoken-alerts"
  iam_role_arn          = aws_iam_role.deception_chatbot[0].arn
  slack_team_id         = var.deception_slack_team_id
  slack_channel_id      = var.deception_slack_channel_id
  sns_topic_arns        = [aws_sns_topic.honeytoken.arn, aws_sns_topic.honeytoken_us_east_1.arn]
  guardrail_policy_arns = ["arn:${data.aws_partition.current.partition}:iam::aws:policy/CloudWatchReadOnlyAccess"]
  logging_level         = "ERROR"
  tags                  = local.deception_tags
}

# Chatbot subscribes itself to the topics, so the topic policies must let it.
data "aws_iam_policy_document" "honeytoken_topic_chatbot" {
  for_each = toset(["eu-north-1", "us-east-1"])

  source_policy_documents = [data.aws_iam_policy_document.honeytoken_topic[each.key].json]
  statement {
    sid       = "ChatbotSubscribe"
    actions   = ["sns:Subscribe", "sns:Receive"]
    resources = ["arn:${data.aws_partition.current.partition}:sns:${each.key}:${data.aws_caller_identity.current.account_id}:openbank-honeytoken-alerts"]
    principals {
      type        = "Service"
      identifiers = ["chatbot.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

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
