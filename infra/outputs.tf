output "alb_dns_name" {
  description = "Public (but allowed_cidr-restricted) URL for the app. No custom domain/HTTPS yet — see acm_certificate_arn TODO."
  value       = "http://${aws_lb.main.dns_name}"
}

output "ecr_backend_repository_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "ecr_frontend_repository_url" {
  value = aws_ecr_repository.frontend.repository_url
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "github_deploy_role_arn_backend" {
  description = "Set this as the AWS_DEPLOY_ROLE_ARN repository variable in the hr-portal-api repo's GitHub settings."
  value       = aws_iam_role.github_deploy_backend.arn
}

output "github_deploy_role_arn_frontend" {
  description = "Set this as the AWS_DEPLOY_ROLE_ARN repository variable in the hr-portal-frontend repo's GitHub settings."
  value       = aws_iam_role.github_deploy_frontend.arn
}

output "db_endpoint" {
  value     = aws_db_instance.main.address
  sensitive = false
}

output "db_secret_arn" {
  description = "Secrets Manager ARN holding the generated DB credentials."
  value       = aws_secretsmanager_secret.db.arn
}
