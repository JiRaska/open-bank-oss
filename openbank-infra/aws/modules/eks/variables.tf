variable "name" {
  description = "EKS cluster name."
  type        = string
}

variable "kubernetes_version" {
  description = "EKS control-plane Kubernetes version."
  type        = string
  default     = "1.31"
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
