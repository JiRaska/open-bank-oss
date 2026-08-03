data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.name
}

# ---------------------------------------------------------------------------
# Node role for Karpenter-launched instances. Karpenter creates the instance
# profile from this role (EC2NodeClass.spec.role). Same managed policies as the
# bootstrap MNG nodes.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${var.cluster_name}-karpenter-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
    "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
  ])
  role       = aws_iam_role.node.name
  policy_arn = each.value
}

# Karpenter nodes are self-managed, so EKS needs an access entry to let their
# kubelet authenticate (managed node groups get this automatically).
resource "aws_eks_access_entry" "node" {
  cluster_name  = var.cluster_name
  principal_arn = aws_iam_role.node.arn
  type          = "EC2_LINUX"
}

# ---------------------------------------------------------------------------
# Interruption queue: Spot reclaims, rebalance recommendations, and scheduled
# changes are delivered here so Karpenter can cordon/drain ahead of termination.
# ---------------------------------------------------------------------------
resource "aws_sqs_queue" "interruption" {
  name                      = "${var.cluster_name}-karpenter"
  message_retention_seconds = 300
  sqs_managed_sse_enabled   = true
  tags                      = var.tags
}

data "aws_iam_policy_document" "interruption_sqs" {
  statement {
    sid     = "EventBridgeAndSQSSendMessage"
    actions = ["sqs:SendMessage"]
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com", "sqs.amazonaws.com"]
    }
    resources = [aws_sqs_queue.interruption.arn]
  }
}

resource "aws_sqs_queue_policy" "interruption" {
  queue_url = aws_sqs_queue.interruption.id
  policy    = data.aws_iam_policy_document.interruption_sqs.json
}

locals {
  interruption_event_rules = {
    spot_interruption = {
      source      = ["aws.ec2"]
      detail_type = ["EC2 Spot Instance Interruption Warning"]
    }
    rebalance = {
      source      = ["aws.ec2"]
      detail_type = ["EC2 Instance Rebalance Recommendation"]
    }
    instance_state = {
      source      = ["aws.ec2"]
      detail_type = ["EC2 Instance State-change Notification"]
    }
    scheduled_change = {
      source      = ["aws.health"]
      detail_type = ["AWS Health Event"]
    }
  }
}

resource "aws_cloudwatch_event_rule" "interruption" {
  for_each      = local.interruption_event_rules
  name          = "${var.cluster_name}-karpenter-${each.key}"
  event_pattern = jsonencode({ source = each.value.source, "detail-type" = each.value.detail_type })
  tags          = var.tags
}

resource "aws_cloudwatch_event_target" "interruption" {
  for_each  = local.interruption_event_rules
  rule      = aws_cloudwatch_event_rule.interruption[each.key].name
  target_id = "karpenter-queue"
  arn       = aws_sqs_queue.interruption.arn
}

# ---------------------------------------------------------------------------
# Controller role via EKS Pod Identity (no IRSA/OIDC needed for Karpenter v1).
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "controller_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "controller" {
  name               = "${var.cluster_name}-karpenter-controller"
  assume_role_policy = data.aws_iam_policy_document.controller_assume.json
  tags               = var.tags
}

# Sandbox-scoped controller policy: bounded to this region/account, the cluster
# tag, and the node role. The upstream getting-started policy adds finer-grained
# tag/RequestTag conditions for prod least-privilege — adopt that before prod.
data "aws_iam_policy_document" "controller" {
  statement {
    sid = "EC2ReadAndProvision"
    actions = [
      "ec2:CreateFleet",
      "ec2:CreateLaunchTemplate",
      "ec2:CreateTags",
      "ec2:DeleteLaunchTemplate",
      "ec2:RunInstances",
      "ec2:TerminateInstances",
      "ec2:DescribeAvailabilityZones",
      "ec2:DescribeImages",
      "ec2:DescribeInstances",
      "ec2:DescribeInstanceTypeOfferings",
      "ec2:DescribeInstanceTypes",
      "ec2:DescribeLaunchTemplates",
      "ec2:DescribeSecurityGroups",
      "ec2:DescribeSpotPriceHistory",
      "ec2:DescribeSubnets",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "PricingAndSSM"
    actions   = ["pricing:GetProducts", "ssm:GetParameter"]
    resources = ["*"]
  }

  statement {
    sid       = "EKSClusterDiscovery"
    actions   = ["eks:DescribeCluster"]
    resources = ["arn:aws:eks:${local.region}:${local.account_id}:cluster/${var.cluster_name}"]
  }

  statement {
    sid       = "InterruptionQueue"
    actions   = ["sqs:DeleteMessage", "sqs:GetQueueUrl", "sqs:ReceiveMessage", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.interruption.arn]
  }

  statement {
    sid       = "PassNodeRole"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.node.arn]
  }

  statement {
    sid = "ManageNodeInstanceProfiles"
    actions = [
      "iam:CreateInstanceProfile",
      "iam:DeleteInstanceProfile",
      "iam:GetInstanceProfile",
      "iam:TagInstanceProfile",
      "iam:AddRoleToInstanceProfile",
      "iam:RemoveRoleFromInstanceProfile",
      # ListInstanceProfiles is what the `instanceprofile.garbagecollection` controller calls,
      # and it was the one verb missing — so that controller had thrown a 403 on every pass for
      # the life of the cluster:
      #
      #   ERROR instanceprofile.garbagecollection  listing instance profiles ...
      #   operation error IAM: ListInstanceProfiles ... StatusCode: 403
      #
      # measured 2026-08-03 firing every ~16 minutes. Two costs, and the second is the one that
      # matters. (a) The GC cannot run, so an instance profile whose EC2NodeClass is deleted
      # leaks permanently — invisible until the account nears the 1000-profile limit. Today the
      # estate is clean (2 NodeClasses, 2 profiles), which is precisely why this is worth fixing
      # now rather than during the incident. (b) A recurring ERROR that is always present and
      # never actionable is how a log stops being read; this repo has paid for that lesson more
      # than once.
      #
      # `Get`/`Create`/`Delete` here are already `Resource: "*"` — the AWS-published Karpenter
      # policy scopes them by `aws:ResourceTag/kubernetes.io/cluster/<name>` instead, which is
      # tighter and worth doing, but is a separate change. `ListInstanceProfiles` cannot be
      # resource-scoped at all: it is a list operation over the account, so `"*"` is the only
      # valid form and adding it grants no access to a profile the role could not already read
      # by name via GetInstanceProfile.
      "iam:ListInstanceProfiles",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "controller" {
  name   = "karpenter-controller"
  role   = aws_iam_role.controller.id
  policy = data.aws_iam_policy_document.controller.json
}

resource "aws_eks_pod_identity_association" "controller" {
  cluster_name    = var.cluster_name
  namespace       = var.controller_namespace
  service_account = var.controller_service_account
  role_arn        = aws_iam_role.controller.arn
}
