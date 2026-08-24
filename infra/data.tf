# Reuse the account's default VPC — no custom VPC. For a single-instance
# development/demo environment there is nothing to isolate from anything
# else: the database is not a separate network peer, it is a container on
# the same host reachable only over a private Docker bridge network.
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

data "aws_region" "current" {}

# Amazon Linux 2023, resolved from SSM Parameter Store rather than
# pinned by AMI ID: AMI IDs differ per region and go stale on every
# release, and this parameter always points at the current AL2023 x86_64
# image. Note this means a `terraform apply` after a new AL2023 release
# will want to replace the instance — the MySQL data lives on a separate
# EBS volume precisely so that is survivable.
data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}
