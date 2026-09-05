# ─────────────────────────────────────────────────────────────────────────────
# Service image repositories (ECR private)
# ─────────────────────────────────────────────────────────────────────────────
# Until this file existed, `git grep aws_ecr_repository` over the whole monorepo
# returned NOTHING: every openbank-* image repository in this account existed
# because a human ran `aws ecr create-repository` once. None was reviewable, none
# had its settings expressed anywhere, and nothing noticed one being deleted —
# the visible symptom being a new service's first build dying at `Docker push`
# with "The repository ... does not exist" (#3423, #3477).
#
# WHY THE SET IS DERIVED AND NOT TYPED
# A hand-typed `for_each` list would be a second copy of the fleet roster, free to
# drift from it — the exact shape this repo has been burned by repeatedly (the
# pact drift scope, the deploy-coverage list, the Dockerfile exemption list). So
# the set is READ OUT OF the artifacts that consume the registry:
#
#   1. every ECR image reference pinned in openbank-infra/gitops/** — i.e. every
#      image ArgoCD actually pulls. That is the definition of "needs a repository":
#      a workload that references it.
#   2. the CI runner image, taken from `local.runner_image` in arc-runners.tf —
#      the one openbank-* image with no gitops workload, and derived from the very
#      string the runner pods pull rather than re-typed.
#
# Measured 2026-08-03 against the live registry: the gitops-derived set is 60
# names and the live account holds exactly those 60 plus openbank-ci-runner. The
# two agree with no exceptions in either direction, which is why (1)+(2) is used
# as the source rather than release-please's package list — that list disagrees
# BOTH ways. It omits the six deployed images that are not released components
# (analytics-sink, developer-portal, document-renderer, keycloak, pyroscope-agent,
# ci-runner) and includes openbank-tax-reporting-service, a released component
# with no gitops workload, no auto-deploy entry and — correctly — no repository.
# Deriving from it would have created a seventh unused repository and left six
# real ones undeclared.
#
# A new service therefore gets its repository from the same PR that gives it a
# gitops manifest, which is necessarily before its first image is pushed.
#
# NOT AN AUTO-CREATION AT PUSH TIME. .github/scripts/ensure-ecr-repository.sh
# (#3492) stays as the fail-open runtime backstop for the window between a manual
# `create-repository` and this file being applied; it is not the declaration.

locals {
  gitops_dir = "${path.module}/../../../gitops"

  # Both extensions are in use under gitops/.
  gitops_manifest_files = setunion(
    fileset(local.gitops_dir, "**/*.yaml"),
    fileset(local.gitops_dir, "**/*.yml"),
  )

  # An image reference, not a mention: the account-qualified ECR host, then the
  # repository name, then a tag or a digest separator. Requiring the trailing
  # [:@] is what keeps the Kyverno wildcard rules ("…amazonaws.com/openbank-*")
  # out of the set — `*` is not a repository name, and a policy pattern is not a
  # workload pin.
  gitops_image_repositories = toset(flatten([
    for f in local.gitops_manifest_files : [
      for m in regexall(
        "[0-9]{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com/(openbank-[a-z0-9-]+)[:@]",
        file("${local.gitops_dir}/${f}")
      ) : m[0]
    ]
  ]))

  # openbank-ci-runner has no gitops workload — ARC pulls it via the Helm values
  # in arc-runners.tf. Split out of that same string so the two can never disagree.
  ci_runner_repository = split("@", split("/", local.runner_image)[1])[0]

  # A first image must exist before an independently reviewed GitOps workload can
  # reference it. Incentive is the one bounded bootstrap exception: this creates
  # its empty registry namespace only; it does not declare a workload, image tag,
  # network edge, or live service. Remove this entry in the same PR that adds the
  # first exact GitOps image pin. The resource precondition below prevents this exception from
  # silently becoming permanent after that pin exists.
  # Empty since 2026-09-03: gitops/components/incentive/incentive-service.yaml now
  # carries the first exact image pin (sandbox-bd090160), so the bootstrap entry
  # graduated to the pinned set as its own precondition required. Keep the local +
  # precondition: the next bounded bootstrap exception lands here the same way.
  bootstrap_service_ecr_repositories = toset([])

  service_ecr_repositories = setunion(
    local.gitops_image_repositories,
    toset([local.ci_runner_repository]),
    local.bootstrap_service_ecr_repositories,
  )

  # Tag immutability, per repository, defaulting to MUTABLE.
  #
  # MUTABLE is load-bearing for the service repositories, not laziness: auto-deploy
  # tags `sandbox-<sha>` and the reconcile path (#2021, #3432) legitimately
  # re-dispatches a build for a sha it has already pushed. Under IMMUTABLE that
  # re-push fails, so flipping the fleet would break the self-heal that exists to
  # rescue stranded deploys.
  #
  # openbank-admin-ui is IMMUTABLE in the live account and stays that way — it is
  # built by its own workflow (build-push-admin-ui.sh) off a version, not a sha,
  # and tightening is never reverted here to make a plan quieter.
  ecr_immutable_repositories = toset(["openbank-admin-ui"])
}

resource "aws_ecr_repository" "service" {
  for_each = local.service_ecr_repositories

  name                 = each.key
  image_tag_mutability = contains(local.ecr_immutable_repositories, each.key) ? "IMMUTABLE" : "MUTABLE"

  # AES256 (the ECR-managed key) rather than KMS: matches every repository that
  # exists today, and a KMS switch is a destroy-and-recreate, not an in-place edit.
  encryption_configuration {
    encryption_type = "AES256"
  }

  # Scan-on-push is also owned registry-level for openbank-* by
  # aws_ecr_registry_scanning_configuration in ecr-image-scanning.tf, so this is
  # belt-and-braces rather than the only lever — but it is stated here on purpose.
  # OMITTING the block is not neutral: the provider plans `scan_on_push = true ->
  # null` on every repository that has it, i.e. leaving it out would have QUIETLY
  # TURNED SCANNING OFF on 51 of the 61 as the price of a tidier file. The live
  # per-repository values are inconsistent (10 read false, created before the
  # registry rule); declaring `true` converges those ten upward in place and is a
  # no-op on the rest.
  image_scanning_configuration {
    scan_on_push = true
  }

  # A repository is deleted only by a human who meant it. Removing a service's
  # gitops manifest must not propose dropping the registry namespace its running
  # pods still pull from — that would be a plan nobody reads carefully enough.
  # Retiring a service is then deliberately two steps: drop the pin, then remove
  # this guard in a PR that says so.
  #
  # Honest limit: prevent_destroy protects an instance that is IN STATE. Until the
  # first apply adopts these 61, dropping a gitops pin removes a name from the
  # derived set with nothing to object — there is no state entry to destroy. That
  # window closes on the first apply, not on this merge.
  lifecycle {
    prevent_destroy = true

    # Unlike a top-level check (which warns), a lifecycle precondition blocks
    # both plan and apply. This forces the one-time bootstrap entry out in the
    # same change that introduces its first exact GitOps image pin.
    precondition {
      condition     = length(setintersection(local.bootstrap_service_ecr_repositories, local.gitops_image_repositories)) == 0
      error_message = "Remove a bootstrap ECR repository once its first GitOps image pin is declared."
    }
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# Adoption of the repositories that already exist
# ─────────────────────────────────────────────────────────────────────────────
# 61 repositories predate this file. Without this block the first plan proposes
# to CREATE all 61, and the apply fails on RepositoryAlreadyExistsException.
#
# The import set is the INTERSECTION of the derived set with what the registry
# actually holds, so this block is correct permanently rather than being a
# one-shot migration file someone must remember to delete: an existing repository
# is adopted, a brand-new service's repository is created, and a repository
# already in state makes the import a no-op. A static import list would have
# broken the plan for the next new service ("Cannot import non-existent remote
# object") — the failure mode a migration artifact left in place always has.
data "aws_ecr_repositories" "existing" {}

import {
  for_each = setintersection(
    local.service_ecr_repositories,
    toset(data.aws_ecr_repositories.existing.names),
  )

  to = aws_ecr_repository.service[each.value]
  id = each.value
}

output "service_ecr_repository_names" {
  description = "Every openbank-* image repository declared here (derived from the gitops image pins plus the ARC runner image)."
  value       = sort(keys(aws_ecr_repository.service))
}
