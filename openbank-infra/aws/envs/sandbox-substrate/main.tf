module "network" {
  source = "../../modules/network"

  name        = var.cluster_name
  vpc_cidr    = "10.80.0.0/16"
  az_count    = 3
  egress_mode = "fck_nat" # ADR-0058: replace managed NAT GW with fck-nat t4g.nano to remove per-GB processing fee

  tags = {
    Project     = "openbank"
    ManagedBy   = "opentofu"
    Environment = "sandbox"
    Service     = "network"
  }
}

module "eks" {
  source = "../../modules/eks"

  name               = var.cluster_name
  kubernetes_version = var.kubernetes_version
  private_subnet_ids = module.network.private_subnet_ids
  public_subnet_ids  = module.network.public_subnet_ids

  admin_access_principal_arns = var.admin_access_principal_arns

  # Bootstrap pool: small on-demand Graviton, just enough for system pods +
  # Karpenter controller + ArgoCD. Karpenter provisions everything else.
  # Downsized t4g.large → t4g.medium (FinOps 2026-06-05): bootstrap nodes run
  # at <16% CPU / <80% RAM. t4g.medium (2 vCPU / 4 GB) fits all system pods;
  # saves ~$22/month (2× on-demand delta: $0.0336 vs $0.0672/h per node).
  node_instance_types = ["t4g.medium"]
  node_desired_size   = 2
  node_min_size       = 2
  node_max_size       = 4

  tags = {
    Project     = "openbank"
    ManagedBy   = "opentofu"
    Environment = "sandbox"
    Service     = "openbank"
  }
}

module "karpenter_iam" {
  source = "../../modules/karpenter-iam"

  cluster_name = module.eks.cluster_name

  # Must match the Karpenter Helm release in the platform root.
  controller_namespace       = "kube-system"
  controller_service_account = "karpenter"

  tags = {
    Project     = "openbank"
    ManagedBy   = "opentofu"
    Environment = "sandbox"
    Service     = "karpenter"
  }
}

# Public DNS (open-bank.tech) + zone-scoped Pod Identity IAM for external-dns
# and cert-manager DNS-01. DNS-01 (not HTTP-01) is the only ACME path that
# works here: the edge NLB is IP-locked, so Let's Encrypt can't reach it — but
# it can read a TXT record. After apply, delegate the domain to the zone's
# name_servers output at the registrar.
module "dns" {
  source = "../../modules/dns"

  # us_east_1 is required for the DNSSEC key-signing KMS key.
  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  domain       = var.domain
  cluster_name = module.eks.cluster_name
}

# ADR-0027 go-live condition: immutable, tamper-evident audit trail.
# CloudTrail + AWS Config → Object Lock (COMPLIANCE) S3 bucket. Sandbox uses a
# 1-day lock so the stack stays destroyable; prod overrides log_retention_days
# to multi-year and turns on S3 data events.
module "audit_baseline" {
  source = "../../modules/audit-baseline"

  name = var.cluster_name

  # Sandbox is out of prod compliance scope (and churns Karpenter nodes constantly),
  # so record AWS Config daily instead of per-change: same resource-type coverage, no
  # change-triggered rules depend on continuous, ~$85/mo of ConfigurationItem cost cut.
  config_recording_frequency = "DAILY"

  # 1-day COMPLIANCE lock keeps the sandbox destroyable; config history is short-lived.
  log_retention_days            = 1
  config_history_retention_days = 90

  tags = {
    Project     = "openbank"
    ManagedBy   = "opentofu"
    Environment = "sandbox"
    Component   = "audit-baseline"
  }
}
