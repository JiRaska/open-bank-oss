terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.53"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }
  }

  # Separate state from the runner stack so the EKS lifecycle never disturbs the
  # live self-hosted runner. Native S3 locking (no DynamoDB).
  backend "s3" {
    bucket       = "openbank-tofu-state-265175468565"
    key          = "sandbox/substrate.tfstate"
    region       = "eu-north-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "openbank"
      ManagedBy   = "opentofu"
      Env         = "sandbox"
      Environment = "sandbox"
      Service     = "openbank"
      Adr         = "0027"
    }
  }
}

# Route53 DNSSEC requires its KMS key-signing CMK in us-east-1, independent of the
# zone's records. Used only by module.dns for the DNSSEC KSK.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "openbank"
      ManagedBy   = "opentofu"
      Env         = "sandbox"
      Environment = "sandbox"
      Service     = "openbank"
      Adr         = "0027"
    }
  }
}

# AWS Chatbot (Amazon Q Developer in chat apps) is a global service whose control-plane API is
# not served from eu-north-1. Used only by deception-delivery.tf. If the owner's first plan
# rejects the region, any Chatbot API region works; the SNS topics it subscribes stay where
# they are (ADR-0357 slice 2).
provider "aws" {
  alias  = "chatbot"
  region = "us-west-2"

  default_tags {
    tags = {
      Project     = "openbank"
      ManagedBy   = "opentofu"
      Env         = "sandbox"
      Environment = "sandbox"
      Service     = "openbank"
      Adr         = "0027"
    }
  }
}
