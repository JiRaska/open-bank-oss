# Keycloak realm signing-key renewal

Use the realm's approved rotation mechanism and change window. Do not export private keys or
client secrets.

## Pre-check

Create a P3 exercise entry, identify the realm and relying services, confirm JWKS reachability,
and verify that at least one client can refresh a token in a non-production environment. Keep the
previous signing key available for the configured overlap period.

## Execute

Generate the successor signing key in Keycloak, publish it through JWKS, and wait for the cache
overlap before making it the active signing key. Coordinate clients that pin a key and require
two-person approval for any manual setting change.

## Verify and evidence

Obtain a synthetic token, validate its issuer/audience/signature from a separate client, and
confirm a token issued before rotation remains accepted during overlap. Record only realm name,
key IDs, timestamps, approvers, and result in the register.
