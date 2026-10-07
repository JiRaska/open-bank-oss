terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.53"
    }
  }
  # Backend config (bucket, key, region) is supplied outside this public repo.
  backend "s3" {}
}
