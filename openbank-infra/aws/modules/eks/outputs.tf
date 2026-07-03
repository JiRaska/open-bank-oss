output "cluster_name" {
  value = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.this.endpoint
}

output "cluster_certificate_authority_data" {
  value = aws_eks_cluster.this.certificate_authority[0].data
}

output "cluster_version" {
  value = aws_eks_cluster.this.version
}

output "oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.this.arn
}

output "oidc_provider_url" {
  value = aws_iam_openid_connect_provider.this.url
}

output "node_role_arn" {
  value = aws_iam_role.node.arn
}

output "node_security_group_id" {
  description = "The cluster security group EKS creates; shared by managed nodes and Karpenter nodes."
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "kms_key_arn" {
  value = aws_kms_key.secrets.arn
}

output "bootstrap_asg_name" {
  description = "Underlying ASG of the bootstrap managed node group — target for aws_autoscaling_schedule (sandbox-substrate bootstrap-schedule.tf)."
  value       = aws_eks_node_group.bootstrap.resources[0].autoscaling_groups[0].name
}
