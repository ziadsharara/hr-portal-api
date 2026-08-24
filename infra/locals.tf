locals {
  tags = {
    Project     = var.project_name
    ManagedBy   = "terraform"
    Environment = "prod"
  }

  name_prefix = var.project_name
}
