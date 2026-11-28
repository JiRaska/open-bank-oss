# Isolated Keycloak for the MCP sandbox

The runtime overlay composes this component with a zero-replica Keycloak
Deployment, a private HTTPS relay, a native read-issuer listener and exact
peer policies. Apply the namespace foundation and the isolated database
workloads first. The source has placeholder addresses and images. Render the
reviewed Keycloak and relay digests and exact site addresses through Argo before
scaling the Deployment.

Use the CNPG read-write Service and verify its certificate hostname. Keycloak
mounts the generated application credentials and only the public CA certificate.
Provision the isolated realm import and a PKCS12 truststore containing only
the approved MCP read client issuer in OpenBao. Check ExternalSecret readiness,
then verify issuer discovery and both listeners before admitting clients. Keep
synthetic credentials, private addresses and registry details in the site
configuration. The public MCP route is managed separately.
