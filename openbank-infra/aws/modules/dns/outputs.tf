output "zone_id" {
  description = "Route53 hosted zone ID for the domain."
  value       = aws_route53_zone.this.zone_id
}

output "name_servers" {
  description = "Delegate the domain to these NS records at the registrar."
  value       = aws_route53_zone.this.name_servers
}

output "dnssec_ds_record" {
  description = "DS record to publish at the registrar (WEDOS) to complete the chain of trust. Publish ONLY after the zone reports signed."
  value       = aws_route53_key_signing_key.this.ds_record
}

output "dnssec_ksk" {
  description = "Key-signing key components (key tag, algorithm, digest) if the registrar wants the DS fields individually instead of the full record."
  value = {
    key_tag                = aws_route53_key_signing_key.this.key_tag
    signing_algorithm_type = aws_route53_key_signing_key.this.signing_algorithm_type
    digest_algorithm_type  = aws_route53_key_signing_key.this.digest_algorithm_type
    digest_value           = aws_route53_key_signing_key.this.digest_value
    flag                   = aws_route53_key_signing_key.this.flag
  }
}

output "external_dns_role_arn" {
  value = aws_iam_role.external_dns.arn
}

output "cert_manager_role_arn" {
  value = aws_iam_role.cert_manager.arn
}
