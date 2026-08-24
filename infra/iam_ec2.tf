# --- EC2 instance role -------------------------------------------------
# What the box itself is allowed to do: pull its own image from ECR, read
# the one DB secret, ship logs, and be driven by SSM (which is both the
# keyless shell and the channel CD uses to trigger a redeploy).
resource "aws_iam_role" "ec2" {
  name = "${local.name_prefix}-ec2"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = local.tags
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${local.name_prefix}-ec2"
  role = aws_iam_role.ec2.name
  tags = local.tags
}

# Enables SSM Session Manager (shell without an SSH key or an open port
# 22) and SSM Run Command (how cd.yml triggers a redeploy).
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "ec2_ecr_pull" {
  name = "${local.name_prefix}-ec2-ecr-pull"
  role = aws_iam_role.ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # GetAuthorizationToken has no resource-level scoping in ECR —
        # it mints a registry-wide token before any repository is named.
        # This is the AWS-documented minimum, not an over-broad grant.
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        # Pull only. The instance must never be able to push: a
        # compromised host should not be able to rewrite the image that
        # every future boot pulls.
        Sid    = "EcrPullBackendOnly"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
        ]
        Resource = [aws_ecr_repository.backend.arn]
      },
    ]
  })
}

resource "aws_iam_role_policy" "ec2_secrets_read" {
  name = "${local.name_prefix}-ec2-secrets-read"
  role = aws_iam_role.ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [aws_secretsmanager_secret.db.arn] # exactly one secret, not secretsmanager:* on *
    }]
  })
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/hr-portal/backend"
  retention_in_days = 30
  tags              = local.tags
}

resource "aws_iam_role_policy" "ec2_logs" {
  name = "${local.name_prefix}-ec2-logs"
  role = aws_iam_role.ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams",
      ]
      Resource = ["${aws_cloudwatch_log_group.backend.arn}:*"]
    }]
  })
}
