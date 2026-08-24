# --- GitHub OIDC federation (Step 5's CD auth) -------------------------
# Lets GitHub Actions assume an AWS role using a short-lived token minted
# per workflow run — no long-lived AWS access keys stored as GitHub
# secrets. This is the AWS-recommended pattern specifically because it
# removes the "leaked static credential" risk entirely.
#
# hr-portal-api and hr-portal-frontend are separate repos, each with
# their own cd.yml, so each gets ITS OWN deploy role below — scoped to
# only that service's ECR repo + ECS service. The backend's pipeline
# cannot touch the frontend's resources and vice versa.

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # This value is NOT security-critical: AWS has validated OIDC tokens
  # from this issuer against its own trusted CA bundle (not this field)
  # since 2023 — the thumbprint is a required-but-unused legacy field for
  # well-known providers like GitHub's. It only needs to be a
  # syntactically valid 40-hex-char SHA1 thumbprint. To recompute it from
  # the live cert chain instead:
  #   echo | openssl s_client -servername token.actions.githubusercontent.com \
  #     -connect token.actions.githubusercontent.com:443 -showcerts 2>/dev/null \
  #     | openssl x509 -noout -fingerprint -sha1 | cut -d= -f2 | tr -d ':'
  thumbprint_list = ["0fcba946366c27a9e22aeb82e6e8b49b172ce8d5"]

  tags = local.tags
}

locals {
  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"
}

# --- Backend deploy role ------------------------------------------------
resource "aws_iam_role" "github_deploy_backend" {
  name = "${local.name_prefix}-github-deploy-backend"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo_backend}:ref:refs/heads/${var.github_deploy_branch}"
        }
      }
    }]
  })

  tags = local.tags
}

resource "aws_iam_role_policy" "github_deploy_backend" {
  name = "${local.name_prefix}-github-deploy-backend"
  role = aws_iam_role.github_deploy_backend.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPushPull"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
        ]
        Resource = [aws_ecr_repository.backend.arn]
      },
      {
        Sid    = "EcsDescribeAndRegister"
        Effect = "Allow"
        # ECS does not support resource-level scoping on these two
        # actions (they act on a task definition family before a
        # specific revision ARN exists) — this is the AWS-documented
        # minimum, not an over-broad grant. RegisterTaskDefinition can't
        # be scoped to "only the hr-portal-api family" at the IAM layer.
        Action = [
          "ecs:DescribeTaskDefinition",
          "ecs:RegisterTaskDefinition",
        ]
        Resource = "*"
      },
      {
        Sid    = "EcsUpdateOwnServiceOnly"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService",
          "ecs:DescribeServices",
        ]
        Resource = [aws_ecs_service.backend.id]
      },
      {
        Sid      = "PassExecutionRoleToEcs"
        Effect   = "Allow"
        Action    = "iam:PassRole"
        Resource  = aws_iam_role.ecs_task_execution.arn
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
    ]
  })
}

# --- Frontend deploy role ------------------------------------------------
resource "aws_iam_role" "github_deploy_frontend" {
  name = "${local.name_prefix}-github-deploy-frontend"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo_frontend}:ref:refs/heads/${var.github_deploy_branch}"
        }
      }
    }]
  })

  tags = local.tags
}

resource "aws_iam_role_policy" "github_deploy_frontend" {
  name = "${local.name_prefix}-github-deploy-frontend"
  role = aws_iam_role.github_deploy_frontend.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPushPull"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
        ]
        Resource = [aws_ecr_repository.frontend.arn]
      },
      {
        Sid    = "EcsDescribeAndRegister"
        Effect = "Allow"
        Action = [
          "ecs:DescribeTaskDefinition",
          "ecs:RegisterTaskDefinition",
        ]
        Resource = "*"
      },
      {
        Sid    = "EcsUpdateOwnServiceOnly"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService",
          "ecs:DescribeServices",
        ]
        Resource = [aws_ecs_service.frontend.id]
      },
      {
        Sid       = "PassExecutionRoleToEcs"
        Effect    = "Allow"
        Action    = "iam:PassRole"
        Resource  = aws_iam_role.ecs_task_execution.arn
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
    ]
  })
}
