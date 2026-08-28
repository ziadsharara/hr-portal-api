# --- Frontend: CloudFront in front of the private S3 bucket -----------
# Fixes the two things a plain S3 website endpoint cannot do: serve HTTPS
# (browsers' HTTPS-first upgrade behavior can otherwise fail to load the
# site outright) and cache at the edge (one S3 region + HTTP/1.1 means
# full round-trip latency for every user, every load). See s3.tf and
# DEPLOYMENT.md for the before/after.
#
# The bucket is private now (Origin Access Control, not the old public
# IP-conditioned policy), so the CloudFront Function below is what
# re-implements api_allowed_cidrs at the edge — see
# cloudfront_function.js.tftpl for why.
#
# allow_public_api_access (variables.tf) controls whether that function
# is attached at all — mirrors the dynamic "condition" toggle s3.tf used
# to carry directly, back when the bucket's own policy did this job.

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${local.name_prefix}-frontend"
  description                       = "Restricts the frontend S3 bucket to reads from this CloudFront distribution only."
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "ip_allowlist" {
  name    = "${local.name_prefix}-frontend-ip-allowlist"
  runtime = "cloudfront-js-2.0"
  comment = "Replaces the old aws:SourceIp S3 bucket-policy condition now that the bucket is private."
  publish = true

  code = templatefile("${path.module}/cloudfront_function.js.tftpl", {
    cidrs = var.api_allowed_cidrs
  })
}

resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  comment             = "${local.name_prefix} frontend"
  default_root_object = "index.html"
  price_class         = var.cloudfront_price_class

  # Off unless access is deliberately public: the IP-allowlist function
  # only checks IPv4 (see cloudfront_function.js.tftpl), so enabling IPv6
  # while that function is attached would be a silent bypass of
  # api_allowed_cidrs for any viewer connecting over IPv6. Once
  # allow_public_api_access is on, the function isn't attached at all, so
  # there's no restriction left for an IPv6 viewer to bypass — and
  # leaving IPv6 off in that case would just be an availability bug for
  # anyone on IPv6-only networks, the same failure mode s3.tf's
  # SourceArn/aws:SourceIp comment already covers for the old bucket
  # policy.
  is_ipv6_enabled = var.allow_public_api_access

  origin {
    # The REST regional endpoint, not the website endpoint — Origin
    # Access Control only works against the S3 REST API. The website
    # endpoint (and the error_document trick it used for Vue Router's
    # history mode) is gone; custom_error_response below replaces it.
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_id                = "s3-frontend"
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "s3-frontend"
    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    # AWS managed "CachingOptimized" policy. It honors the origin's own
    # Cache-Control headers when present, which is exactly what cd.yml
    # already sets per-object (immutable long cache for hashed assets,
    # no-cache for index.html) — no custom cache policy needed.
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"

    dynamic "function_association" {
      for_each = var.allow_public_api_access ? [] : [1]

      content {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.ip_allowlist.arn
      }
    }
  }

  # A private bucket returns 403 (not 404) for a missing key, so both
  # codes have to map to index.html for Vue Router's history mode to
  # survive a hard refresh on a client-side route like /employees/42.
  custom_error_response {
    error_code         = 403
    response_code      = 200
    response_page_path = "/index.html"
  }

  custom_error_response {
    error_code         = 404
    response_code      = 200
    response_page_path = "/index.html"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # No custom domain yet (none exists for this project) — CloudFront's
  # own *.cloudfront.net certificate is free HTTPS with no ACM/Route53
  # setup required. Swap this for an ACM cert + aliases if a domain is
  # ever added.
  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = local.tags
}
