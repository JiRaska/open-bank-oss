output "node_role_name" {
  description = "Role name referenced by EC2NodeClass.spec.role."
  value       = aws_iam_role.node.name
}

output "node_role_arn" {
  value = aws_iam_role.node.arn
}

output "controller_role_arn" {
  value = aws_iam_role.controller.arn
}

output "interruption_queue_name" {
  value = aws_sqs_queue.interruption.name
}
