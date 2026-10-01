terraform {
  required_version = ">= 1.10"

  required_providers {
    # OpenBao is API-compatible with Vault; the Vault provider manages it unchanged.
    vault = {
      source  = "hashicorp/vault"
      version = "~> 5.0"
    }
  }

  # Separate state: OpenBao's own access configuration must never share a plan with the
  # platform it protects.
  backend "s3" {
    bucket       = "openbank-tofu-state-265175468565"
    key          = "sandbox/openbao.tfstate"
    region       = "eu-north-1"
    encrypt      = true
    use_lockfile = true
  }
}
