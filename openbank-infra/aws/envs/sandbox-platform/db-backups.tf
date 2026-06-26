# ---------------------------------------------------------------------------
# CNPG database backups — S3 + Pod Identity for barman-cloud (statements first).
#
# The statement-service period-close records carry the PSD2/ČNB retention
# obligation (ADR-0035: statements are re-rendered byte-identically from the
# StatementPeriod rows — losing the DB loses the legal record). Until now NO
# CNPG cluster had backups at all; this wires WAL archiving + scheduled base
# backups for statements-db as the pattern to roll out fleet-wide.
#
# Mechanism: EKS Pod Identity (same as cost-collector/external-dns — no OIDC
# annotations); CNPG pods run as the `statements-db` ServiceAccount, barman-cloud
# picks the credentials up through the AWS SDK default chain.
#
# Sandbox bucket lifecycle expires objects after 35 days — the legal 10y
# retention applies to PROD, where the lifecycle must be replaced by
# compliance-grade retention (object lock / glacier) before go-live (ADR-0027).
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "db_backups" {
  bucket        = "${local.cluster_name}-db-backups"
  force_destroy = true # sandbox only — prod must never set this
  tags          = { Project = "openbank", ManagedBy = "opentofu", Adr = "0035" }
}

resource "aws_s3_bucket_public_access_block" "db_backups" {
  bucket                  = aws_s3_bucket.db_backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "db_backups" {
  bucket = aws_s3_bucket.db_backups.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "db_backups" {
  bucket = aws_s3_bucket.db_backups.id
  rule {
    id     = "sandbox-expiry"
    status = "Enabled"
    filter {}
    expiration {
      days = 35
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 3
    }
  }
}

data "aws_iam_policy_document" "db_backups_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "db_backups" {
  name               = "${local.cluster_name}-db-backups"
  assume_role_policy = data.aws_iam_policy_document.db_backups_assume.json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0035" }
}

data "aws_iam_policy_document" "db_backups" {
  statement {
    sid       = "BucketOps"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [aws_s3_bucket.db_backups.arn]
  }
  statement {
    sid = "ObjectOps"
    # barman-cloud needs delete for its retention sweep of expired base backups.
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.db_backups.arn}/*"]
  }
}

resource "aws_iam_role_policy" "db_backups" {
  name   = "s3-backup-rw"
  role   = aws_iam_role.db_backups.id
  policy = data.aws_iam_policy_document.db_backups.json
}

# CNPG runs the instance pods under a ServiceAccount named after the Cluster
# (statements-db). One association per backed-up cluster; add more as the
# pattern rolls out fleet-wide.
resource "aws_eks_pod_identity_association" "db_backups_statements" {
  cluster_name    = local.cluster_name
  namespace       = "statements"
  service_account = "statements-db"
  role_arn        = aws_iam_role.db_backups.arn
}

# Fleet rollout (critical money-path + compliance DBs). One association per CNPG cluster;
# the SA is named after the Cluster. All share the bucket-wide db_backups role (each cluster
# writes to its own s3://.../<cluster>-db prefix). The matching gitops backup stanza +
# ScheduledBackup live in openbank-infra/gitops/components/<svc>/postgres.yaml.
locals {
  db_backup_clusters = {
    # Original fleet (compliance + money-path first)
    ledger    = { namespace = "ledger",    sa = "ledger-db" }
    # Restore-drill slot: permanent SA for point-in-time restore tests (runbook-0003).
    # Named "ledger-db-drill" so it never conflicts with the live cluster SA.
    ledger-drill = { namespace = "ledger", sa = "ledger-db-drill" }
    balances  = { namespace = "balances",  sa = "balances-db" }
    accounts  = { namespace = "accounts",  sa = "accounts-db" }
    kyc       = { namespace = "kyc",       sa = "kyc-db" }
    consent   = { namespace = "consent",   sa = "consent-db" }
    sca       = { namespace = "sca",       sa = "sca-db" }
    audit     = { namespace = "audit",     sa = "audit-db" }
    sanctions = { namespace = "sanctions", sa = "sanctions-db" }
    # Extended fleet — all remaining clusters with barmanObjectStore to openbank-sandbox-db-backups
    aml              = { namespace = "aml",              sa = "aml-db" }
    dispute          = { namespace = "dispute",          sa = "dispute-db" }
    fraud            = { namespace = "fraud",            sa = "fraud-db" }
    fx               = { namespace = "fx",               sa = "fx-db" }
    keycloak         = { namespace = "iam",              sa = "keycloak-db" }
    interest         = { namespace = "interest",         sa = "interest-db" }
    apicurio         = { namespace = "messaging",        sa = "apicurio-db" }
    notifications    = { namespace = "notifications",    sa = "notifications-db" }
    onboarding       = { namespace = "onboarding",       sa = "onboarding-db" }
    pact-broker      = { namespace = "pact-broker",      sa = "pact-broker-db" }
    party            = { namespace = "party",            sa = "party-db" }
    card-issuance    = { namespace = "payments",         sa = "card-issuance-db" }
    settlement       = { namespace = "payments",         sa = "settlement-service-db" }
    swift-service    = { namespace = "payments",         sa = "swift-service-db" }
    transaction      = { namespace = "payments",         sa = "transaction-db" }
    sepa-payment     = { namespace = "payments",         sa = "sepa-payment-db" }
    domestic-payment = { namespace = "payments",         sa = "domestic-payment-db" }
    sepa-instant     = { namespace = "payments",         sa = "sepa-instant-db" }
    clearing         = { namespace = "payments",         sa = "clearing-db" }
    standing-order   = { namespace = "payments",         sa = "standing-order-db" }
    pid              = { namespace = "pid",              sa = "pid-db" }
    agent            = { namespace = "platform",         sa = "agent-db" }
    psd2             = { namespace = "psd2",             sa = "psd2-db" }
    security-scanner = { namespace = "security-scanner", sa = "security-scanner-db" }
    temporal         = { namespace = "temporal",         sa = "temporal-db" }
  }
}

resource "aws_eks_pod_identity_association" "db_backups_fleet" {
  for_each = local.db_backup_clusters

  cluster_name    = local.cluster_name
  namespace       = each.value.namespace
  service_account = each.value.sa
  role_arn        = aws_iam_role.db_backups.arn
}

output "db_backups_bucket" {
  value = aws_s3_bucket.db_backups.bucket
}
