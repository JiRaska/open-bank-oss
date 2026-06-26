#!/usr/bin/env bash
set -euo pipefail

TOTAL_HEALTHY=0
TOTAL_UNHEALTHY=0
TOTAL_STARTING=0
COMPLIANCE_GATE_OK=true
TIER2_UNHEALTHY=0

echo "╔══════════════════════════════════════════════════════╗"
echo "║         OpenBank BCP Health Check                    ║"
echo "╚══════════════════════════════════════════════════════╝"
echo "Timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo ""

check_tier() {
  local tier_num="$1"
  local tier_label="$2"
  shift 2

  echo "── Tier ${tier_num}: ${tier_label} ──────────────────────────"

  for container in "$@"; do
    local health running
    health=$(docker inspect "$container" \
      --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' \
      2>/dev/null || echo "not_found")
    running=$(docker inspect "$container" --format='{{.State.Status}}' 2>/dev/null || echo "not_found")

    if [ "$health" = "healthy" ]; then
      echo "  ✅ $container"
      TOTAL_HEALTHY=$((TOTAL_HEALTHY + 1))
    elif [ "$health" = "starting" ]; then
      echo "  ⏳ $container — starting"
      TOTAL_STARTING=$((TOTAL_STARTING + 1))
    elif [ "$health" = "no-healthcheck" ] && [ "$running" = "running" ]; then
      echo "  ℹ️  $container — running (no healthcheck)"
      TOTAL_HEALTHY=$((TOTAL_HEALTHY + 1))
    elif [ "$running" = "not_found" ] || [ "$health" = "not_found" ]; then
      echo "  ❌ $container — NOT RUNNING"
      TOTAL_UNHEALTHY=$((TOTAL_UNHEALTHY + 1))
      [ "$tier_num" = "2" ] && TIER2_UNHEALTHY=$((TIER2_UNHEALTHY + 1))
    elif [ "$running" = "created" ] || [ "$running" = "exited" ]; then
      echo "  ❌ $container — stopped (${running})"
      TOTAL_UNHEALTHY=$((TOTAL_UNHEALTHY + 1))
      [ "$tier_num" = "2" ] && TIER2_UNHEALTHY=$((TIER2_UNHEALTHY + 1))
    else
      echo "  ❌ $container — ${health} (${running})"
      TOTAL_UNHEALTHY=$((TOTAL_UNHEALTHY + 1))
      [ "$tier_num" = "2" ] && TIER2_UNHEALTHY=$((TIER2_UNHEALTHY + 1))
    fi
  done
  echo ""
}

check_tier 0 "Infrastructure Prerequisites" \
  openbank-postgres openbank-kafka openbank-keycloak \
  openbank-vault openbank-valkey openbank-schema-registry

check_tier 1 "Core Ledger & Identity" \
  openbank-account-service openbank-ledger-service \
  openbank-transaction-service openbank-party-service openbank-audit-service

check_tier 2 "Compliance Gate (5AMLD Art.18 / DORA Art.12)" \
  openbank-balance-service openbank-aml-service openbank-sanctions-service \
  openbank-kyc-service openbank-security-scanner openbank-notification-service

check_tier 3 "PSD2 / SCA (PSD2 Art.97)" \
  openbank-sca-service openbank-consent-service \
  openbank-tpp-registry-service openbank-pid-service openbank-psd2-service

check_tier 4 "Payment Processing" \
  openbank-domestic-payment openbank-sepa-payment openbank-sepa-instant-service \
  openbank-swift-service openbank-fx-service openbank-clearing-service \
  openbank-standing-order-service openbank-card-issuance-service

check_tier 5 "Operations & Observability" \
  openbank-dispute-service openbank-interest-service openbank-admin-ui \
  openbank-grafana openbank-prometheus openbank-loki openbank-tempo \
  openbank-kafka-ui openbank-mailhog

[ "$TIER2_UNHEALTHY" -gt 0 ] && COMPLIANCE_GATE_OK=false

echo "══════════════════════════════════════════════════════"
echo "SUMMARY"
echo "══════════════════════════════════════════════════════"
echo "  Healthy:   $TOTAL_HEALTHY"
echo "  Starting:  $TOTAL_STARTING"
echo "  Unhealthy: $TOTAL_UNHEALTHY"
echo ""

if [ "$COMPLIANCE_GATE_OK" = false ]; then
  echo "⛔ COMPLIANCE GATE FAILED — Payment processing BLOCKED"
  echo "   AML/Sanctions/Balance must be healthy before payments can run."
  echo "   Regulatory basis: 5AMLD Art. 18, DORA Art. 12"
  echo ""
fi

if [ "$TOTAL_UNHEALTHY" -gt 0 ]; then
  echo "🔴 BCP STATUS: DEGRADED"
  echo "   Runbook: docs/strategy/10-business-continuity-plan.md § 5.4"
  exit 1
elif [ "$TOTAL_STARTING" -gt 0 ]; then
  echo "🟡 BCP STATUS: RECOVERING ($TOTAL_STARTING service(s) starting)"
  exit 2
else
  echo "🟢 BCP STATUS: NOMINAL — all services healthy"
  [ "$COMPLIANCE_GATE_OK" = true ] && echo "   ✅ Compliance gate: CLEAR — payments authorized"
  exit 0
fi
