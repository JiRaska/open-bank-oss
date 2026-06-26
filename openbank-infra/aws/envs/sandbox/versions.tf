terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }

  # Remote state in the bootstrap-created bucket. Native S3 locking (no DynamoDB).
  backend "s3" {
    bucket       = "openbank-tofu-state-265175468565"
    key          = "sandbox/runner.tfstate"
    region       = "eu-north-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "openbank"
      ManagedBy = "opentofu"
      Env       = "sandbox"
      Adr       = "0027"
    }
  }
}
