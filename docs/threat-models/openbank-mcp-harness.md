<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-mcp-harness

The image runs the private sandbox HTTPS router and the suspended browser test
runner. Both operate on synthetic data. See ADR-0383 and the MCP synthetic
sandbox runbook.

## Data flow and trust boundaries

The runner connects to the private router using its sandbox CA. The router
connects only to configured HTTPS MCP, OAuth and Customer Edge origins. The
runner mounts the synthetic fixture secret and keeps its WebAuthn counter on a
volume shared across scheduled and manual runs. The router has separate
server and client certificates and does not mount fixture credentials.

## STRIDE analysis

| Threat | Mitigation |
| --- | --- |
| Spoofing | The browser trusts the dedicated CA. The router validates certificates for the configured upstream identities. |
| Tampering | Both workloads pin the approved image digest. The runner serializes access to the credential counter. |
| Repudiation | Job and deployment revisions identify which build ran. The bank services retain their normal approval and audit checks. |
| Information disclosure | Network policies restrict the router to the runner and named peers. Fixture keys are mounted only in the runner. |
| Denial of service | The runner starts suspended and forbids overlapping jobs. Resource limits bound CPU, memory and temporary storage. |
| Elevation of privilege | Pods do not automount Kubernetes API credentials. The fixture is confined to a synthetic customer and its own account. |

## Residual risks

A compromised runner can act as the synthetic customer until its grants and
credentials are revoked. Provisioning must demonstrate isolation from
customer data before the runner is enabled. Track certificate expiry,
credential rotation and fixture enrollment as deployment prerequisites.
