#!/bin/bash
set -e

BOOTSTRAP="kafka:9092"
MAX_RETRIES=30
RETRY_INTERVAL=5
KAFKA_TOPICS_BIN="/opt/kafka/bin/kafka-topics.sh"

echo "Waiting for Kafka to be ready..."
for i in $(seq 1 $MAX_RETRIES); do
  if "$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP" --list > /dev/null 2>&1; then
    echo "Kafka is ready."
    break
  fi
  echo "Attempt $i/$MAX_RETRIES — Kafka not ready, retrying in ${RETRY_INTERVAL}s..."
  sleep $RETRY_INTERVAL
  if [ $i -eq $MAX_RETRIES ]; then
    echo "ERROR: Kafka did not become ready in time."
    exit 1
  fi
done

create_topic() {
  local topic=$1
  if "$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP" --list | grep -q "^${topic}$"; then
    echo "Topic already exists: $topic"
  else
    "$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP" --create \
      --topic "$topic" \
      --partitions 3 \
      --replication-factor 1
    echo "Created topic: $topic"
  fi
}

create_topic "openbank.accounts.account.created"
create_topic "openbank.accounts.account.status-changed"
create_topic "openbank.accounts.balance.updated"
create_topic "openbank.ledger.journal.posted"
create_topic "openbank.ledger.journal.reversed"
create_topic "openbank.transactions.transaction.initiated"
create_topic "openbank.transactions.transaction.completed"
create_topic "openbank.transactions.transaction.failed"
create_topic "openbank.payments.payment-order.created"
create_topic "openbank.payments.payment-order.submitted"
create_topic "openbank.payments.payment-order.settled"
create_topic "openbank.payments.payment-order.rejected"
create_topic "openbank.cards.card.issued"
create_topic "openbank.cards.authorization.requested"
create_topic "openbank.cards.authorization.approved"
create_topic "openbank.cards.authorization.declined"
create_topic "openbank.compliance.kyc.completed"
create_topic "openbank.compliance.aml.alert.raised"
create_topic "openbank.compliance.sanctions.hit"
create_topic "openbank.audit.event.recorded"
create_topic "openbank.party.created"
create_topic "openbank.party.verified"
create_topic "openbank.party.status.changed"
create_topic "openbank.party.relationship.added"
create_topic "openbank.party.relationship.terminated"
create_topic "openbank.party.kyc.level.changed"
create_topic "party.events"

echo "All topics created successfully."
"$KAFKA_TOPICS_BIN" --bootstrap-server "$BOOTSTRAP" --list

create_topic "openbank.consent.events"
create_topic "openbank.consent.granted"
create_topic "openbank.consent.revoked"
create_topic "openbank.consent.expired"
create_topic "openbank.sca.challenge.initiated"
create_topic "openbank.sca.challenge.completed"
create_topic "openbank.sca.challenge.failed"
create_topic "openbank.balance.events"
create_topic "openbank.balance.holds"
create_topic "openbank.party.events"
create_topic "openbank.kyc.events"
create_topic "openbank.notification.requests"
create_topic "openbank.audit.events"
