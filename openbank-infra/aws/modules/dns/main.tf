# ---------------------------------------------------------------------------
# Public DNS + the IAM that lets the cluster manage it.
#
# Route53 hosted zone for the apex domain. The domain itself stays registered
# at an external registrar — go-live is a one-time manual step: copy the four
# name_servers (output) into the registrar's NS records and wait for
# delegation. Everything below (record CRUD, ACME DNS-01) then works without
# touching the edge, so the IP-locked ingress NLB can stay locked.
#
# Two consumers get scoped, zone-bound credentials via EKS Pod Identity (same
# mechanism as Karpenter — no IRSA/OIDC annotations):
#   * external-dns   — reconciles A/ALIAS + TXT-ownership records from Ingress
#                      hosts, so adding a service needs no manual Route53 edit.
#   * cert-manager   — solves Let's Encrypt DNS-01 challenges (_acme-challenge
#                      TXT) to mint real browser-trusted leaf certs.
# ---------------------------------------------------------------------------

resource "aws_route53_zone" "this" {
  name    = var.domain
  comment = "OpenBank public zone (${var.domain}) — managed by external-dns; cert-manager solves ACME DNS-01 here."
  tags    = var.tags
}

# Records external-dns/cert-manager may CHANGE are bound to this one zone; the
# discovery List* calls can't be resource-scoped (they enumerate all zones).
locals {
  zone_arn = aws_route53_zone.this.arn
}

# Pod Identity trust: the EKS Pod Identity agent assumes these roles for the
# named (namespace, service account) pairs.
data "aws_iam_policy_document" "pod_identity_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------------------
# external-dns
# ---------------------------------------------------------------------------
resource "aws_iam_role" "external_dns" {
  name               = "${var.cluster_name}-external-dns"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "external_dns" {
  statement {
    sid       = "ChangeThisZone"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = [local.zone_arn]
  }
  statement {
    sid = "DiscoverZonesAndRecords"
    actions = [
      "route53:ListHostedZones",
      "route53:ListResourceRecordSets",
      "route53:ListTagsForResource",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "external_dns" {
  name   = "external-dns"
  role   = aws_iam_role.external_dns.id
  policy = data.aws_iam_policy_document.external_dns.json
}

resource "aws_eks_pod_identity_association" "external_dns" {
  cluster_name    = var.cluster_name
  namespace       = var.external_dns_namespace
  service_account = var.external_dns_service_account
  role_arn        = aws_iam_role.external_dns.arn
}

# ---------------------------------------------------------------------------
# cert-manager (DNS-01 solver)
# ---------------------------------------------------------------------------
resource "aws_iam_role" "cert_manager" {
  name               = "${var.cluster_name}-cert-manager-dns01"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "cert_manager" {
  statement {
    sid       = "AcmeChallengeRecords"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = [local.zone_arn]
  }
  statement {
    sid       = "GetChangeStatus"
    actions   = ["route53:GetChange"]
    resources = ["arn:aws:route53:::change/*"]
  }
  statement {
    sid       = "DiscoverZone"
    actions   = ["route53:ListHostedZonesByName", "route53:ListHostedZones"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "cert_manager" {
  name   = "cert-manager-dns01"
  role   = aws_iam_role.cert_manager.id
  policy = data.aws_iam_policy_document.cert_manager.json
}

resource "aws_eks_pod_identity_association" "cert_manager" {
  cluster_name    = var.cluster_name
  namespace       = var.cert_manager_namespace
  service_account = var.cert_manager_service_account
  role_arn        = aws_iam_role.cert_manager.arn
}

# ---------------------------------------------------------------------------
# Email anti-spoofing (SPF + DMARC)
# hello@open-bank.tech is a real Zoho Mail inbox (see the landing page's
# mailto: links) — SPF must authorize Zoho's sending servers, not reject
# everyone. DMARC p=reject still instructs receiving MTAs to drop anything
# that doesn't align with this SPF (or a future DKIM signature).
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# CAA (Certification Authority Authorization)
# Restricts which CAs may issue TLS certificates for this domain, reducing
# the blast radius of a rogue CA mis-issuance event.
#
# Authorized CAs:
#   amazon.com    — ACM (used for CloudFront distribution and ALB listeners)
#   letsencrypt.org — cert-manager DNS-01 (EKS workload leaf certs via ACME)
#
# issuewild entries mirror the issue entries so wildcard certs are also
# restricted to the same two CAs.
#
# iodef sends violation reports to the security inbox when a CA attempts to
# issue a cert not covered by these records.
# ---------------------------------------------------------------------------
resource "aws_route53_record" "caa" {
  zone_id = aws_route53_zone.this.zone_id
  name    = var.domain
  type    = "CAA"
  ttl     = 3600
  records = [
    "0 issue \"amazon.com\"",
    # RFC 8657: lock Let's Encrypt to DNS-01 only (prevents HTTP-01 hijack via dangling DNS)
    "0 issue \"letsencrypt.org; validationmethods=dns-01\"",
    "0 issuewild \"amazon.com\"",
    "0 issuewild \"letsencrypt.org; validationmethods=dns-01\"",
    "0 iodef \"mailto:security@${var.domain}\"",
  ]
}

# ---------------------------------------------------------------------------
# Email anti-spoofing (SPF + DMARC)
resource "aws_route53_record" "spf" {
  zone_id = aws_route53_zone.this.zone_id
  name    = var.domain
  type    = "TXT"
  ttl     = 300
  records = [
    "v=spf1 include:zohomail.eu ~all",
    # Zoho Mail domain-ownership proof (Zoho re-checks this TXT periodically;
    # removing it can silently suspend the hello@ mailbox).
    "zoho-verification=zb32116331.zmverify.zoho.eu",
  ]
}

# SPF is NOT inherited from the apex the way DMARC/CAA are (RFC 7208 has no
# organizational-domain fallback) — admin.<domain> needs its own record. No
# mail is ever sent from this subdomain (it's the operator console), so -all
# rejects every sender outright.
resource "aws_route53_record" "spf_admin" {
  zone_id = aws_route53_zone.this.zone_id
  name    = "admin.${var.domain}"
  type    = "TXT"
  ttl     = 300
  records = ["v=spf1 -all"]
}

resource "aws_route53_record" "dmarc" {
  zone_id = aws_route53_zone.this.zone_id
  name    = "_dmarc.${var.domain}"
  type    = "TXT"
  ttl     = 300
  records = ["v=DMARC1; p=reject; sp=reject; adkim=s; aspf=s; rua=mailto:dmarc-reports@${var.domain}"]
}
