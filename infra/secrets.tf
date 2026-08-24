# Terraform generates the DB password and stores it ONLY in Secrets
# Manager — it's never in a .tf file, tfvars, or the task definition's
# environment block. The ECS task definition references this secret by
# ARN (see ecs.tf's `secrets` block, not `environment`), and the task
# execution role is granted read-only access to exactly this one secret
# (see iam.tf).
resource "random_password" "db" {
  length  = 32
  special = false # avoid characters that need extra escaping in a JDBC URL
}

resource "aws_secretsmanager_secret" "db" {
  name        = "${local.name_prefix}/db-credentials"
  description = "RDS MySQL credentials for ${local.name_prefix}, consumed by the backend ECS task via the `secrets` block (never plain env vars)."
  tags        = local.tags
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = var.db_username
    password = random_password.db.result
    url      = "jdbc:mysql://${aws_db_instance.main.address}:3306/${var.db_name}?useSSL=false&serverTimezone=UTC"
  })
}
