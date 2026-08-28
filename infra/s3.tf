# --- Frontend: private S3 bucket behind CloudFront ---------------------
# Replaces the frontend's ECS service, ECR repo, and nginx container. CD
# in hr-portal-frontend runs `npm run build` and syncs dist/ here.
#
# The bucket is private — CloudFront (cloudfront.tf) is the only reader,
# via Origin Access Control, and is what serves HTTPS to the browser. This
# used to be a public S3 website endpoint (HTTP only, IP-restricted by a
# bucket-policy aws:SourceIp condition) — see cloudfront.tf and
# DEPLOYMENT.md for why that changed and what replaced the IP allowlist.

resource "aws_s3_bucket" "frontend" {
  # Bucket names are globally unique across all AWS accounts, so the
  # account ID is appended rather than hoping "hr-portal-frontend" is
  # free. Not a secret — account IDs are not credentials.
  bucket = "${local.name_prefix}-frontend-${data.aws_caller_identity.current.account_id}"

  tags = local.tags
}

# Fully blocking public access is safe here (and correct) even though a
# bucket policy is attached below: that policy grants only the
# cloudfront.amazonaws.com service principal, scoped to this one
# distribution's ARN — AWS does not classify that as "public" the way a
# "*" principal is.
resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "frontend_read" {
  statement {
    sid     = "AllowCloudFrontReadOnly"
    effect  = "Allow"
    actions = ["s3:GetObject"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    # Scopes the grant to this one distribution specifically — not "any
    # CloudFront distribution in this account". This is a structural
    # requirement (only CloudFront may ever read the bucket) and stays in
    # place regardless of allow_public_api_access — the visitor-facing
    # decision of who may reach the *site* now lives entirely in the
    # CloudFront Function (cloudfront.tf), which is conditionally attached
    # based on that variable instead of duplicating the toggle here.
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_read.json

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
