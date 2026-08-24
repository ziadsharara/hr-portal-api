# --- Frontend: S3 static website hosting ------------------------------
# Replaces the frontend's ECS service, ECR repo, and nginx container. CD
# in hr-portal-frontend runs `npm run build` and syncs dist/ here.
#
# WARNING - HTTP ONLY. An S3 website endpoint cannot serve HTTPS. That is
# the direct cost of dropping Cloudflare from the architecture: there is
# no TLS anywhere in this deployment, so all traffic (including anything
# the HR app displays) crosses the network in plaintext. Acceptable only
# because api_allowed_cidrs keeps this to known networks in a
# development/demo environment. Putting CloudFront or Cloudflare back in
# front is what fixes it — see DEPLOYMENT.md.

resource "aws_s3_bucket" "frontend" {
  # Bucket names are globally unique across all AWS accounts, so the
  # account ID is appended rather than hoping "hr-portal-frontend" is
  # free. Not a secret — account IDs are not credentials.
  bucket = "${local.name_prefix}-frontend-${data.aws_caller_identity.current.account_id}"

  tags = local.tags
}

resource "aws_s3_bucket_website_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  index_document {
    suffix = "index.html"
  }

  # Vue Router runs in history mode, so a hard refresh of a client-side
  # route like /employees/42 arrives at S3 as a key that does not exist.
  # Serving index.html for those lets the router take over instead of
  # showing S3's XML error document.
  #
  # The tradeoff: S3 returns HTTP 404 with this body, not 200. The app
  # renders correctly, but genuinely missing assets are indistinguishable
  # from routes at the status-code level. CloudFront custom error
  # responses are what fix that properly, if this ever grows a CDN.
  error_document {
    key = "index.html"
  }
}

# S3 website endpoints do not support origin access identities or bucket
# policies scoped to a CDN — the objects must be readable by the caller
# directly. So the public access block is relaxed just enough to attach a
# policy, and the policy itself is what does the restricting via an
# IpAddress condition.
resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls  = true
  ignore_public_acls = true
  # Both must be false for the IP-conditioned policy below to attach at
  # all: AWS classifies any policy with a "*" principal as public,
  # regardless of the conditions narrowing it.
  block_public_policy     = false
  restrict_public_buckets = false
}

data "aws_iam_policy_document" "frontend_read" {
  statement {
    sid     = "PublicReadFromAllowedCidrsOnly"
    effect  = "Allow"
    actions = ["s3:GetObject"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    # This condition is the entire access control on the frontend. The
    # Vue bundle itself is not sensitive, but leaving the bucket open to
    # the world would advertise the API's existence and endpoint to
    # anyone who found it.
    condition {
      test     = "IpAddress"
      variable = "aws:SourceIp"
      values   = var.api_allowed_cidrs
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_read.json

  # Attaching a "public" policy fails if the access block is still in
  # place, and Terraform does not infer this ordering on its own.
  depends_on = [aws_s3_bucket_public_access_block.frontend]
}

resource "aws_s3_bucket_versioning" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  # CD syncs with --delete, so a bad deploy otherwise destroys the only
  # copy of the previous bundle. Versioning makes a rollback possible
  # without a rebuild.
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Noncurrent versions are only kept for rollback, which is a
# days-not-months concern here; without this they accumulate forever.
resource "aws_s3_bucket_lifecycle_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  depends_on = [aws_s3_bucket_versioning.frontend]
}
