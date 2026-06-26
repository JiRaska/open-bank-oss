variable "cluster_name" {
  description = "EKS cluster name (also the karpenter.sh/discovery tag value)."
  type        = string
}

variable "controller_namespace" {
  description = "Namespace the Karpenter controller runs in (must match the Helm release)."
  type        = string
  default     = "kube-system"
}

variable "controller_service_account" {
  description = "Karpenter controller service account name (must match the Helm release)."
  type        = string
  default     = "karpenter"
}

variable "tags" {
  type    = map(string)
  default = {}
}
