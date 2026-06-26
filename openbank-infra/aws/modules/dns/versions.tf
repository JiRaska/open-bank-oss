terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
      # DNSSEC key-signing keys require a KMS CMK in us-east-1 regardless of where the
      # zone's records live, so the caller passes an aliased us-east-1 provider in too.
      configuration_aliases = [aws.us_east_1]
    }
  }
}
