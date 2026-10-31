# OpenBank public status

The public page is in English at `https://status.open-bank.tech/`. It is served
from a separate private S3 bucket through CloudFront. A regional Lambda runs
external checks every two minutes and stores public aggregate results in a
separate private S3 bucket. The public API is a Lambda function URL accessible
only through the CloudFront distribution's signed origin access control.
An in-cluster CronJob queries the private Prometheus service and writes only
two allowlisted service-reachability verdicts to one object in that private
bucket. EKS Pod Identity grants write access to that object alone; the public
API has read access alone. It has no route or credentials to query Prometheus.

Deploy the `web-prod` and `sandbox-platform` OpenTofu stacks before syncing the
export CronJob, then run `./openbank-infra/web/status/deploy.sh` with the normal
AWS profile from the repository root. The status remains **Unable to verify**
until both the external checker and internal exporter have fresh samples.

## Public API

- `GET /api/v1/status` returns page data, recent incidents, and observed history.
- `GET /api/v1/health` returns HTTP 200 only for fresh, operational results;
  outage, pending verification, or stale data returns HTTP 503 with JSON.
- `GET /api/v1/history` returns 24-hour and 30-day website probe buckets.
- `GET /api/v1/incidents` returns the latest confirmed incident timeline.
- `GET /api/v1/healthz` checks the status API itself, independent of upstream
  service state.
- `GET /api/v1/freshness` returns 200 only while both scheduled data sources
  are fresh and verifiable, regardless of service impact.
- `/openapi.yaml` is the public OpenAPI 3.1 contract.

The website check requires both names to resolve via Cloudflare and Google DNS,
both HTTPS pages to pass certificate validation, and both pages to contain the
OpenBank identity. The customer login check validates OIDC discovery. The API
edge check only proves reachability and TLS; it is **not** a transaction probe.
Three failed samples confirm an incident and two successful samples confirm
recovery. No missing sample counts as uptime. Historical percentages cover only
observed samples and begin when this checker first runs.

The two internal verdicts indicate that selected core and payment services
respond to monitoring. They do not prove a completed customer journey or
payment. Missing services, failed queries, or an old export become **unknown**;
the health endpoint returns 503. No raw metric labels, service inventory, or
internal endpoint is included in the public JSON.

Mobile customer journeys, payment completion, real-user latency, and contractual
SLA attainment are outside the public badge until they have appropriate
measurements and disclosure review. The educational SLO calculator uses
hypothetical targets and must never be presented as an OpenBank commitment.

The generated mascot images have transparent backgrounds. The two scene poses
cycle to animate the characters; the incident poses show blue safety helmets.
`prefers-reduced-motion` displays a still frame.
