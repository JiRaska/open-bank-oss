# Auto deploy: why `ALL_SERVICES` is a static list, and every time it drifted

Moved verbatim from the comment above `ALL_SERVICES` in `.github/workflows/auto-deploy.yml`
(job `changes`, step `detect`). That step had grown past the `workflow-run-step-size` limit,
and history is prose, which belongs outside a `run:` script. `check-deploy-coverage.sh` is
the control that keeps the list in sync; this file is the record of why it exists.

Full fleet — unlike services-ci.yml (which discovers every openbank-*/
with a build.gradle.kts dynamically), this list is static and can drift:
a service added to the fleet gets full CI for free but silently gets ZERO
auto-deploy coverage until someone adds it here (openbank-libs excluded on
purpose: it has no Dockerfile, only its consumers need rebuilding).
billing-service shipped a CodeQL log-injection fix (#597/#601) that merged
and released cleanly but never deployed — "Detect changed services" always
printed "No deployable services changed" because the service was simply
never a candidate, regardless of what changed. Re-added here.
anacredit-service had the same gap PLUS no Dockerfile ever committed — the
running sandbox pod traces back to an image built from a Dockerfile that only
ever existed on someone's laptop. Neither build-push-service.sh nor this
workflow's own Docker-push step read a per-service Dockerfile for the build
itself (both generate their own from a canonical template); the per-service
Dockerfile is only grepped for its EXPOSE line to set the container port
(falls back to 8080 if absent). Added openbank-anacredit-service/Dockerfile
(EXPOSE 8137, matching the gitops manifest) alongside this entry so the port
extraction is correct, not just defaulted.
The 6 ADR-0163/ADR-0164-0168 control/governance agents (control-liveness-
sentinel, governance-auditor, release-steward, docs-truth-agent,
authz-policy-auditor, flaky-test-hunter) shipped with the exact same gap:
merged, released, gitops manifest present — but never in ALL_SERVICES, so
every one of their Deployments sat perpetually blocked at the placeholder
`sandbox-pending` tag (Kyverno's SBOM-attestation/cosign-signature policies
reject a tag that was never pushed) with zero auto-deploy coverage, and none
had a per-service Dockerfile either. Added both here.
document-service had the exact same gap: real Dockerfile (EXPOSE 8143), real
gitops manifest, ADR-0162 onboarding-agreement + ADR-0169 SCA-document-binding
PRs (#1137, #1142) merged and released — but never in ALL_SERVICES, so every
push since it shipped silently deployed nothing. Added here.
clearing-simulator: the same gap again, and live. Released (0.4.4), real
Dockerfile (EXPOSE 8139), real gitops manifest — but never in ALL_SERVICES,
so its manifest still pinned the image built on 2026-06-22 and every push
since deployed nothing. It also went unattested for the whole of that window
(its .att dates from the 2026-07-16 hand-fix, its .sig from June), because
the SBOM-attestation policy graduated to Enforce while nothing rebuilt it —
one reschedule in that window and it would have died like keycloak did.
Audit at the time: 50 released components, 47 here; the only legitimate
absentee is admin-ui (own admin-ui-deploy.yml). finrep-service is the third
and needs a Dockerfile first — see #1205, which also tracks a guard so this
list stops drifting out of sync with release-please by hand.
vop-service is the fourth, and it shipped straight into the gap: #1195 merged
it with a version.txt, a release-please entry and a Rollout, but no entry here,
so its auto-deploy run had nothing to build. Its manifest still pinned the
literal `sandbox-placeholder` tag, the ECR repo was never created, and Kyverno
therefore denied every pod (NAME_UNKNOWN on the SBOM-attestation and
cosign-signature policies) — the Rollout sat Degraded with zero pods from the
moment it landed. Added here with a Dockerfile carrying EXPOSE 8149.
finrep-service: the last of the six. Released 0.5.0 since June with a gitops
manifest pinned to the ADR-0097 `sandbox-init` placeholder and no ECR repo at
all, so it sat at 0/1 ErrImagePull and no push could ever fix it. Repo created
2026-07-16; first real image (sandbox-cef5fdcd) built, signed, attested and
running. Added here so it stays built. With this, ALL_SERVICES and
release-please's package list finally agree — and check-deploy-coverage.sh
keeps them agreeing (#1205).
analytics-sink: never listed here since the service was created, so NO push has ever
built it. Its deployed image was built 2026-07-26 (#2654) and nine commits touched
its src/main afterwards, none of which reached the cluster — including the
aggregate_type normalisation of #4576. Nothing was red: the run reports
"No deployable services changed" and succeeds. check-deploy-coverage.sh could not
see it either, because its subject set was release-please's released components and
analytics-sink has no version.txt (it owns no OLTP database and cuts no release —
ADR-0022). That blindness is fixed in the same PR; this line is the gap it found.
