terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.53"
    }
  }

  # Isolated state: the public marketing site must never share a plan with the
  # EKS/DNS substrate. Native S3 locking (no DynamoDB), same bucket as the rest.
  backend "s3" {
    bucket       = "openbank-tofu-state-265175468565"
    key          = "web/prod.tfstate"
    region       = "eu-north-1"
    encrypt      = true
    use_lockfile = true
  }
}
