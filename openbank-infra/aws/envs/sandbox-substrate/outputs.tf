# Consumed by the platform root (envs/sandbox-platform) via terraform_remote_state.

output "region" {
  value = var.region
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "bootstrap_asg_name" {
  description = "Underlying ASG of the bootstrap managed node group — consumed by the platform root's aws_autoscaling_schedule (sandbox-platform/bootstrap-schedule.tf) via terraform_remote_state."
  value       = module.eks.bootstrap_asg_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "cluster_certificate_authority_data" {
  value = module.eks.cluster_certificate_authority_data
}

output "cluster_version" {
  value = module.eks.cluster_version
}

output "oidc_provider_arn" {
  value = module.eks.oidc_provider_arn
}

output "node_security_group_id" {
  value = module.eks.node_security_group_id
}

output "vpc_id" {
  value = module.network.vpc_id
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

output "karpenter_node_role_name" {
  value = module.karpenter_iam.node_role_name
}

output "karpenter_controller_role_arn" {
  value = module.karpenter_iam.controller_role_arn
}

output "karpenter_interruption_queue_name" {
  value = module.karpenter_iam.interruption_queue_name
}

output "audit_log_archive_bucket" {
  value = module.audit_baseline.log_archive_bucket
}

output "audit_cloudtrail_arn" {
  value = module.audit_baseline.cloudtrail_arn
}

# Delegate the domain at the registrar to these four NS records (one-time).
output "dns_name_servers" {
  value = module.dns.name_servers
}

output "dns_zone_id" {
  value = module.dns.zone_id
}

# Publish this DS record at the registrar (WEDOS) to complete the DNSSEC chain of
# trust — ONLY after `tofu apply` and the zone reports signed.
# Retrieve with: tofu output -raw dnssec_ds_record
output "dnssec_ds_record" {
  value = module.dns.dnssec_ds_record
}

output "dnssec_ksk" {
  value = module.dns.dnssec_ksk
}

output "external_dns_role_arn" {
  value = module.dns.external_dns_role_arn
}

output "cert_manager_dns01_role_arn" {
  value = module.dns.cert_manager_role_arn
}
