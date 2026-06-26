terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "openbank"
      ManagedBy = "opentofu"
      Layer     = "bootstrap"
      Adr       = "0027"
    }
  }
}
