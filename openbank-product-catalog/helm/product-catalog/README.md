# Product Catalog Helm chart

The chart deploys only the stateless catalog. PostgreSQL and an OIDC issuer stay operator-owned.
It creates no credentials and defaults to no industry pack.

```yaml
image:
  tag: v0.11.4
oidc:
  issuer: https://identity.example/realms/catalog
database:
  host: catalog-postgresql.example
  existingSecret: catalog-database
bankV1CompatibilityEnabled: false
catalogPacks:
  - insurance
```

The referenced Secret must contain the configured `usernameKey` and `passwordKey`. Install with
`helm upgrade --install catalog ./helm/product-catalog -f values.production.yaml`.
