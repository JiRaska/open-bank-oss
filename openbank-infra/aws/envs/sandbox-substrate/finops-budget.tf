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
