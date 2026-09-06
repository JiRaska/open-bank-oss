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

data "aws_iam_policy_document" "db_backups_policy" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.db_backups.arn,
      "${aws_s3_bucket.db_backups.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "db_backups" {
  bucket     = aws_s3_bucket.db_backups.id
  policy     = data.aws_iam_policy_document.db_backups_policy.json
  depends_on = [aws_s3_bucket_public_access_block.db_backups]
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
    ledger = { namespace = "ledger", sa = "ledger-db" }
    # Restore-drill slot: permanent SA for point-in-time restore tests (runbook-0003).
    # Named "ledger-db-drill" so it never conflicts with the live cluster SA.
    ledger-drill = { namespace = "ledger", sa = "ledger-db-drill" }
    balances     = { namespace = "balances", sa = "balances-db" }
    accounts     = { namespace = "accounts", sa = "accounts-db" }
    kyc          = { namespace = "kyc", sa = "kyc-db" }
    consent      = { namespace = "consent", sa = "consent-db" }
    sca          = { namespace = "sca", sa = "sca-db" }
    audit        = { namespace = "audit", sa = "audit-db" }
    sanctions    = { namespace = "sanctions", sa = "sanctions-db" }
    # Extended fleet — all remaining clusters with barmanObjectStore to openbank-sandbox-db-backups
    aml              = { namespace = "aml", sa = "aml-db" }
    dispute          = { namespace = "dispute", sa = "dispute-db" }
    fraud            = { namespace = "fraud", sa = "fraud-db" }
    fx               = { namespace = "fx", sa = "fx-db" }
    keycloak         = { namespace = "iam", sa = "keycloak-db" }
    interest         = { namespace = "interest", sa = "interest-db" }
    lending          = { namespace = "lending", sa = "lending-db" }
    apicurio         = { namespace = "messaging", sa = "apicurio-db" }
    notifications    = { namespace = "notifications", sa = "notifications-db" }
    onboarding       = { namespace = "onboarding", sa = "onboarding-db" }
    pact-broker      = { namespace = "pact-broker", sa = "pact-broker-db" }
    party            = { namespace = "party", sa = "party-db" }
    card-issuance    = { namespace = "payments", sa = "card-issuance-db" }
    settlement       = { namespace = "payments", sa = "settlement-service-db" }
    swift-service    = { namespace = "payments", sa = "swift-service-db" }
    transaction      = { namespace = "payments", sa = "transaction-db" }
    sepa-payment     = { namespace = "payments", sa = "sepa-payment-db" }
    domestic-payment = { namespace = "payments", sa = "domestic-payment-db" }
    sepa-instant     = { namespace = "payments", sa = "sepa-instant-db" }
    clearing         = { namespace = "payments", sa = "clearing-db" }
    standing-order   = { namespace = "payments", sa = "standing-order-db" }
    pid              = { namespace = "pid", sa = "pid-db" }
    agent            = { namespace = "platform", sa = "agent-db" }
    case-coordinator = { namespace = "platform", sa = "case-coordinator-db" }
    psd2             = { namespace = "psd2", sa = "psd2-db" }
    security-scanner = { namespace = "security-scanner", sa = "security-scanner-db" }
    temporal         = { namespace = "temporal", sa = "temporal-db" }
    # Added by #1444. These three declared barmanObjectStore -> this bucket but were never
    # added here, so every WAL archive failed with "Unable to locate credentials" and they had
    # no backups at all — sdd-db for three days, alerting the whole time. The comment above
    # claiming "all remaining clusters" was simply untrue; check-db-backup-associations.py now
    # asserts it instead of trusting it.
    sdd          = { namespace = "sdd", sa = "sdd-db" }
    tpp-registry = { namespace = "tpp-registry", sa = "tpp-registry-db" }
    vop          = { namespace = "payments", sa = "vop-db" }
    # Added by #1444 (second wave). These 11 declared NO backup at all — they never even
    # attempted an archive, so nothing alerted, and they would have had no recovery point the
    # first time anyone needed one. The matching barmanObjectStore + ScheduledBackup + a bounded
    # max_wal_size were added to their gitops manifests in the same change.
    anacredit  = { namespace = "anacredit", sa = "anacredit-db" }
    authzaudit = { namespace = "authz-policy-auditor", sa = "authzaudit-db" }
    billing    = { namespace = "billing", sa = "billing-db" }
    # campaign was the ONE cluster in the fleet whose backups had never worked. Its
    # gitops manifest declares a barmanObjectStore (s3://…/campaign-db) and a
    # ScheduledBackup, so Postgres kept trying to archive and kept failing —
    # `barman-cloud-wal-archive: exit status 4`, ContinuousArchiving=False, ~48h of
    # PostgresWALArchiveFailing. Backups were switched on and the permission to
    # perform them never was.
    #
    # This is NOT the 2026-07-19 failure mode (#1759), where the association existed
    # and EKS Pod Identity simply missed injecting credentials at admission during a
    # node roll; there the fix was a pod restart. Checked here first: the agent is
    # 31/31 healthy and a restart still produced a pod with no
    # AWS_CONTAINER_CREDENTIALS_FULL_URI, because there was no association to inject.
    #
    # Measured 2026-08-02 across the live fleet: 52 CNPG clusters declare
    # barmanObjectStore, 51 archive fine, and campaign was the only one broken.
    campaign         = { namespace = "campaign", sa = "campaign-db" }
    referral         = { namespace = "referral", sa = "referral-db" }
    devops           = { namespace = "devops-agent", sa = "devops-db" }
    docstruth        = { namespace = "docs-truth-agent", sa = "docstruth-db" }
    document-service = { namespace = "documents", sa = "document-service-db" }
    finops           = { namespace = "finops-agent", sa = "finops-db" }
    flakytest        = { namespace = "flaky-test-hunter", sa = "flakytest-db" }
    govaudit         = { namespace = "governance-auditor", sa = "govaudit-db" }
    liveness         = { namespace = "control-liveness-sentinel", sa = "liveness-db" }
    product-catalog  = { namespace = "accounts", sa = "product-catalog-db" }
    releasesteward   = { namespace = "release-steward", sa = "releasesteward-db" }
    # delegation-db is the FOURTH time this list has been the thing that lagged a new cluster,
    # and the first one where the gate built to prevent it had already said so. It shipped
    # 2026-08-02 with a barmanObjectStore, a ScheduledBackup, and no association, so every WAL
    # archive failed from the first minute:
    #
    #   Barman cloud WAL archive check exception: Unable to locate credentials
    #
    # Confirmed the same way campaign was, rather than assumed: the pod carries no
    # AWS_CONTAINER_CREDENTIALS_FULL_URI at all (accounts-db-1 does), so this is a missing
    # association and not the #1759 admission-injection race a restart fixes. IMDS is
    # unreachable from pods, so there is no fallback credential path — the archive can only
    # ever fail.
    #
    # check-db-backup-associations.py flagged it correctly and the change merged anyway,
    # because the gate was advisory. That is the actual defect this entry pays for, and it is
    # fixed alongside: a check that can only ever be right about "this database has no
    # backups" has no judgement left to exercise, so advisory just made it mergeable. The gate
    # is now enforced.
    delegation = { namespace = "delegation", sa = "delegation-db" }
    kyb        = { namespace = "kyb", sa = "kyb-db" }
    # Added by #3555 with their barmanObjectStore + ScheduledBackup in the same change — the two
    # clusters that still declared NO backup at all, out of 55. Both are `instances: 1`, so they
    # had neither a replica nor a recovery point: a lost EBS volume was total data loss.
    #
    # They are the counterpart to the second wave above, and they stayed hidden for a different
    # reason. mcp-db's runbook actively asserted the opposite — "RPO target: <= 5 min (continuous
    # archiving)" — because the runbook generator decided "has a backup" with a whole-file
    # substring test that matched the OPA bundle's embedded rules.yaml prose (#3508, #3551).
    # litellm-db carried a comment declining backups "matching devops-agent's pattern", written
    # after #1444/#1452 had already given devops-agent and the other seven control-plane agent
    # DBs a barmanObjectStore each. Two different kinds of false statement, one effect.
    mcp     = { namespace = "platform", sa = "mcp-db" }
    litellm = { namespace = "ai-platform", sa = "litellm-db" }
    # langfuse-db arrives with the self-hosted LLM-observability store (ADR-0265). Same reason as
    # every entry above: its Cluster declares a barmanObjectStore into this bucket, and without the
    # association here every WAL archive fails with "Unable to locate credentials" while the pod
    # stays Ready — a cluster with no recovery point and nothing anywhere going red about it. The
    # `db-backup-association` gate is what caught this one before it merged.
    langfuse = { namespace = "ai-platform", sa = "langfuse-db" }
    # copilot-db arrives with the durable conversation-history store (#3710). Its gitops
    # manifest declares a barmanObjectStore into this bucket, so without the association here
    # every WAL archive would fail with "Unable to locate credentials" and the cluster would
    # have no recovery point at all — the #1444 failure mode, caught this time by
    # check-db-backup-associations.py before the cluster ever existed rather than days after.
    copilot = { namespace = "platform", sa = "copilot-db" }
    # engagement-db was the last cluster in the fleet with NO backup at all — no
    # barmanObjectStore, therefore no archive attempt, therefore no alert. The loud version of
    # this (campaign-db, above) failed for ~48h and was visible the whole time; the silent
    # version is worse and had been true since the cluster was created. Enumerated from the
    # `kind: Cluster` manifests rather than from the rollout comments in this file, which have
    # asserted "all remaining clusters" incorrectly three times now (#1444).
    engagement = { namespace = "engagement", sa = "engagement-db" }
    # Reserve the Pod Identity association before the reviewed Incentive CNPG Cluster is synced.
    # The future Cluster uses barmanObjectStore under incentive-db; declaring its service account
    # here prevents the otherwise silent "Ready but no WAL archive credentials" failure at first boot.
    incentive = { namespace = "incentive", sa = "incentive-db" }
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
