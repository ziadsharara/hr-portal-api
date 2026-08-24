# --- EC2: the only inbound surface in this architecture ---------------
# There is no ALB and no Cloudflare in front of this any more. The rules
# below are the entire network boundary for the deployment.
#
# Deliberately absent from this security group:
#
#   * Any rule for port 3306. MySQL runs as a container that publishes NO
#     host port (see the compose file rendered by user_data.sh.tftpl) —
#     it is reachable only from the backend container over the private
#     Docker bridge network. Even a rule here would not expose it, and
#     there must never be one. To inspect the database, SSH or SSM onto
#     the box and use `docker compose exec mysql mysql ...` locally.
#
#   * Any 0.0.0.0/0 ingress. Both CIDR variables are validated against it
#     in variables.tf, because the API has no authentication.
resource "aws_security_group" "ec2" {
  name        = "${local.name_prefix}-ec2"
  description = "Backend API from api_allowed_cidrs, SSH from ssh_allowed_cidr. MySQL is container-internal and never exposed."
  vpc_id      = data.aws_vpc.default.id

  tags = merge(local.tags, { Name = "${local.name_prefix}-ec2" })
}

resource "aws_vpc_security_group_ingress_rule" "api" {
  for_each = toset(var.api_allowed_cidrs)

  security_group_id = aws_security_group.ec2.id
  description       = "Backend API from ${each.value}"
  cidr_ipv4         = each.value
  from_port         = var.backend_port
  to_port           = var.backend_port
  ip_protocol       = "tcp"
}

# Only created when an SSH key pair is configured. With ssh_key_name left
# empty there is no key to log in with, so port 22 stays shut entirely
# and SSM Session Manager is the way onto the box.
resource "aws_vpc_security_group_ingress_rule" "ssh" {
  count = var.ssh_key_name == "" ? 0 : 1

  security_group_id = aws_security_group.ec2.id
  description       = "SSH from ssh_allowed_cidr only"
  cidr_ipv4         = var.ssh_allowed_cidr
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
}

# Outbound is unrestricted: the instance needs to reach ECR for images,
# Secrets Manager for the DB password, SSM for the deploy channel, and
# the distro package mirrors on first boot.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.ec2.id
  description       = "All outbound (ECR, Secrets Manager, SSM, package mirrors)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
