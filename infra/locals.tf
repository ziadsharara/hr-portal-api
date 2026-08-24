locals {
  tags = {
    Project     = var.project_name
    ManagedBy   = "terraform"
    Environment = "dev"
  }

  name_prefix = var.project_name

  # Where the dedicated EBS volume gets mounted on the instance and, in
  # turn, what the MySQL container binds its data directory to. Shared
  # between ec2.tf's user_data render and the docs, so the two cannot
  # drift.
  db_mount_point = "/var/lib/hr-portal/mysql"
}
