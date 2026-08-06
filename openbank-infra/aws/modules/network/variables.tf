variable "name" {
  description = "Name prefix for all network resources (also the EKS cluster name for discovery tags)."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR. /16 gives room for three /18 private blocks plus public /20s."
  type        = string
  default     = "10.80.0.0/16"
}

variable "az_count" {
  description = "Number of AZs to span. 3 for spread; cross-AZ is a first-class HA requirement per ADR-0027."
  type        = number
  default     = 3
}

variable "tags" {
  description = "Extra tags merged onto every resource."
  type        = map(string)
  default     = {}
}

variable "nat_ami_id" {
  description = <<-EOT
    PINNED AMI id for the fck-nat instance (egress_mode = "fck_nat").

    Why a pin and not "whatever is newest": the fck-nat publisher ships a new AMI
    every few weeks, and `data.aws_ami.fck_nat` (most_recent = true) re-resolves on
    EVERY plan. Wired straight into aws_instance.fck_nat that made `ami` a
    replace-forcing attribute which re-armed itself with no commit, no review and no
    author — so the next `tofu apply` for ANY unrelated reason destroyed and
    recreated the single NAT instance and dropped all private-subnet egress
    (issue #3602). Pinning turns a NAT rebuild back into a deliberate decision that
    shows up as a reviewable one-line diff.

    Leave empty ONLY when bootstrapping a brand-new environment that has no NAT
    instance yet — the module then falls back to the newest published AMI. Read the
    id it chose out of the apply output and pin it in the same change; an env left
    unpinned has the #3602 landmine re-armed.

    Upgrading deliberately: find the newest AMI, put its id here, open a PR, and
    apply in a window where a few minutes of lost egress is acceptable.
      aws ec2 describe-images --owners 568608671756 --region <region> \
        --filters 'Name=name,Values=fck-nat-al2023-hvm-*-arm64-*' \
        --query 'reverse(sort_by(Images,&CreationDate))[:3].[ImageId,Name]' --output text
  EOT
  type        = string
  default     = ""
  validation {
    condition     = var.nat_ami_id == "" || can(regex("^ami-[0-9a-f]{8,17}$", var.nat_ami_id))
    error_message = "nat_ami_id must be empty (bootstrap only) or a concrete ami-... id."
  }
}

variable "egress_mode" {
  description = "NAT egress strategy. 'managed_nat' = AWS-managed NAT Gateway (prod-safe default). 'fck_nat' = fck-nat t4g.nano instance (sandbox FinOps, removes per-GB processing fee, ADR-0058)."
  type        = string
  default     = "managed_nat"
  validation {
    condition     = contains(["managed_nat", "fck_nat"], var.egress_mode)
    error_message = "egress_mode must be 'managed_nat' or 'fck_nat'."
  }
}
