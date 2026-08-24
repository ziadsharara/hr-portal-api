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

# The one subnet everything lands in, resolved independently of any
# resource. This exists to break a dependency that quietly endangered the
# database: when the EBS volume took its availability_zone from
# aws_instance.backend, any change forcing an instance replacement made
# the AZ "known after apply", which in turn forced the VOLUME to be
# replaced — destroying the HR data as a side effect of, say, editing the
# bootstrap script. Reading the AZ from the subnet instead means the
# volume no longer depends on the instance at all.
#
# sort() because aws_subnets returns IDs in no guaranteed order, and an
# unstable [0] would silently migrate the instance to a different subnet
# (and therefore a different AZ) on some later apply.
data "aws_subnet" "selected" {
  id = var.subnet_id != "" ? var.subnet_id : sort(data.aws_subnets.default.ids)[0]
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
