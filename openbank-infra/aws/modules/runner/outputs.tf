output "instance_id" {
  description = "EC2 instance ID — open a shell with: aws ssm start-session --target <id>"
  value       = aws_instance.runner.id
}

output "vpc_id" {
  value = aws_vpc.this.id
}

output "runner_labels" {
  description = "Use these in a workflow's runs-on to target this runner."
  value       = var.runner_labels
}
