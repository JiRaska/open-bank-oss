\connect postgres

CREATE DATABASE openbank_accounts;
CREATE DATABASE openbank_ledger;
CREATE DATABASE openbank_transactions;
CREATE DATABASE openbank_balances;
CREATE DATABASE openbank_gatus;
CREATE DATABASE openbank_products;
CREATE DATABASE openbank_keycloak;
CREATE DATABASE openbank_pid;
CREATE DATABASE openbank_audit;
CREATE DATABASE openbank_consents;
CREATE DATABASE openbank_sca;
CREATE DATABASE openbank_tpp_registry;
CREATE DATABASE openbank_parties;
CREATE DATABASE openbank_notifications;
CREATE DATABASE openbank_kyc;
CREATE DATABASE openbank_sepa_payments;
CREATE DATABASE openbank_domestic_payments;
CREATE DATABASE openbank_aml;

\connect openbank_accounts
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_accounts TO openbank;

\connect openbank_ledger
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_ledger TO openbank;

\connect openbank_transactions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_transactions TO openbank;

\connect openbank_balances
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_balances TO openbank;

\connect openbank_products
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_products TO openbank;

\connect openbank_keycloak
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_keycloak TO openbank;

\connect openbank_pid
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_pid TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_audit
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_audit TO openbank;

\connect openbank_consents
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_consents TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_sca
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_sca TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_tpp_registry
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_tpp_registry TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_balances
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_balances TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_gatus
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
GRANT ALL PRIVILEGES ON DATABASE openbank_gatus TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_parties
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_parties TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_notifications
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
GRANT ALL PRIVILEGES ON DATABASE openbank_notifications TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_kyc
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
GRANT ALL PRIVILEGES ON DATABASE openbank_kyc TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_sepa_payments
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_sepa_payments TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_domestic_payments
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_domestic_payments TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_aml
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_aml TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

CREATE DATABASE openbank_sanctions;
CREATE DATABASE openbank_card_issuance;
CREATE DATABASE openbank_clearing;
CREATE DATABASE openbank_dispute;
CREATE DATABASE openbank_fx;
CREATE DATABASE openbank_interest;
CREATE DATABASE openbank_sepa_instant;
CREATE DATABASE openbank_standing_orders;
CREATE DATABASE openbank_swift;

\connect openbank_sanctions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_sanctions TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_card_issuance
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_card_issuance TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_clearing
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_clearing TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_dispute
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_dispute TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_fx
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_fx TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_interest
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_interest TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_sepa_instant
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_sepa_instant TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_standing_orders
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_standing_orders TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

\connect openbank_swift
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_swift TO openbank;
GRANT ALL ON SCHEMA public TO openbank;

CREATE DATABASE openbank_cards;

\connect openbank_cards
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT ALL PRIVILEGES ON DATABASE openbank_cards TO openbank;
GRANT ALL ON SCHEMA public TO openbank;
