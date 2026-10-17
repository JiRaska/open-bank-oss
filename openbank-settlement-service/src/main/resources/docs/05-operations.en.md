# openbank-settlement-service — operations

## Shared configuration defaults

This service's `application.yaml` does not declare the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

The reactive datasource pool bounds (`quarkus.datasource.reactive.idle-timeout` 5M, `max-lifetime` 30M, `max-size` 20) are likewise no longer declared here; they come from the same fleet default and equal the values this service used to declare. Setting any of them in this service's `application.yaml` still overrides the default.
