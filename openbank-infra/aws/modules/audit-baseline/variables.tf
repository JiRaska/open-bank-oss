variable "name" {
  description = "Prefix for audit-baseline resources (bucket, trail, KMS alias)."
  type        = string
  default     = "openbank-sandbox"
}

variable "tags" {
  type    = map(string)
  default = {}
}

# Object Lock COMPLIANCE retention. COMPLIANCE-locked objects cannot be deleted
# by ANY principal (incl. root) until retention expires, which means the bucket
# itself cannot be torn down while locked objects exist. Sandbox defaults to 1
# day so `tofu destroy` is not permanently blocked. Production sets years —
# DORA Art. 12 expects multi-year immutable retention of ICT audit trails
# (e.g. 2555 days = 7y). Changing this only affects objects written AFTER the
# change; already-locked objects keep their original retention.
variable "log_retention_days" {
  description = "Object Lock COMPLIANCE retention in days for audit log objects."
  type        = number
  default     = 1
}

# Record S3 object-level data events in CloudTrail. Off by default in sandbox
# because data events are billed per event and the sandbox has no real PII
# traffic; production turns this on (DORA Art. 12 expects data-plane visibility
# on the audit log store and money-path buckets).
variable "record_s3_data_events" {
  description = "Whether the trail captures S3 object-level (data plane) events."
  type        = bool
  default     = false
}

variable "config_history_retention_days" {
  description = <<-EOT
    Days after which AWS Config history objects expire. Sandbox default 90 (Config
    snapshots are supplementary; 3 months is ample for incident lookback). Prod
    should set 365–2555 to match the DORA Art. 12 multi-year retention requirement.
  EOT
  type        = number
  default     = 90
}

# Master switch for AWS Config recording. When false the recorder, delivery
# channel, IAM role and history bucket all stay provisioned but the recorder is
# stopped — zero ConfigurationItem cost, one-flag re-enable, no resource churn.
# CloudTrail (the tamper-evident half of ADR-0027) is unaffected. Sandbox sets
# false: it is out of prod compliance scope, has no Config rules consuming the
# items, and a high-churn Karpenter estate makes even DAILY recording a steady
# cost with no consumer.
variable "config_recording_enabled" {
  description = "Whether the AWS Config recorder actively records. False stops recording but keeps all resources in place."
  type        = bool
  default     = true
}

variable "config_recording_frequency" {
  description = <<-EOT
    AWS Config recording frequency: CONTINUOUS (every change) or DAILY (one snapshot
    per resource per day). Default CONTINUOUS for prod-grade change-tracking. There are
    no change-triggered Config rules on this account, so DAILY loses no rule evaluation
    — only granularity — while killing the per-change ConfigurationItem cost from a
    high-churn estate (Karpenter node / ENI / volume create+delete). Sandbox sets DAILY.
  EOT
  type        = string
  default     = "CONTINUOUS"
  validation {
    condition     = contains(["CONTINUOUS", "DAILY"], var.config_recording_frequency)
    error_message = "config_recording_frequency must be CONTINUOUS or DAILY."
  }
}
