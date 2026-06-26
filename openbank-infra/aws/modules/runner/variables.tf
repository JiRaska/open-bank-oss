variable "name" {
  description = "Name prefix for all runner resources (e.g. openbank-sandbox-runner)."
  type        = string
}

variable "github_repo" {
  description = "owner/repo the runner registers against."
  type        = string
}

variable "runner_labels" {
  description = "Labels added to the runner so workflows can target it via runs-on."
  type        = list(string)
  default     = ["self-hosted", "linux", "arm64", "openbank-sandbox"]
}

variable "reg_token_ssm_parameter" {
  description = <<-EOT
    Name of a temporary SSM SecureString holding a short-lived (~1h) runner
    registration token. Created out of band just before apply:
      gh api -X POST repos/<owner>/<repo>/actions/runners/registration-token -q .token \
        | xargs -I{} aws ssm put-parameter --type SecureString \
            --name /openbank/sandbox/reg-token --value {} --overwrite
    The instance reads it once at boot (via its IAM role), registers, then
    DELETES the parameter. The token value never enters OpenTofu config/state or
    instance user-data — only this parameter name does.
  EOT
  type        = string
  default     = "/openbank/sandbox/reg-token"
}

variable "arch" {
  description = <<-EOT
    CPU architecture of the runner: "arm64" (Graviton, cheaper) or "x86_64".
    Selects both the AL2023 AMI and the GitHub Actions runner agent build, and
    must match the instance_type family (t4g/m7g = arm64; t3/m7i = x86_64).
    x86_64 is needed for tools with no arm64 build (e.g. the CodeQL CLI, which
    does not support linux/arm64).
  EOT
  type        = string
  default     = "arm64"
  validation {
    condition     = contains(["arm64", "x86_64"], var.arch)
    error_message = "arch must be \"arm64\" or \"x86_64\"."
  }
}

variable "instance_type" {
  description = "Instance type; family must match var.arch (t4g.* for arm64, t3.* for x86_64). t4g.small = 2 vCPU / 2 GiB, ~EUR 11/mo on-demand in eu-north-1."
  type        = string
  default     = "t4g.small"
}

variable "root_volume_gb" {
  description = "Root EBS (gp3) size in GiB. CI checkouts + Gradle/Docker caches need headroom."
  type        = number
  default     = 40
}

variable "use_spot" {
  description = <<-EOT
    Run the runner on a Spot instance instead of On-Demand (~65% cheaper in
    eu-north-1). Uses a PERSISTENT request with STOP-on-interruption: when AWS
    reclaims capacity the box is stopped (EBS root + warm Gradle/Docker caches
    preserved), not terminated, and restarted when capacity returns. The systemd
    resilience drop-in in user-data re-registers the runner agent on that restart,
    so CI self-heals without a human. Off by default; opt in per runner.
  EOT
  type        = bool
  default     = false
}

variable "vpc_cidr" {
  description = "CIDR for the runner's dedicated VPC."
  type        = string
  default     = "10.80.0.0/24"
}

variable "tags" {
  description = "Extra tags merged onto every resource."
  type        = map(string)
  default     = {}
}
