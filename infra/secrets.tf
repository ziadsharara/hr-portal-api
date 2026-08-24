# Terraform generates both DB passwords and stores them ONLY in Secrets
# Manager — never in a .tf file, a tfvars file, or the instance's user
# data. user_data fetches them at boot with the instance profile's
# scoped read permission (see iam_ec2.tf) and writes them to a
# root-owned 0600 env file that only the Docker daemon reads.
#
# Note what is NOT here any more: an RDS endpoint. The JDBC URL is
# assembled on the instance and points at the "mysql" container over the
# private Docker network, so it never contains a routable host.

resource "random_password" "db_app" {
  length  = 32
  special = false # avoid characters needing extra escaping in a JDBC URL
}

resource "random_password" "db_root" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "db" {
  name        = "${local.name_prefix}/db-credentials"
  description = "MySQL credentials for ${local.name_prefix}, consumed at boot by the EC2 instance's user data. The database is a container on that instance, not RDS."

  # A dev/demo stack gets torn down and re-applied often, and Secrets
  # Manager's default 30-day soft delete makes the *next* apply fail with
  # a name collision. Zero means the name is free again immediately.
  recovery_window_in_days = 0

  tags = local.tags
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id

  secret_string = jsonencode({
    db_name       = var.db_name
    username      = var.db_username
    password      = random_password.db_app.result
    root_password = random_password.db_root.result
  })
}
