data "terraform_remote_state" "substrate" {
  backend = "s3"
  config = {
    bucket = "openbank-tofu-state-265175468565"
    key    = "sandbox/substrate.tfstate"
    region = "eu-north-1"
  }
}

locals {
  s            = data.terraform_remote_state.substrate.outputs
  cluster_name = local.s.cluster_name
  region       = local.s.region
  cluster_ca   = base64decode(local.s.cluster_certificate_authority_data)
  cluster_host = local.s.cluster_endpoint
}

provider "aws" {
  region = local.region

  default_tags {
    tags = {
      Project     = "openbank"
      ManagedBy   = "opentofu"
      Env         = "sandbox"
      Environment = "Dev"
      Service     = "openbank"
      Adr         = "0027"
    }
  }
}

# ECR Public's control-plane API lives only in us-east-1, regardless of where the
# images are actually served from (the public.ecr.aws CDN is global). Needed to
# manage the CI base-image mirror repo — see ecr-public-mirror.tf.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "openbank"
      ManagedBy   = "opentofu"
      Env         = "sandbox"
      Environment = "Dev"
      Service     = "openbank"
      Adr         = "0027"
    }
  }
}

# Tokens are minted per-invocation via the AWS CLI exec plugin (no long-lived
# kubeconfig token in state).
provider "kubernetes" {
  host                   = local.cluster_host
  cluster_ca_certificate = local.cluster_ca
  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", local.cluster_name, "--region", local.region]
  }
}

provider "helm" {
  kubernetes {
    host                   = local.cluster_host
    cluster_ca_certificate = local.cluster_ca
    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", local.cluster_name, "--region", local.region]
    }
  }
}

provider "kubectl" {
  host                   = local.cluster_host
  cluster_ca_certificate = local.cluster_ca
  load_config_file       = false
  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", local.cluster_name, "--region", local.region]
  }
}

# GitHub governance provider (ADR-0059 / issue #282).
# Token is passed via TF_VAR_governance_gh_pat env var in CI — never stored in state.
# Requires a fine-grained PAT with Contents:read + Administration:write on JiRaska/open-bank
# (for branch protection) and Environments:write (for the platform-apply environment).
# Store as the GOVERNANCE_GH_PAT secret in the open-bank repo settings.
provider "github" {
  owner = "JiRaska"
  token = var.governance_gh_pat
}
