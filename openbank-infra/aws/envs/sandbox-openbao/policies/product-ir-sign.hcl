# product-ir-sign — product-catalog's publish path (ADR-0352). Sign with the one key, nothing else:
# no verify (the publisher never needs to), no key read/rotate/export, no other Transit key.
path "transit/sign/product-ir-publish" {
  capabilities = ["update"]
}
