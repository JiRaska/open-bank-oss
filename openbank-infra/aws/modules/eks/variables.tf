variable "name" {
  description = "EKS cluster name."
  type        = string
}

variable "kubernetes_version" {
  description = "EKS control-plane Kubernetes version."
  type        = string
  # Deliberately no default. The version is governed in ONE place — the caller's
  # envs/sandbox-substrate/variables.tf, which eks-version-lifecycle.json and
  # check-version-lifecycle.py police (ADR-0054). A module default is a second,
  # unpoliced copy: this one read "1.31" (past end of standard support) while the
  # cluster ran 1.36, and any new caller that omitted the argument would have
  # silently created a cluster on extended-support pricing. Required = explicit.
}

variable "private_subnet_ids" {
  description = "Private subnets for control-plane ENIs and worker nodes."
  type        = list(string)
}

variable "public_subnet_ids" {
  description = "Public subnets (for internet-facing load balancers)."
  type        = list(string)
}

variable "endpoint_public_access" {
  description = "Expose the API server publicly. True for sandbox so laptop/CI reach it without a bastion; lock to CIDRs in prod."
  type        = bool
  default     = true
}

variable "public_access_cidrs" {
  description = "CIDRs allowed to the public API endpoint when enabled."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "admin_access_principal_arns" {
  description = "IAM principal ARNs granted cluster-admin via EKS access entries (e.g. the SSO AdministratorAccess role)."
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  description = "Instance types for the bootstrap managed node group (Graviton)."
  type        = list(string)
  default     = ["t4g.large"]
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 4
}

variable "tags" {
  type    = map(string)
  default = {}
}
