# ─────────────────────────────────────────────────────────────────────────────
# FinOps spend guardrail — AWS Budgets + Cost Anomaly Detection
# ─────────────────────────────────────────────────────────────────────────────
# Context: AWS Activate credits covered the account through May 2026 (May net ~$0,
# -$42.94 credit applied); they stopped on 2026-06-01, so the account now bills real
# money. Without a guardrail a runaway CI / NAT-egress burst could run up a large
# month-end bill unnoticed ("the AWS rocket"). These resources alert early — they do
# NOT cap or stop spend (a hard cap risks taking the sandbox down), they only notify.
#
# Two complementary signals:
#   - aws_budgets_budget: a fixed monthly ceiling with threshold alerts (catches a
#     steady overrun trending past the budget).
#   - Cost Anomaly Detection: ML baseline that catches a sudden spike even when total
#     is still under budget (free; catches the NAT-byte / fleet-rebuild kind of burst).
#
# Tune the ceiling via var.finops_monthly_budget_usd once Activate credits are renewed.

variable "finops_alert_email" {
  description = "Email that receives budget + cost-anomaly alerts."
  type        = string
  default     = "jiri@iraska.cz" # AWS account root / org management email
}

variable "finops_monthly_budget_usd" {
  description = "Monthly cost budget ceiling (USD). Alerts fire at 50/80/100% actual + 100% forecast. Early-warning, not a hard cap."
  type        = number
  default     = 300
}

resource "aws_budgets_budget" "monthly_cost" {
  name         = "openbank-sandbox-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.finops_monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Alert on real spend at 50/80/100% of the ceiling …
  dynamic "notification" {
    for_each = toset([50, 80, 100])
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = [var.finops_alert_email]
    }
  }

  # … and once when the month-end FORECAST is projected to blow the ceiling.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.finops_alert_email]
  }
}

# Cost Anomaly Detection — ML baseline per AWS service; alerts on a sudden spike even
# while the monthly total is still under budget (catches a NAT-byte / fleet-rebuild
# burst). NOT (re)created here: the account already carries AWS's default
# `Default-Services-Monitor` (DIMENSIONAL/SERVICE) + `Default-Services-Subscription`
# (DAILY → finops_alert_email), and AWS caps dimensional SERVICE monitors at one per
# account. So anomaly coverage is already live; this file adds only the budget ceiling
# on top. If the default subscription's threshold ever needs tightening, import the
# default monitor/subscription into state rather than creating a second one.

output "finops_budget_name" {
  description = "Name of the monthly cost budget guardrail."
  value       = aws_budgets_budget.monthly_cost.name
}

# ─────────────────────────────────────────────────────────────────────────────
# NAT egress early-warning — CloudWatch alarm + daily NAT budget
# ─────────────────────────────────────────────────────────────────────────────
# Root cause pattern (June 2026): three successive NAT spikes were caused by
# different sources (EC2 API calls, Docker Hub pulls, GitHub CDN JDK downloads)
# and each took 2-11 days to detect because the monthly budget alert fired only
# after $40/day was already spent. By then hundreds of GB had accumulated.
#
# These two guardrails detect a spike WITHIN 1-3 HOURS:
#
#  1. CloudWatch alarm on the NAT GW metric (fires ≤ 1h after a new NAT spike):
#     If any 1-hour window exceeds 20 GB through the NAT gateway, the alarm fires
#     → SNS → email. At $0.045/GB, 20 GB/hr = $0.90/hr = $21.60/day. That's
#     well above our expected post-fix baseline (< 2 GB/hr = < $2.16/day) but
#     catches a runaway before it reaches 790 GB/day again.
#     Diagnostic: check VPC Flow Logs on NAT GW ENI for top destination IPs.
#
#  2. Daily NAT budget at $5 (fires same day as a high-traffic day):
#     AWS Budgets can filter to NatGateway-Bytes usage type; this budget alerts
#     at $3 actual and $5 forecast — complements the hourly CW alarm with a
#     DAILY accumulated view (catches slow-burn sources the CW alarm misses).
#
# NAT GW ID is stable (nat-04cc90dd78be3f023, eu-north-1a) until fck-nat
# migration (ADR-0058) replaces it; at that point remove the CW alarm (no metric)
# and lower the daily budget to $1 (only DataTransfer-Out-Bytes remains).
# ─────────────────────────────────────────────────────────────────────────────

resource "aws_sns_topic" "finops_nat_alerts" {
  name = "openbank-finops-nat-alerts"
  tags = { Project = "openbank", ManagedBy = "opentofu" }
}

resource "aws_sns_topic_subscription" "finops_nat_email" {
  topic_arn = aws_sns_topic.finops_nat_alerts.arn
  protocol  = "email"
  endpoint  = var.finops_alert_email
}

resource "aws_cloudwatch_metric_alarm" "nat_egress_hourly" {
  alarm_name          = "openbank-nat-egress-20gb-per-hour"
  alarm_description   = "NAT gateway processed >20 GB in 1 hour. Diagnostic: check VPC Flow Logs for NAT GW ENI (eu-north-1). Past causes: GitHub CDN JDK re-downloads (RUNNER_TOOL_CACHE not cached), Docker Hub image pulls (ECR pull-through not working), aws-node EC2 API calls (EC2 VPC endpoint missing)."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "BytesOutToDestination"
  namespace           = "AWS/NatGateway"
  period              = 3600 # 1 hour
  statistic           = "Sum"
  threshold           = 20000000000 # 20 GB in bytes
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.finops_nat_alerts.arn]
  ok_actions          = [aws_sns_topic.finops_nat_alerts.arn]
  dimensions = {
    NatGatewayId = "nat-04cc90dd78be3f023"
  }
  tags = { Project = "openbank", ManagedBy = "opentofu" }
}

resource "aws_budgets_budget" "nat_daily" {
  name         = "openbank-nat-egress-daily"
  budget_type  = "COST"
  limit_amount = "5"
  limit_unit   = "USD"
  time_unit    = "DAILY"

  cost_filter {
    name   = "Service"
    values = ["EC2 - Other"]
  }
  cost_filter {
    name   = "UsageType"
    values = ["EUN1-NatGateway-Bytes", "EUN1-NatGateway-Hours"]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 60 # $3/day actual (60% of $5 limit)
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.finops_alert_email]
  }
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100 # $5/day forecast
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.finops_alert_email]
  }
}
