# product-ir-verify — billing's and interest's product-IR shadow (ADR-0352). Verify only: a
# consumer that could sign could forge the revision it is checking.
path "transit/verify/product-ir-publish" {
  capabilities = ["update"]
}
