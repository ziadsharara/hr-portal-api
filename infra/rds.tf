resource "aws_db_subnet_group" "main" {
  name       = "${local.name_prefix}-db"
  subnet_ids = data.aws_subnets.default.ids
  tags       = local.tags
}

resource "aws_db_instance" "main" {
  identifier     = "${local.name_prefix}-db"
  engine         = "mysql"
  engine_version = var.db_engine_version

  instance_class    = var.db_instance_class
  allocated_storage = var.db_allocated_storage_gb
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  # Never publicly reachable, regardless of the subnet's own route table.
  # Default-VPC subnets are technically "public" subnets (route to an
  # IGW) since we're not standing up a custom VPC with real private
  # subnets yet — this flag plus the security group above is what
  # actually keeps this instance unreachable from the internet. Revisit
  # with a custom VPC + genuinely private subnets if that's ever not
  # enough isolation.
  publicly_accessible = false

  # Single instance, not Multi-AZ — see the comment on db_instance_class
  # in variables.tf for the reasoning.
  multi_az = false

  backup_retention_period = 7
  skip_final_snapshot     = false
  final_snapshot_identifier = "${local.name_prefix}-db-final"
  deletion_protection      = true

  tags = local.tags
}
