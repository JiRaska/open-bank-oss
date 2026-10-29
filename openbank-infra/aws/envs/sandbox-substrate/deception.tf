# Deception programme, phase 1: cloud and cluster honeytokens (ADR-0357).
#
# WHY. Edge decoys (gitops observability/httproute-edge-decoys.yaml) see scanners, which every
# public host sees. What separates a nuisance from an incident is what happens AFTER initial
# access: credential theft, token reuse, discovery. A honeytoken is a credential that nothing
# legitimate ever uses, so any use is a true positive by construction and needs no tuning. This
# file builds the two cloud-side tripwires and their delivery; the cluster-side lure (a
# zero-RBAC ServiceAccount token) lives in gitops/components/arc-runners/honey-serviceaccount.yaml.
#
#   Tripwire                     ATT&CK                         Signal source             Fires on
#   canary IAM access key        T1552.001, T1078.004           CloudTrail -> EventBridge any API call signed by the key
#   honey SA token used          T1528, T1552.007               EKS audit log -> metric   any request authenticated as the SA
#
# FINOPS. IAM user, access key, SSM Standard parameter, EventBridge rules and SNS email are
# free; one CloudWatch metric filter is free; one standard alarm is 0.10 USD/month. Total
# ~0.10 USD/month. No compute, no log ingestion added: the EKS audit log and the multi-region
# trail already exist (modules/eks, modules/audit-baseline).
#
# WHY READ-ONLY EVENTS ARE OPTED IN. The first thing anyone does with a found key is
# `aws sts get-caller-identity`, a read-only call. EventBridge delivers read-only CloudTrail
# management events only to rules in state ENABLED_WITH_ALL_CLOUDTRAIL_MANAGEMENT_EVENTS
# (docs: eb-service-event-cloudtrail-management). A plain ENABLED rule would see only the
# attacker's SECOND, mutating step, which on a zero-permission user never succeeds and may
# never be attempted.
#
# REGIONAL COVERAGE, STATED HONESTLY. EventBridge is regional. Rules exist in eu-north-1 (our
# region) and us-east-1 (the global STS endpoint, IAM, and the default region of most tooling).
# A call made through another region's regional STS endpoint is recorded by the multi-region
# trail but alerts nothing. Closing that needs a rule per enabled region (still free, ~15
# provider aliases); tracked in #14163, not hidden.
#
# THE KEY NEVER ENTERS GIT. It exists in tofu state (encrypted S3, see versions.tf) and in the
# SSM parameter below, which is the first lure: a plausible "legacy backup replication"
# credential where an attacker with read on SSM would look. Further placements (a runner
# Secret via OpenBao, a decoy object in a backup bucket) are follow-ups in #14163.

data "aws_caller_identity" "current" {}

locals {
  # Plausible, boring, and distinct from every real principal in the account.
  canary_user_name = "svc-backup-replication"
  deception_tags = {
    Purpose = "deception-honeytoken"
    Adr     = "0357"
  }
}

resource "aws_iam_user" "canary" {
  name          = local.canary_user_name
  path          = "/service/"
  force_destroy = true
  tags          = local.deception_tags
}

# Explicit deny of everything, so the key can never become useful even if a future policy
# attachment or group membership is added by mistake. sts:GetCallerIdentity cannot be denied
# by IAM and still lands in CloudTrail, which is exactly the call this tripwire wants.
resource "aws_iam_user_policy" "canary_deny_all" {
  name = "deny-all"
  user = aws_iam_user.canary.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "HoneytokenDenyAll"
      Effect   = "Deny"
      Action   = "*"
      Resource = "*"
    }]
  })
}

resource "aws_iam_access_key" "canary" {
  user = aws_iam_user.canary.name
}

resource "aws_ssm_parameter" "canary_lure" {
  name        = "/openbank/sandbox/backup/replication-credentials"
  description = "Legacy backup replication credentials (S3 cross-region)."
  type        = "SecureString"
  tier        = "Standard"
  value = jsonencode({
    aws_access_key_id     = aws_iam_access_key.canary.id
    aws_secret_access_key = aws_iam_access_key.canary.secret
    region                = "eu-north-1"
    bucket                = "openbank-backup-replica"
  })
  tags = local.deception_tags
}

# ---------------------------------------------------------------------------------------------
# Delivery. One SNS topic per region for the honeytoken signals, email to the same owner
# address finops-budget.tf already uses. Alertmanager has no internet-facing receiver, and
# adding one would be a new attack surface for a detection control; email reaches a human
# outside the cluster, which is the property that matters when the cluster is the thing
# compromised. Wiring the same topic into GoAlert is a follow-up.
# ---------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "honeytoken_topic" {
  for_each = toset(["eu-north-1", "us-east-1"])

  statement {
    sid       = "EventBridgeAndCloudWatchPublish"
    actions   = ["sns:Publish"]
    resources = ["arn:${data.aws_partition.current.partition}:sns:${each.key}:${data.aws_caller_identity.current.account_id}:openbank-honeytoken-alerts"]
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com", "cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic" "honeytoken" {
  name = "openbank-honeytoken-alerts"
  tags = local.deception_tags
}

resource "aws_sns_topic_policy" "honeytoken" {
  arn    = aws_sns_topic.honeytoken.arn
  policy = data.aws_iam_policy_document.honeytoken_topic["eu-north-1"].json
}

resource "aws_sns_topic_subscription" "honeytoken_email" {
  topic_arn = aws_sns_topic.honeytoken.arn
  protocol  = "email"
  endpoint  = var.finops_alert_email
}

resource "aws_sns_topic" "honeytoken_us_east_1" {
  provider = aws.us_east_1
  name     = "openbank-honeytoken-alerts"
  tags     = local.deception_tags
}

resource "aws_sns_topic_policy" "honeytoken_us_east_1" {
  provider = aws.us_east_1
  arn      = aws_sns_topic.honeytoken_us_east_1.arn
  policy   = data.aws_iam_policy_document.honeytoken_topic["us-east-1"].json
}

resource "aws_sns_topic_subscription" "honeytoken_email_us_east_1" {
  provider  = aws.us_east_1
  topic_arn = aws_sns_topic.honeytoken_us_east_1.arn
  protocol  = "email"
  endpoint  = var.finops_alert_email
}

# ---------------------------------------------------------------------------------------------
# Tripwire 1: any API call signed by the canary key. Matching on the access key id, not the
# user name, so a call that fails authorization (errorCode AccessDenied) still matches.
# ---------------------------------------------------------------------------------------------

locals {
  canary_event_pattern = jsonencode({
    "detail-type" = ["AWS API Call via CloudTrail"]
    detail = {
      userIdentity = {
        accessKeyId = [aws_iam_access_key.canary.id]
      }
    }
  })
  canary_input_template = <<-EOT
    "HONEYTOKEN USED (ADR-0357): the canary AWS key <key> (user ${local.canary_user_name}) called <source>:<event> in <region> from <ip> (<ua>) at <time>. No legitimate process holds this key: treat as credential compromise. Find where it leaked from the placement list in #14163, rotate anything stored alongside it, and open an incident."
  EOT
  canary_input_paths = {
    key    = "$.detail.userIdentity.accessKeyId"
    source = "$.detail.eventSource"
    event  = "$.detail.eventName"
    region = "$.detail.awsRegion"
    ip     = "$.detail.sourceIPAddress"
    ua     = "$.detail.userAgent"
    time   = "$.detail.eventTime"
  }
}

resource "aws_cloudwatch_event_rule" "canary_key_used" {
  name          = "openbank-honeytoken-canary-key-used"
  description   = "ADR-0357: any API call signed by the canary access key."
  event_pattern = local.canary_event_pattern
  state         = "ENABLED_WITH_ALL_CLOUDTRAIL_MANAGEMENT_EVENTS"
  tags          = local.deception_tags
}

resource "aws_cloudwatch_event_target" "canary_key_used" {
  rule = aws_cloudwatch_event_rule.canary_key_used.name
  arn  = aws_sns_topic.honeytoken.arn
  input_transformer {
    input_paths    = local.canary_input_paths
    input_template = local.canary_input_template
  }
}

resource "aws_cloudwatch_event_rule" "canary_key_used_us_east_1" {
  provider      = aws.us_east_1
  name          = "openbank-honeytoken-canary-key-used"
  description   = "ADR-0357: any API call signed by the canary access key (global endpoints)."
  event_pattern = local.canary_event_pattern
  state         = "ENABLED_WITH_ALL_CLOUDTRAIL_MANAGEMENT_EVENTS"
  tags          = local.deception_tags
}

resource "aws_cloudwatch_event_target" "canary_key_used_us_east_1" {
  provider = aws.us_east_1
  rule     = aws_cloudwatch_event_rule.canary_key_used_us_east_1.name
  arn      = aws_sns_topic.honeytoken_us_east_1.arn
  input_transformer {
    input_paths    = local.canary_input_paths
    input_template = local.canary_input_template
  }
}

# ---------------------------------------------------------------------------------------------
# Tripwire 2: any request authenticated as the honey ServiceAccount. The SA has no RBAC
# binding, so every use is a 403 that does nothing, and is still recorded by the audit log.
# A metric filter is evaluated on ingest, so this costs no query and no extra ingestion.
# Measured 2026-10-29: the only principal that reads Secrets in arc-runners is the ARC
# controller (41,581 gets/24h), which is why the tripwire keys on the token's USE, not on the
# Secret being read — a read alarm would drown in controller traffic.
# ---------------------------------------------------------------------------------------------

resource "aws_cloudwatch_log_metric_filter" "honey_sa_used" {
  name           = "openbank-honeytoken-sa-used"
  log_group_name = "/aws/eks/${var.cluster_name}/cluster"
  pattern        = "{ $.user.username = \"system:serviceaccount:arc-runners:deploy-prod\" }"

  metric_transformation {
    name          = "HoneytokenServiceAccountUsed"
    namespace     = "OpenBank/Deception"
    value         = "1"
    default_value = "0"
  }
  # No depends_on module.eks: the log group already exists, and a module-level dependency
  # drags every pending EKS add-on update into a `-target` apply of this file (measured in
  # the plan, 2026-10-29). Apply this file without touching the control plane.
}

resource "aws_cloudwatch_metric_alarm" "honey_sa_used" {
  alarm_name          = "openbank-honeytoken-sa-used"
  alarm_description   = "HONEYTOKEN USED (ADR-0357): a request authenticated as system:serviceaccount:arc-runners:deploy-prod reached the EKS API. Nothing legitimate holds this token. Query the EKS audit log for user.username = that SA to get sourceIPs, userAgent and the attempted verbs, then treat the pod or host it came from as compromised."
  namespace           = "OpenBank/Deception"
  metric_name         = "HoneytokenServiceAccountUsed"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.honeytoken.arn]
  tags                = local.deception_tags
}

output "honeytoken_canary_access_key_id" {
  description = "Access key id of the canary user (not secret: it identifies the tripwire in CloudTrail)."
  value       = aws_iam_access_key.canary.id
}
