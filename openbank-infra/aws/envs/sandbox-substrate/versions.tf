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
