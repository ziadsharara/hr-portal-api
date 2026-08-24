resource "aws_ecs_cluster" "main" {
  name = "${local.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "disabled" # extra CloudWatch cost not justified for this scale yet
  }

  tags = local.tags
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.name_prefix}/backend"
  retention_in_days = 30
  tags              = local.tags
}

resource "aws_cloudwatch_log_group" "frontend" {
  name              = "/ecs/${local.name_prefix}/frontend"
  retention_in_days = 30
  tags              = local.tags
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "hr-portal-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn

  container_definitions = jsonencode([{
    name      = "hr-portal-api"
    image     = "${aws_ecr_repository.backend.repository_url}:${var.container_image_tag}"
    essential = true

    portMappings = [{
      containerPort = var.backend_container_port
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
    ]

    # Credentials come from Secrets Manager via `secrets`, never baked
    # into the task definition as plain `environment` values.
    secrets = [
      { name = "DB_URL", valueFrom = "${aws_secretsmanager_secret.db.arn}:url::" },
      { name = "DB_USERNAME", valueFrom = "${aws_secretsmanager_secret.db.arn}:username::" },
      { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db.arn}:password::" },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.backend.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])

  # Terraform manages everything ELSE about this task definition; the
  # `image` field is deliberately left free for CD (cd.yml) to update on
  # every deploy via register-task-definition + update-service. Without
  # this, every `terraform apply` would fight the CD pipeline over
  # whichever image tag was last deployed.
  lifecycle {
    ignore_changes = [container_definitions]
  }

  tags = local.tags
}

resource "aws_ecs_task_definition" "frontend" {
  family                   = "hr-portal-frontend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.frontend_cpu
  memory                   = var.frontend_memory
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn

  container_definitions = jsonencode([{
    name      = "hr-portal-frontend"
    image     = "${aws_ecr_repository.frontend.repository_url}:${var.container_image_tag}"
    essential = true

    portMappings = [{
      containerPort = var.frontend_container_port
      protocol      = "tcp"
    }]

    # BACKEND_HOST/PORT (nginx's local-docker-compose proxy target) are
    # left at the image's own defaults — irrelevant in AWS since the ALB
    # routes /api/* straight to the backend target group and never
    # reaches this container's nginx proxy at all.

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.frontend.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])

  lifecycle {
    ignore_changes = [container_definitions]
  }

  tags = local.tags
}

resource "aws_ecs_service" "backend" {
  name            = "hr-portal-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.ecs_backend.id]
    assign_public_ip = true # default VPC subnets are public; tasks still unreachable directly — only the ALB SG can reach them
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "hr-portal-api"
    container_port   = var.backend_container_port
  }

  # CD updates the task definition revision directly; don't let a later
  # `terraform apply` roll a running deploy back to container_image_tag's
  # bootstrap value.
  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.http]

  tags = local.tags
}

resource "aws_ecs_service" "frontend" {
  name            = "hr-portal-frontend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.frontend.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.ecs_frontend.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name   = "hr-portal-frontend"
    container_port   = var.frontend_container_port
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.http]

  tags = local.tags
}
