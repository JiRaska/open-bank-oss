variable "domain" {
  description = "Public apex domain whose Route53 hosted zone this module owns (e.g. open-bank.tech). NS records must be delegated to the zone's name_servers at the registrar."
  type        = string
}

variable "cluster_name" {
  description = "EKS cluster the Pod Identity associations target."
  type        = string
}

variable "external_dns_namespace" {
  description = "Namespace running external-dns (must match the gitops Deployment + ServiceAccount)."
  type        = string
  default     = "external-dns"
}

variable "external_dns_service_account" {
  description = "external-dns ServiceAccount name (must match the gitops Deployment)."
  type        = string
  default     = "external-dns"
}

variable "cert_manager_namespace" {
  description = "Namespace running cert-manager (the controller SA does the DNS-01 solve)."
  type        = string
  default     = "cert-manager"
}

variable "cert_manager_service_account" {
  description = "cert-manager controller ServiceAccount name (created by the cert-manager Helm chart)."
  type        = string
  default     = "cert-manager"
}

variable "tags" {
  type    = map(string)
  default = {}
}
