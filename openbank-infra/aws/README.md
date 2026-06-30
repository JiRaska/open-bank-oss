# openbank-infra/aws — OpenTofu substrate (ADR-0027)

Phase-0 AWS infrastructure as code. OpenTofu only (not Terraform — BSL license
conflicts with the OSS mandate). Day-0 substrate lives here; day-2 platform and
apps are owned by ArgoCD (not yet wired).

> **Region:** `eu-north-1` for the phase-0 sandbox (where the account is set up).
> Production is pinned to `eu-central-1` per ADR-0027's GDPR condition — a
> separate account, not provisioned here.

## Layout

```
bootstrap/          One-time: the S3 bucket holding remote state (local state).
modules/runner/     Reusable self-hosted GitHub Actions runner (VPC + EC2),
                    arch-parameterized (var.arch = arm64 | x86_64).
envs/sandbox/       Sandbox env — instantiates TWO runners (arm64 + x86_64),
                    S3 remote state.
```

### Why two runners (arch split)

Most CI runs on a single arm64 Graviton box (cheap, fits the JVM build matrix).
But a few tools have **no arm64 build** and must run on x86_64:

- **CodeQL CLI** — does not support `linux/arm64` at all (fails at init).
- **shellcheck** (via `ludeeus/action-shellcheck`) — ships an x86_64-only binary.

So `envs/sandbox` stands up a second, smaller x86_64 runner labelled
`openbank-sandbox-x64`, and only the `CodeQL` and `Validate manifests` jobs
target it. Everything else stays on the arm64 `openbank-sandbox` runner.

## First apply

The first deliverable is a self-hosted CI runner, to unblock GitHub Actions
without making the repo public or paying for hosted minutes.

### 1. Create the state bucket (once)

```bash
cd bootstrap
tofu init
tofu apply
```

### 2. Stage a one-shot registration token, then provision

No long-lived secret is stored. A ~1h registration token is minted with `gh`,
parked in a temporary SSM SecureString, read by the instance at boot, and then
**deleted by the instance itself**. The token value never enters OpenTofu
config/state or instance user-data — only the parameter *name* does.

Each runner reads its own one-shot token, so stage **both** before applying
(each instance self-deletes its own parameter at boot):

```bash
cd ../envs/sandbox

mint() { gh api -X POST repos/JiRaska/open-bank-oss/actions/runners/registration-token -q .token; }

# arm64 runner token:
mint | xargs -I{} aws ssm put-parameter --profile openbank --region eu-north-1 \
  --type SecureString --name /openbank/sandbox/reg-token --value {} --overwrite

# x86_64 runner token (distinct parameter):
mint | xargs -I{} aws ssm put-parameter --profile openbank --region eu-north-1 \
  --type SecureString --name /openbank/sandbox/reg-token-x64 --value {} --overwrite

tofu init
tofu plan      # review
tofu apply     # both instances register, then self-delete their SSM parameters
```

Tokens expire in ~1h regardless. To (re-)provision just one runner later, stage
only its token and `tofu apply` — the other is left untouched.

The instances auto-register as `openbank-sandbox-runner` (labels
`self-hosted, linux, arm64, openbank-sandbox`) and `openbank-sandbox-x64-runner`
(labels `self-hosted, linux, x64, openbank-sandbox-x64`). Confirm under
**Settings → Actions → Runners**, then target them via
`runs-on: [self-hosted, openbank-sandbox]` / `[self-hosted, openbank-sandbox-x64]`.

### Shell access

No SSH / no inbound ports. Use SSM Session Manager:

```bash
aws ssm start-session --target <instance_id>
sudo cat /var/log/openbank-runner-bootstrap.log   # boot diagnostics
```

## Cost (sandbox, on-demand, eu-north-1, approx)

| Resource                      | ~Monthly |
|-------------------------------|----------|
| arm64 t4g.xlarge (24/7)       | ~EUR 88  |
| x86_64 t3.large (24/7)        | ~EUR 60  |
| 2× 40 GiB gp3 root            | ~EUR 7   |
| 2× Public IP (IPv4)           | ~EUR 7   |
| **Total**                     | **~EUR 162** |

The arm64 t4g.xlarge (4 vCPU / 16 GiB) carries the per-service Gradle build+test
matrix; the x86_64 t3.large (2 vCPU / 8 GiB) only runs CodeQL + shellcheck (no
arm64 build — see "Why two runners"). No NAT gateway (runners sit in public
subnets, egress-only SG). **Stop both instances when idle** to cut compute to
~zero (storage persists) — the biggest lever on this bill; CodeQL/shellcheck
only need the x64 box up while a scan is running.
