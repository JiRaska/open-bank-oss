data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  # AL2023 "latest" AMI SSM alias and GitHub runner agent download arch, keyed by
  # var.arch. The runner tarball uses "x64" where AWS/AMI uses "x86_64".
  ami_ssm_alias = {
    arm64  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
    x86_64 = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
  }
  runner_dl_arch = {
    arm64  = "arm64"
    x86_64 = "x64"
  }
}

# Latest Amazon Linux 2023 AMI for the selected architecture.
data "aws_ssm_parameter" "al2023" {
  name = local.ami_ssm_alias[var.arch]
}

# ---------------------------------------------------------------------------
# Minimal network: one public subnet + IGW. No NAT gateway (saves ~EUR 32/mo).
# The runner needs only outbound to GitHub; nothing connects inbound to it.
# ---------------------------------------------------------------------------
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

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 1, 0)
  map_public_ip_on_launch = true
  tags                    = merge(var.tags, { Name = "${var.name}-public" })
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
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# Egress-only: no inbound rules at all. Shell access is via SSM Session Manager.
resource "aws_security_group" "runner" {
  name        = var.name
  description = "openbank self-hosted runner - egress only"
  vpc_id      = aws_vpc.this.id

  egress {
    description = "all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = var.name })
}

# ---------------------------------------------------------------------------
# IAM: SSM core (Session Manager, no SSH key) + read-and-delete on exactly the
# one temporary registration-token parameter (least privilege).
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "runner" {
  name               = "${var.name}-role"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.runner.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "reg_token" {
  statement {
    sid       = "ReadAndDeleteRegToken"
    actions   = ["ssm:GetParameter", "ssm:DeleteParameter"]
    resources = ["arn:aws:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter${var.reg_token_ssm_parameter}"]
  }
  statement {
    sid       = "DecryptRegToken"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${data.aws_region.current.name}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "reg_token" {
  name   = "${var.name}-reg-token"
  role   = aws_iam_role.runner.id
  policy = data.aws_iam_policy_document.reg_token.json
}

resource "aws_iam_instance_profile" "runner" {
  name = "${var.name}-profile"
  role = aws_iam_role.runner.name
}

# ---------------------------------------------------------------------------
# The runner instance. At boot user-data reads the temporary reg-token from SSM,
# registers the runner as a systemd service (survives reboots), then deletes the
# SSM parameter. The token value is never present in user-data or state.
# ---------------------------------------------------------------------------
resource "aws_instance" "runner" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.runner.id]
  iam_instance_profile   = aws_iam_instance_profile.runner.name

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    github_repo     = var.github_repo
    runner_name     = var.name
    runner_labels   = join(",", var.runner_labels)
    reg_token_param = var.reg_token_ssm_parameter
    aws_region      = data.aws_region.current.name
    # Arch token drives the gitleaks/shellcheck/runner-agent asset selection in
    # user-data. Parametrized on var.arch (default arm64/Graviton, cheaper) so the
    # x86_64 escape hatch (CodeQL CLI etc.) stays available without editing this file.
    runner_arch     = local.runner_dl_arch[var.arch]
  })

  # Spot when var.use_spot. PERSISTENT request + STOP (not terminate) on
  # interruption: the box comes back with its EBS root and warm Gradle/Docker
  # caches when capacity returns, and the systemd resilience drop-in re-registers
  # the runner agent — so CI self-heals. ~65% cheaper than on-demand. Flipping an
  # existing on-demand instance to spot forces a one-time replacement (the new box
  # re-bootstraps via user-data and re-registers).
  dynamic "instance_market_options" {
    for_each = var.use_spot ? [1] : []
    content {
      market_type = "spot"
      spot_options {
        spot_instance_type             = "persistent"
        instance_interruption_behavior = "stop"
      }
    }
  }

  metadata_options {
    http_tokens   = "required" # IMDSv2 only
    http_endpoint = "enabled"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gb
    encrypted             = true
    delete_on_termination = true
  }

  tags = merge(var.tags, { Name = var.name })
}
