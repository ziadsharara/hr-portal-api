# Reuse the account's default VPC to start — no custom VPC yet. Keeps
# this first deployment simple and low-cost for an internal tool; revisit
# with a custom VPC (real private subnets, NAT gateway) if/when this
# needs stronger network isolation than SG rules + publicly_accessible =
# false provide.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_caller_identity" "current" {}
