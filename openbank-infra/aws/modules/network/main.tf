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
  map_public_ip_on_launch = true

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
data "aws_ami" "fck_nat" {
  count       = var.egress_mode == "fck_nat" ? 1 : 0
  most_recent = true
  owners      = ["568608671756"] # fck-nat publisher account

  filter {
    name   = "name"
    values = ["fck-nat-al2023-arm64-*"]
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
  count         = var.egress_mode == "fck_nat" ? 1 : 0
  ami           = data.aws_ami.fck_nat[0].id
  instance_type = "t4g.nano"

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
    "sts",
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
