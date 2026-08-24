# --- ALB: the ONLY thing allowed in from outside the VPC -------------
# Locked to allowed_cidr (validated non-0.0.0.0/0 in variables.tf). This
# is the safety boundary for the whole deployment — the API has no
# authentication, so this SG is the only thing standing between it and
# the open internet.
resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb"
  description = "Inbound HTTP from allowed_cidr only (API has no auth yet — never open to 0.0.0.0/0)"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP from allowed CIDR (office/VPN)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}

# --- ECS services: reachable only from the ALB ------------------------
resource "aws_security_group" "ecs_backend" {
  name        = "${local.name_prefix}-ecs-backend"
  description = "Backend ECS tasks — inbound only from the ALB"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB"
    from_port       = var.backend_container_port
    to_port         = var.backend_container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}

resource "aws_security_group" "ecs_frontend" {
  name        = "${local.name_prefix}-ecs-frontend"
  description = "Frontend ECS tasks — inbound only from the ALB"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB"
    from_port       = var.frontend_container_port
    to_port         = var.frontend_container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}

# --- RDS: reachable only from the backend ECS tasks -------------------
resource "aws_security_group" "rds" {
  name        = "${local.name_prefix}-rds"
  description = "MySQL — inbound only from the backend ECS service, never public"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "MySQL from backend ECS tasks"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_backend.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}
