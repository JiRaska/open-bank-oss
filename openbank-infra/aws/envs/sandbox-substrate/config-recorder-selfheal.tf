# ─────────────────────────────────────────────────────────────────────────────
# AWS Config recorder self-heal (2026-07-06/07/08 incidents)
# ─────────────────────────────────────────────────────────────────────────────
# The Config recorder has been left fully STOPPED (not just drifted to
# CONTINUOUS recording_mode) three times by an apply that recreates or replaces
# the recorder and gets interrupted (cancelled/failed) before its start step
# runs — most recently by repeated `tofu apply` iterations against this same
# stack from a separate in-flight CI-pipeline change. Each recurrence cost
# $60-90 extra in a single day once a human noticed and restarted it, because a
# stopped-then-restarted recorder can also lose its DAILY recording_mode and
# briefly record CONTINUOUS again before the mode is re-applied.
#
# This closes the gap with two layers, event-driven + periodic backstop:
#  1. EventBridge rule matching CloudTrail's StopConfigurationRecorder call —
#     fires within ~1 minute of the recorder stopping, any cause.
#  2. A 15-minute schedule as a backstop in case the CloudTrail event is ever
#     missed (event delivery is best-effort, not guaranteed).
# Both invoke the same Lambda, which is idempotent — restarting an already-
# running recorder is a no-op checked before any AWS API call.
# ─────────────────────────────────────────────────────────────────────────────

data "aws_partition" "current" {}

data "archive_file" "config_recorder_healer" {
  type        = "zip"
  source_dir  = "${path.module}/lambda/config-recorder-healer"
  output_path = "${path.module}/lambda/config-recorder-healer.zip"
}

data "aws_iam_policy_document" "config_recorder_healer_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "config_recorder_healer" {
  name               = "${var.cluster_name}-config-recorder-healer"
  assume_role_policy = data.aws_iam_policy_document.config_recorder_healer_assume.json
}

data "aws_iam_policy_document" "config_recorder_healer" {
  statement {
    sid = "HealRecorder"
    actions = [
      "config:DescribeConfigurationRecorderStatus",
      "config:DescribeConfigurationRecorders",
      "config:StartConfigurationRecorder",
      "config:PutConfigurationRecorder",
    ]
    resources = ["*"] # Config recorder actions are not resource-scopable
  }
  statement {
    sid       = "PassRecorderRole"
    actions   = ["iam:PassRole"]
    resources = [module.audit_baseline.config_role_arn]
  }
  statement {
    sid = "Logs"
    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["arn:${data.aws_partition.current.partition}:logs:${var.region}:*:log-group:/aws/lambda/${var.cluster_name}-config-recorder-healer*"]
  }
}

resource "aws_iam_role_policy" "config_recorder_healer" {
  name   = "heal-recorder"
  role   = aws_iam_role.config_recorder_healer.id
  policy = data.aws_iam_policy_document.config_recorder_healer.json
}

resource "aws_lambda_function" "config_recorder_healer" {
  function_name    = "${var.cluster_name}-config-recorder-healer"
  role              = aws_iam_role.config_recorder_healer.arn
  handler           = "handler.handler"
  runtime           = "python3.13"
  timeout           = 30
  memory_size       = 128
  filename          = data.archive_file.config_recorder_healer.output_path
  source_code_hash  = data.archive_file.config_recorder_healer.output_base64sha256

  environment {
    variables = {
      RECORDER_NAME       = module.audit_baseline.config_recorder_name
      RECORDING_FREQUENCY = "DAILY"
    }
  }
}

resource "aws_cloudwatch_log_group" "config_recorder_healer" {
  name              = "/aws/lambda/${aws_lambda_function.config_recorder_healer.function_name}"
  retention_in_days = 14
}

# Layer 1: event-driven, fires within ~1 minute of the stop.
resource "aws_cloudwatch_event_rule" "config_recorder_stopped" {
  name = "${var.cluster_name}-config-recorder-stopped"
  event_pattern = jsonencode({
    source      = ["aws.config"]
    detail-type = ["AWS API Call via CloudTrail"]
    detail = {
      eventSource = ["config.amazonaws.com"]
      eventName   = ["StopConfigurationRecorder"]
    }
  })
}

resource "aws_cloudwatch_event_target" "config_recorder_stopped" {
  rule = aws_cloudwatch_event_rule.config_recorder_stopped.name
  arn  = aws_lambda_function.config_recorder_healer.arn
}

resource "aws_lambda_permission" "config_recorder_stopped" {
  statement_id  = "AllowEventBridgeStoppedEvent"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.config_recorder_healer.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.config_recorder_stopped.arn
}

# Layer 2: periodic backstop in case the CloudTrail event is ever missed.
resource "aws_cloudwatch_event_rule" "config_recorder_healer_schedule" {
  name                = "${var.cluster_name}-config-recorder-healer-schedule"
  schedule_expression = "rate(15 minutes)"
}

resource "aws_cloudwatch_event_target" "config_recorder_healer_schedule" {
  rule = aws_cloudwatch_event_rule.config_recorder_healer_schedule.name
  arn  = aws_lambda_function.config_recorder_healer.arn
}

resource "aws_lambda_permission" "config_recorder_healer_schedule" {
  statement_id  = "AllowEventBridgeSchedule"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.config_recorder_healer.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.config_recorder_healer_schedule.arn
}
