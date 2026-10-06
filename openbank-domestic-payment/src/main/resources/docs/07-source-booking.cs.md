# Zdrojové propojení zaúčtování

Po přijetí tuzemské platby systémem CERTIS předá settlement adaptér její přesné UUID jako `originatingPaymentId` službě transaction-service spolu s debetním zápisem. Opakování stejného idempotentního klíče smí vrátit existující transakci jen bez konfliktu zaznamenaného zdroje. Odpověď 409 ze služby transaction-service je konfliktní zaúčtování, **nikoli** úspěšný settlement; adaptér nesmí platbu označit za vypořádanou a musí chybu předat dál. Konflikt neopravuj vymyšlením nového idempotentního klíče ani ztrátou zdrojové reference.
