module "network" {
  source = "../../modules/network"

  name        = var.cluster_name
  vpc_cidr    = "10.80.0.0/16"
  az_count    = 3
  egress_mode = "fck_nat" # ADR-0058: replace managed NAT GW with fck-nat t4g.nano to remove per-GB processing fee

  # The AMI the live NAT instance is ALREADY running. Pinned so that a newly
  # published upstream fck-nat AMI cannot turn an unrelated `tofu apply` into a
  # rebuild of the single NAT instance — which drops all private-subnet egress
  # (issue #3602). Changing this line IS the NAT upgrade: it must be its own PR,
  # applied in a window. See modules/network/variables.tf for the lookup command.
  nat_ami_id = "ami-08c439a446e724124"

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
  # Karpenter controller + ArgoCD. Karpenter provisions everything else; this
  # pool stays on-demand deliberately (Karpenter needs a stable node to run on
  # before it can provision anything else — it can't provision its own home).
  #
  # t4g.medium → c7g.large (FinOps issue #445, 2026-07-08): the 2026-06-05
  # downsize to t4g.medium (<16% CPU at the time) has since regressed — fleet
  # growth pushed sustained CPU to ~40% average with bursts to 95-98%
  # (CloudWatch, 14-day window) and CPUCreditBalance is permanently at 0,
  # meaning the node runs entirely on paid CPUSurplusCreditBalance (June's
  # ~$28/mo `CPUCredits:t4g` Cost Explorer line). A burstable type is the
  # wrong instance family for a node that is chronically above its baseline —
  # switch to c7g.large: same 2 vCPU as t4g.medium but non-burstable
  # (compute-optimized, no credit mechanism at all), so the CPUCredits line
  # goes to zero. BoxUsage rises (~$0.0344/h -> ~$0.0774/h per node) but the
  # $28/mo CPUCredits burn (100% avoidable, was pure inefficiency tax) is
  # eliminated outright, and headroom removes the risk of kubelet/CDI thrash
  # under load spikes on the node running Karpenter's own controller.
  node_instance_types = ["c7g.large"]
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

  # Sandbox is out of prod compliance scope (and churns Karpenter nodes constantly).
  # Even at DAILY frequency, full resource-type recording over that churn is a steady
  # ~$140/mo with no consumer (no Config rules on this account), so the recorder is
  # stopped entirely. Risk boundary: what is lost is only the supplementary
  # "what the resource looked like" state snapshots; the "who did what" audit trail —
  # the actual ADR-0027 tamper-evident requirement — is CloudTrail (multi-region,
  # SHA-256 digest chain, COMPLIANCE Object-Lock WORM bucket) and is untouched, so
  # every configuration CHANGE remains attributable and tamper-evident via its API
  # event. All Config resources stay provisioned — re-enable is this one flag
  # (frequency stays DAILY for when it comes back), and the
  # openbank-config-recording-daily budget in finops-budget.tf remains as the
  # tripwire against silent re-enable. Prod-shaped environments keep the module
  # default (enabled).
  config_recording_enabled   = false
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
