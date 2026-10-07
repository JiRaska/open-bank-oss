# OpenBank public status

The public page is in English at `https://status.open-bank.tech/`. It is served
from a separate private S3 bucket through CloudFront. A regional Lambda runs
external checks every two minutes and stores public aggregate results in a
separate private S3 bucket. The public API is a Lambda function URL accessible
only through the CloudFront distribution's signed origin access control.

Deploy infrastructure with the `openbank-infra/aws/envs/web-prod` OpenTofu
stack, then run `AWS_PROFILE=openbank ./openbank-infra/web/status/deploy.sh` from
the repository root. The scheduled checker publishes its first sample within
two minutes. The site remains **Unable to verify** until that sample arrives.

## Public API

- `GET /api/v1/status` returns page data, recent incidents, and observed history.
- `GET /api/v1/health` returns HTTP 200 only for fresh, operational results;
  outage, pending verification, or stale data returns HTTP 503 with JSON.
- `GET /api/v1/history` returns 24-hour and 30-day website probe buckets.
- `GET /api/v1/incidents` returns the latest confirmed incident timeline.
- `GET /api/v1/healthz` checks the status API itself, independent of upstream
  service state.
- `GET /api/v1/freshness` returns 200 only while scheduled results are fresh,
  regardless of whether monitored services are up or down.
- `/openapi.yaml` is the public OpenAPI 3.1 contract.

The website check requires both names to resolve via Cloudflare and Google DNS,
both HTTPS pages to pass certificate validation, and both pages to contain the
OpenBank identity. The customer login check validates OIDC discovery. The API
edge check only proves reachability and TLS; it is **not** a transaction probe.
Three failed samples confirm an incident and two successful samples confirm
recovery. No missing sample counts as uptime. Historical percentages cover only
observed samples and begin when this checker first runs.

Mobile customer journeys, payment success, real-user latency, and contractual
SLA attainment are outside the public badge until they have appropriate public
measurements and disclosure review. The educational SLO calculator uses
hypothetical targets and must never be presented as an OpenBank commitment.

The generated mascot images have transparent backgrounds. The two scene poses
cycle to animate the characters; the incident poses show blue safety helmets.
`prefers-reduced-motion` displays a still frame.
