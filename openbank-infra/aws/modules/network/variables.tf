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

variable "egress_mode" {
  description = "NAT egress strategy. 'managed_nat' = AWS-managed NAT Gateway (prod-safe default). 'fck_nat' = fck-nat t4g.nano instance (sandbox FinOps, removes per-GB processing fee, ADR-0058)."
  type        = string
  default     = "managed_nat"
  validation {
    condition     = contains(["managed_nat", "fck_nat"], var.egress_mode)
    error_message = "egress_mode must be 'managed_nat' or 'fck_nat'."
  }
}
