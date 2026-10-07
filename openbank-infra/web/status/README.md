# Public status site

This site is served from a dedicated private S3 origin through CloudFront at
`status.open-bank.tech`. Its read-only `/api/status.json` is produced by an
external GitHub runner, so a banking-cluster outage cannot make the status page
appear healthy merely because an in-cluster observer stopped running.

## Rollout

1. Review and apply `openbank-infra/aws/envs/status-prod` with operator-supplied
   S3 backend configuration and a globally unique `bucket_name`. Keep this state
   separate from the banking substrate state.
2. Set repository variables `STATUS_PUBLIC_BUCKET`, `STATUS_DISTRIBUTION_ID`,
   and `STATUS_PUBLISH_ROLE_ARN` from the reviewed OpenTofu outputs. The publisher
   role is limited to this repo's protected `main`, the status origin and its CDN.
3. Merge the status site and synthetic probes through normal CI and review.
   Dispatch **Public status evidence** once with `bootstrap=true`. Scheduled
   runs thereafter require the prior JSON; a missing or corrupt history fails
   publishing instead of silently claiming a full observation window.
4. Verify DNS, TLS, `/`, `/api/status.json`, the Prometheus page/API/DNS probes,
   missing-probe alerts, and a deliberately failed probe. Confirm the page
   changes to **unverified** when `expires_at` passes without a fresh publish.
5. Let 24-hour and 30-day history accumulate from actual checks. The page shows
   insufficient coverage until at least 95% of expected five-minute samples
   exist; it never backfills unobserved time as healthy.

The `incidents.json` file is a reviewed list of confirmed incidents. Each record
needs `id`, `summary`, `started_at`, `confirmed: true`, and optionally
`resolved_at`. A recovery time is published only after confirmation. Do not put
customer data or internal incident details in this public file.

Local checks:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest openbank-infra/web/status/test_checks.py
node --test openbank-infra/web/status/status.test.cjs
actionlint .github/workflows/public-status.yml
tofu -chdir=openbank-infra/aws/envs/status-prod init -backend=false
tofu -chdir=openbank-infra/aws/envs/status-prod validate
```
