output "frontend_url" {
  description = "The Vue app, served over HTTPS via CloudFront (default *.cloudfront.net certificate — no custom domain yet). Reachable only from api_allowed_cidrs, enforced by the CloudFront Function in cloudfront.tf."
  value       = "https://${aws_cloudfront_distribution.frontend.domain_name}"
}

output "frontend_bucket_name" {
  description = "Set this as the S3_BUCKET repository variable in the hr-portal-frontend repo's GitHub settings."
  value       = aws_s3_bucket.frontend.bucket
}

output "cloudfront_distribution_id" {
  description = "Set this as the CLOUDFRONT_DISTRIBUTION_ID repository variable in the hr-portal-frontend repo's GitHub settings — CD uses it to invalidate the cache after each deploy."
  value       = aws_cloudfront_distribution.frontend.id
}

output "api_base_url" {
  description = "Set this as the VITE_API_BASE_URL repository variable in hr-portal-frontend. It is baked into the Vue bundle at build time, so changing it requires a frontend rebuild, not just a redeploy."
  value       = "http://${aws_eip.backend.public_ip}:${var.backend_port}/api"
}

output "backend_public_ip" {
  description = "Elastic IP of the backend instance. Stable across stop/start, which is what keeps the built frontend bundle valid."
  value       = aws_eip.backend.public_ip
}

output "backend_instance_id" {
  description = "Set this as the EC2_INSTANCE_ID repository variable in the hr-portal-api repo's GitHub settings. Also the target for `aws ssm start-session --target <id>`."
  value       = aws_instance.backend.id
}

output "ecr_backend_repository_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "ecr_db_repository_url" {
  description = "Push the demo-data MySQL image (db/image/Dockerfile) here — see db/image/README.md."
  value       = aws_ecr_repository.db.repository_url
}

output "github_deploy_role_arn_backend" {
  description = "Set this as the AWS_DEPLOY_ROLE_ARN repository variable in the hr-portal-api repo's GitHub settings."
  value       = aws_iam_role.github_deploy_backend.arn
}

output "github_deploy_role_arn_frontend" {
  description = "Set this as the AWS_DEPLOY_ROLE_ARN repository variable in the hr-portal-frontend repo's GitHub settings."
  value       = aws_iam_role.github_deploy_frontend.arn
}

output "db_secret_arn" {
  description = "Secrets Manager ARN holding the generated MySQL credentials. The instance reads this at boot; nothing else should."
  value       = aws_secretsmanager_secret.db.arn
}

output "db_data_volume_id" {
  description = "EBS volume holding the MySQL data directory. This is the only stateful resource in the stack — snapshot it before any risky change."
  value       = aws_ebs_volume.db_data.id
}
