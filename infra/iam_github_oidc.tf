# --- GitHub OIDC federation --------------------------------------------
# Lets GitHub Actions assume an AWS role using a short-lived token minted
# per workflow run — no long-lived AWS access keys stored as GitHub
# secrets. This is the AWS-recommended pattern specifically because it
# removes the "leaked static credential" risk entirely.
#
# The two repos now deploy to completely different things, so their roles
# have nothing in common any more:
#
#   backend  -> push to ECR, then trigger a redeploy on the one instance
#   frontend -> write objects into the one S3 site bucket
#
# Neither can reach the other's resources, and neither can touch the
# database secret.

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # This value is NOT security-critical: AWS has validated OIDC tokens
  # from this issuer against its own trusted CA bundle (not this field)
  # since 2023 — the thumbprint is a required-but-unused legacy field for
  # well-known providers like GitHub's.
  thumbprint_list = ["0fcba946366c27a9e22aeb82e6e8b49b172ce8d5"]

  tags = local.tags
}

locals {
  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"

  # Both accepted `sub` forms per repo — see the comment on
  # github_owner_id in variables.tf for why there are two. StringLike
  # takes a list with OR semantics, so this permits exactly these
  # strings and nothing else; there is no wildcard in either.
  backend_owner  = split("/", var.github_repo_backend)[0]
  backend_name   = split("/", var.github_repo_backend)[1]
  frontend_owner = split("/", var.github_repo_frontend)[0]
  frontend_name  = split("/", var.github_repo_frontend)[1]

  github_ids_known = var.github_owner_id != ""

  backend_subs = compact([
    "repo:${var.github_repo_backend}:ref:refs/heads/${var.github_deploy_branch}",
    local.github_ids_known && var.github_repo_backend_id != "" ? "repo:${local.backend_owner}@${var.github_owner_id}/${local.backend_name}@${var.github_repo_backend_id}:ref:refs/heads/${var.github_deploy_branch}" : "",
  ])

  frontend_subs = compact([
    "repo:${var.github_repo_frontend}:ref:refs/heads/${var.github_deploy_branch}",
    local.github_ids_known && var.github_repo_frontend_id != "" ? "repo:${local.frontend_owner}@${var.github_owner_id}/${local.frontend_name}@${var.github_repo_frontend_id}:ref:refs/heads/${var.github_deploy_branch}" : "",
  ])
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
        # Pinned to one repo AND one branch. Not a wildcard: any repo
        # whose sub matched could otherwise mint these credentials.
        StringLike = {
          "token.actions.githubusercontent.com:sub" = local.backend_subs
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
        Sid    = "EcrPushToBackendRepoOnly"
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
        # CD resolves the instance from its Name tag rather than trusting
        # a hardcoded ID in a repository variable, so replacing the
        # instance does not silently break deploys. DescribeInstances has
        # no resource-level scoping in EC2.
        Sid      = "DiscoverBackendInstance"
        Effect   = "Allow"
        Action   = ["ec2:DescribeInstances"]
        Resource = "*"
      },
      {
        # The deploy itself. Instead of an SSH key held as a GitHub
        # secret, CD asks SSM to run the deploy script already on the
        # box — so there is no inbound path from CI to the instance at
        # all, and nothing to leak.
        Sid    = "SsmRunDeployOnThisInstanceOnly"
        Effect = "Allow"
        Action = ["ssm:SendCommand"]
        Resource = [
          aws_instance.backend.arn,
          "arn:aws:ssm:${data.aws_region.current.name}::document/AWS-RunShellScript",
        ]
      },
      {
        # Reading back the result of the command CD just issued, so the
        # workflow can fail when the deploy fails. These act on a command
        # invocation ID that does not exist until SendCommand returns, so
        # they cannot be scoped to a resource ARN.
        Sid    = "SsmReadOwnCommandResult"
        Effect = "Allow"
        Action = [
          "ssm:GetCommandInvocation",
          "ssm:ListCommandInvocations",
        ]
        Resource = "*"
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
          "token.actions.githubusercontent.com:sub" = local.frontend_subs
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
        # ListBucket is on the bucket ARN; the object actions are on the
        # keys within it. `aws s3 sync --delete` needs ListBucket to see
        # what is already there and DeleteObject to remove what is gone.
        Sid      = "ListSiteBucket"
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = [aws_s3_bucket.frontend.arn]
      },
      {
        Sid    = "WriteSiteObjects"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
        ]
        Resource = ["${aws_s3_bucket.frontend.arn}/*"]
      },
      {
        # Lets CD bust the CloudFront cache for index.html after each
        # sync, so a deploy shows up immediately instead of waiting out
        # whatever TTL the cache policy would otherwise apply. Scoped to
        # this one distribution — nothing else in the account.
        Sid    = "InvalidateFrontendDistribution"
        Effect = "Allow"
        Action = [
          "cloudfront:CreateInvalidation",
          "cloudfront:GetInvalidation",
        ]
        Resource = [aws_cloudfront_distribution.frontend.arn]
      },
    ]
  })
}
