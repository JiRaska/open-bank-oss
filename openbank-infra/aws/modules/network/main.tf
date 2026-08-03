data "aws_region" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  # Three /18 private blocks (indices 0..2 of the /16 split into /18) for nodes;
  # the fourth /18 is carved into /20 public subnets for the NAT GW and ALBs.
  private_cidrs = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 2, i)]
  public_cidrs  = [for i in range(var.az_count) : cidrsubnet(var.vpc_cidr, 4, 12 + i)]
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(var.tags, { Name = var.name })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = var.name })
}

# ---------------------------------------------------------------------------
# Subnets. Public subnets host the single NAT GW and public-facing ALBs;
# private subnets host all worker nodes (no public IPs). Tags drive discovery:
#   kubernetes.io/role/elb           -> public subnets for internet-facing LBs
#   kubernetes.io/role/internal-elb  -> private subnets for internal LBs
#   karpenter.sh/discovery = <name>  -> private subnets Karpenter launches into
# ---------------------------------------------------------------------------
resource "aws_subnet" "private" {
  count             = var.az_count
  vpc_id            = aws_vpc.this.id
  cidr_block        = local.private_cidrs[count.index]
  availability_zone = local.azs[count.index]

  tags = merge(var.tags, {
    Name                              = "${var.name}-private-${local.azs[count.index]}"
    "kubernetes.io/role/internal-elb" = "1"
    "karpenter.sh/discovery"          = var.name
  })
}

resource "aws_subnet" "public" {
  count                   = var.az_count
  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.public_cidrs[count.index]
  availability_zone       = local.azs[count.index]
  # Do NOT auto-assign public IPs to every instance launched here (EC2.25).
  # The fck-nat instance sets associate_public_ip_address = true explicitly;
  # ALBs and NLBs allocate their own IPs independently of this flag.
  map_public_ip_on_launch = false

  tags = merge(var.tags, {
    Name                     = "${var.name}-public-${local.azs[count.index]}"
    "kubernetes.io/role/elb" = "1"
  })
}

# ---------------------------------------------------------------------------
# Egress NAT — two modes selected by var.egress_mode (ADR-0058):
#
#   managed_nat (default, prod-safe):
#     AWS-managed NAT Gateway + EIP. ~$33/mo fixed + $0.045/GB processing.
#
#   fck_nat (sandbox FinOps):
#     fck-nat t4g.nano instance — no per-GB processing fee, only EC2 hours
#     (~$3/mo). Source/dest check disabled; EIP attached to the instance.
#     Same single-AZ blast radius as the managed NAT (sandbox SLO).
#
# To switch: set egress_mode = "fck_nat" in the env module call and re-apply.
# Rollback: flip back to "managed_nat" and re-apply (~2 min, reversible).
# ---------------------------------------------------------------------------

# EIP — shared between both modes (reattached to whichever NAT is active).
resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = merge(var.tags, { Name = "${var.name}-nat" })
}

# --- managed NAT Gateway (count=0 when egress_mode=fck_nat) -----------------
resource "aws_nat_gateway" "this" {
  count         = var.egress_mode == "managed_nat" ? 1 : 0
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id
  tags          = merge(var.tags, { Name = var.name })

  depends_on = [aws_internet_gateway.this]
}

# --- fck-nat instance (count=0 when egress_mode=managed_nat) ----------------
#
# This data source is the BOOTSTRAP fallback only — it is consulted when
# var.nat_ami_id is empty, i.e. on a brand-new environment that has no NAT
# instance yet. Every environment that already runs one pins var.nat_ami_id, so
# a newly published upstream AMI cannot silently force-replace a live NAT.
# See var.nat_ami_id and issue #3602 for the full failure mode.
data "aws_ami" "fck_nat" {
  count       = var.egress_mode == "fck_nat" ? 1 : 0
  most_recent = true
  owners      = ["568608671756"] # fck-nat publisher account

  filter {
    # Publisher's naming convention now inserts hvm-<fck-nat version>-<build date>
    # between "al2023" and "arm64" (e.g. fck-nat-al2023-hvm-1.4.0-20260701-arm64-ebs).
    # The old "fck-nat-al2023-arm64-*" pattern stopped matching anything, which
    # broke every tofu plan/apply for this module ("query returned no results").
    # This pattern is deliberately anchored right after "al2023" so it does NOT
    # also match the "fck-nat-nat64-al2023-hvm-*" NAT64 variant.
    name   = "name"
    values = ["fck-nat-al2023-hvm-*-arm64-*"]
  }

  filter {
    name   = "architecture"
    values = ["arm64"]
  }

  filter {
    name   = "state"
    values = ["available"]
  }
}

resource "aws_security_group" "fck_nat" {
  count       = var.egress_mode == "fck_nat" ? 1 : 0
  name        = "${var.name}-fck-nat"
  description = "fck-nat NAT instance - inbound from VPC CIDR, all outbound"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "All traffic from within the VPC (private subnets NATing outbound)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Unrestricted outbound (this instance IS the NAT)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-fck-nat" })
}

resource "aws_instance" "fck_nat" {
  count = var.egress_mode == "fck_nat" ? 1 : 0
  # PINNED, deliberately (issue #3602). The data source below is the bootstrap
  # fallback for an environment that has no NAT yet; a live environment pins,
  # so `ami` only ever changes when a human changes it in a reviewed diff.
  ami = var.nat_ami_id != "" ? var.nat_ami_id : data.aws_ami.fck_nat[0].id
  # t4g.nano/micro hit InsufficientInstanceCapacity in eu-north-1a; t4g.small
  # is reliably available and still comfortably sized for NAT-instance traffic.
  instance_type = "t4g.small"

  subnet_id = aws_subnet.public[0].id
  # tfsec/infracost EC2.9/EC2.25: public IP is intentional — this IS the NAT.
  # fck-nat must have a public IP to masquerade private-subnet traffic to the
  # internet; associate_public_ip_address = false would break NAT entirely.
  associate_public_ip_address = true  # trivy-ignore:AVD-AWS-0130
  source_dest_check           = false # MUST be false for NAT to work

  vpc_security_group_ids = [aws_security_group.fck_nat[0].id]

  tags = merge(var.tags, { Name = "${var.name}-fck-nat" })

  root_block_device {
    tags = merge(var.tags, { Name = "${var.name}-fck-nat-root" })
  }

  lifecycle {
    # Replace the instance when the AMI changes (fck-nat publishes patched AMIs).
    create_before_destroy = true
  }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_eip_association" "fck_nat" {
  count         = var.egress_mode == "fck_nat" ? 1 : 0
  instance_id   = aws_instance.fck_nat[0].id
  allocation_id = aws_eip.nat.id
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = merge(var.tags, { Name = "${var.name}-public" })
}

resource "aws_route_table_association" "public" {
  count          = var.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block      = "0.0.0.0/0"
    nat_gateway_id  = var.egress_mode == "managed_nat" ? aws_nat_gateway.this[0].id : null
    network_interface_id = var.egress_mode == "fck_nat" ? aws_instance.fck_nat[0].primary_network_interface_id : null
  }
  tags = merge(var.tags, { Name = "${var.name}-private" })
}

resource "aws_route_table_association" "private" {
  count          = var.az_count
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# ---------------------------------------------------------------------------
# VPC endpoints. S3 is a free gateway endpoint (ECR layers live in S3). The
# interface endpoints keep ECR/STS/SSM/EKS/logs API calls inside the VPC, off
# the NAT — cheaper and lower-latency for node bootstrap and image pulls.
# ---------------------------------------------------------------------------
resource "aws_security_group" "endpoints" {
  name        = "${var.name}-vpce"
  description = "Allow HTTPS from the VPC to interface endpoints"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "HTTPS from within the VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-vpce" })
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]
  tags              = merge(var.tags, { Name = "${var.name}-s3" })
}

locals {
  interface_endpoints = [
    "ecr.api",
    "ecr.dkr",
    # ssm/ssmmessages/ec2messages removed (FinOps 2026-06-05): these were added
    # for the retired EC2 self-hosted runner model (ADR-0082). With EKS+kubectl
    # node access and ARC ephemeral runners gone, SSM Session Manager is not
    # used. Removing saves ~3 interface endpoints × 2 AZs × $0.01/h ≈ $10/month.
    #
    # eks removed (FinOps 2026-06-22): the EKS VPC endpoint is used for private
    # EKS API access from within the VPC. ArgoCD, Karpenter, and ARC all reach
    # the API server via the in-cluster kubernetes.default service (no VPC
    # endpoint needed). kubectl from CI runners does not run in the cluster.
    # Removing saves 3 AZs × $0.01/h = $0.72/day.
    #
    # logs removed (FinOps 2026-06-22): only EKS control-plane log shipping used
    # this endpoint. With EKS audit/controllerManager/scheduler logs disabled
    # (dominant volume = 10+ GB/day), remaining api+authenticator volume is tiny
    # and can go via NAT ($0.045/GB vs endpoint standing charge $0.72/day).
    # Fluent Bit ships to in-cluster Loki (not CloudWatch), so no other consumer.
    #
    # sts removed (FinOps #444, 2026-07-08): post-#198 hosted-runner migration
    # audit. CloudWatch AWS/PrivateLinkEndpoints BytesProcessed showed ~0.23 GB
    # over the trailing 14 days (~0.5 GB/month) — NAT-equivalent cost ~$0.02/mo
    # vs the ~$23/mo fixed cost of the endpoint (3 AZs × $0.0105/h × 730h).
    # IRSA/STS token exchange still works fine over the NAT path (STS is a
    # low-volume, small-payload auth API, not a bulk-data one) — no security
    # posture change, just no longer routed privately. Watch
    # openbank-nat-egress-daily; if this regresses, revert is a one-line list
    # entry (see open-bank-oss#444).
    #
    # ecr.api / ecr.dkr KEPT despite also being low-traffic on this measurement
    # window (ecr.api ~0.2 GB/14d, ecr.dkr ~25.5 GB/14d but bursty up to
    # 6-10 GB on deploy/Karpenter-churn days): both are the transport for the
    # ECR pull-through cache (envs/sandbox-platform/ecr-pull-through-cache.tf)
    # that every Karpenter node image pull depends on. ecr.dkr carries the
    # actual layer bytes; ecr.api carries the paired auth/manifest calls for
    # the same flow. Removing either pushes ALL node image pulls back onto
    # NAT, which is the exact cost this pair was built to eliminate — traffic
    # here scales with deploy/node-churn activity, not a flat baseline, so a
    # quiet 14-day sample understates its long-run value.
  ]
}

resource "aws_vpc_endpoint" "interface" {
  for_each = toset(local.interface_endpoints)

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${data.aws_region.current.name}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true
  tags                = merge(var.tags, { Name = "${var.name}-${each.value}" })
}
