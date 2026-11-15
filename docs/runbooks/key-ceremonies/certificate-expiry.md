# Certificate expiry alerts

What to do when one of the `openbank.cert-expiry` alerts fires
(`openbank-infra/gitops/components/observability/prometheus-rules-cert-expiry.yaml`).
Public URL expiry is covered separately by `TLSCertExpiringSoon` / `TLSCertExpiringCritical`
on the synthetic probes.

Never print, copy or attach a certificate's private key, a keystore or a Secret's data while
working through this. Everything below reads certificate *status* and public metadata only.

## What breaks when a certificate expires

| Certificate | What stops working |
|---|---|
| A service's `*-internal-tls` leaf | Callers of that service fail the mTLS handshake on 8443. |
| `openbank-sandbox-ca` (root CA) | Every leaf issued by `openbank-ca` stops validating at once: east-west mTLS across the fleet. |
| Strimzi cluster CA | Brokers present certificates clients no longer trust; every event flow stops. |
| Strimzi clients CA | Every `KafkaUser` certificate is rejected by the broker. |
| `openbao-tls` | Clients of OpenBao (ESO, dynamic DB credentials, PKI issuance) fail their TLS connection. |

## InternalCertRenewalFailing / InternalCertExpiringCritical

1. Read why cert-manager did not renew:
   `kubectl describe certificate -n <namespace> <name>` and the newest
   `kubectl get certificaterequest -n <namespace>`. Typical causes: the issuer is not Ready,
   the CA secret is missing, or (for `letsencrypt-prod`) a DNS-01 challenge is stuck.
2. Fix the cause, then force a renewal: `cmctl renew -n <namespace> <name>`.
3. Confirm the workload serves the NEW certificate. Several workloads load their certificate
   only at start (OpenBao documents this in `openbao-tls.yaml`), so a renewed Secret is not a
   renewed listener. Restart the workload if the served `notAfter` did not move.

## CertificateAuthorityExpiring

A CA cannot rescue itself: `openbank-sandbox-ca` declares `rotationPolicy: Never`. Treat this
as a planned ceremony, not an incident, and follow
[cert-manager PKI recovery](cert-manager-pki-recovery.md) with its pre-check, two-person
execution and evidence phases. Schedule it well inside the 60-day warning window.

## KafkaCertificateAuthorityExpiring

Strimzi renews its CAs `renewalDays` (30) before expiry during reconciliation. Inside 21 days
that did not happen: check the cluster operator is running and reconciling
(`kubectl logs -n messaging deploy/strimzi-cluster-operator`) and the `Kafka` resource's
`status.conditions`. See `docs/runbooks/0008-kafka-mtls-scheme-accepted-cutover.md` for the
client-side mTLS layout.

## CertManagerExpiryMetricsAbsent / KafkaCaExpiryMetricsAbsent

The expiry alerts above evaluate to nothing when their metric is missing, which looks exactly
like "nothing is expiring". These alerts say the signal is gone. Check the PodMonitor in
`podmonitor-cert-expiry.yaml`, that the operator pod is running, and the target's state on the
Prometheus targets page. For Strimzi, also confirm the operator version emits
`strimzi_certificate_expiration_timestamp_ms`.
