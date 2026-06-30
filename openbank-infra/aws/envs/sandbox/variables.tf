variable "region" {
  description = "Sandbox region — eu-north-1, where the phase-0 account is set up."
  type        = string
  default     = "eu-north-1"
}

variable "github_repo" {
  description = "owner/repo the runner registers against."
  type        = string
  default     = "JiRaska/open-bank-oss"
}
