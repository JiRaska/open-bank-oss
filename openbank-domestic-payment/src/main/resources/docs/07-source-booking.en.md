# Source-owned settlement booking

After CERTIS accepts a domestic payment, the settlement adapter sends its exact payment UUID as `originatingPaymentId` to transaction-service with the debit booking. Repeating the same idempotency key may return the existing transaction only when its recorded source does not conflict. A 409 from transaction-service is a conflicting booking, **not** successful settlement; the adapter must leave the payment unsettled and surface the error. Do not repair a conflict by inventing a new idempotency key or losing the source reference.
