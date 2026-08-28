# --- Frontend: CloudFront in front of the private S3 bucket -----------
# Fixes the two things a plain S3 website endpoint cannot do: serve HTTPS
# (browsers' HTTPS-first upgrade behavior can otherwise fail to load the
# site outright) and cache at the edge (one S3 region + HTTP/1.1 means
# full round-trip latency for every user, every load). See s3.tf and
# DEPLOYMENT.md for the before/after.
#
# The bucket is private now (Origin Access Control, not the old public
# IP-conditioned policy), so the CloudFront Functions below are what
# re-implement api_allowed_cidrs at the edge — see cloudfront_function.js.tftpl
# for why.
#
# Both functions are always attached; allow_public_api_access
# (variables.tf) is baked into their CODE as a runtime flag instead of
# controlling whether they're attached at all. The S3-side function also
# does the Vue Router SPA-fallback rewrite, which has to run on every
# request regardless of that flag — and CloudFront allows only one
# function per event type per behavior, so toggling attachment would have
# meant either losing the rewrite while public, or duplicating it into a
# second function kept in sync with the first.
#
# /api/* on this same distribution proxies to the EC2 backend (see the
# second origin and ordered_cache_behavior below). This exists because
# putting the frontend on HTTPS while the API stayed on plain HTTP turned
# a merely-insecure setup into a BROKEN one: browsers block active mixed
# content (fetch/XHR from an HTTPS page to an http:// URL) outright, so
# every API call from the CloudFront-served frontend would otherwise
# fail silently. Routing both through one HTTPS origin fixes that and
# means the frontend build can go back to a relative VITE_API_BASE_URL
# (/api) instead of an absolute cross-origin one — see DEPLOYMENT.md.

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
  comment = "IP allowlist + SPA fallback rewrite for the S3 origin"
  publish = true

  code = templatefile("${path.module}/cloudfront_function.js.tftpl", {
    cidrs             = var.api_allowed_cidrs
    enforce_allowlist = !var.allow_public_api_access
  })
}

resource "aws_cloudfront_function" "api_ip_allowlist" {
  name    = "${local.name_prefix}-api-ip-allowlist"
  runtime = "cloudfront-js-2.0"
  comment = "IP allowlist for the /api/* origin, no SPA rewrite"
  publish = true

  code = templatefile("${path.module}/cloudfront_function_api.js.tftpl", {
    cidrs             = var.api_allowed_cidrs
    enforce_allowlist = !var.allow_public_api_access
  })
}

resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  comment             = "${local.name_prefix} frontend"
  default_root_object = "index.html"
  price_class         = var.cloudfront_price_class

  # Off unless access is deliberately public: both CloudFront Functions'
  # IP allowlist only checks IPv4 (see cloudfront_function.js.tftpl), so
  # enabling IPv6 while enforce_allowlist is baked in as true would be a
  # silent bypass of api_allowed_cidrs for any viewer connecting over
  # IPv6. Once allow_public_api_access is on, enforce_allowlist is false
  # and there's no restriction left for an IPv6 viewer to bypass — and
  # leaving IPv6 off in that case would just be an availability bug for
  # anyone on IPv6-only networks, the same failure mode s3.tf's
  # SourceArn/aws:SourceIp comment already covers for the old bucket
  # policy.
  is_ipv6_enabled = var.allow_public_api_access

  origin {
    # The REST regional endpoint, not the website endpoint — Origin
    # Access Control only works against the S3 REST API. The website
    # endpoint (and the error_document trick it used for Vue Router's
    # history mode) is gone; the SPA rewrite in the ip_allowlist
    # CloudFront Function (below) replaces it.
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_id                = "s3-frontend"
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  origin {
    # CloudFront custom origins must be addressed by DNS name, not a bare
    # IP. Deliberately built from aws_eip.backend.public_ip rather than
    # aws_instance.backend.public_dns: the whole point of the Elastic IP
    # (see ec2.tf) is that it's stable across an instance replacement,
    # and this origin should be too — depending on the instance directly
    # would make any future instance replacement (e.g. once the
    # hr-portal-db ECR image gap is resolved) force a CloudFront update
    # in lockstep for no reason. AWS's own public-DNS format for an EC2
    # public IP is deterministic, so this needs no separate Route53
    # record — but the format itself differs for us-east-1 (a legacy
    # naming quirk: "compute-1", not "<region>.compute") versus every
    # other region.
    domain_name = "ec2-${replace(aws_eip.backend.public_ip, ".", "-")}.${data.aws_region.current.name == "us-east-1" ? "compute-1" : "${data.aws_region.current.name}.compute"}.amazonaws.com"
    origin_id   = "ec2-backend"

    custom_origin_config {
      # http-only because the backend has no TLS listener (see ec2.tf /
      # DEPLOYMENT.md's "no TLS on the API" gap) — CloudFront terminates
      # HTTPS for the viewer and talks plain HTTP to this origin, which
      # is exactly what fixes the mixed-content problem without needing
      # a certificate on the EC2 instance itself.
      origin_protocol_policy = "http-only"
      http_port              = var.backend_port
      https_port             = 443
      origin_ssl_protocols   = ["TLSv1.2"]
    }
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

    # Always attached — enforce_allowlist inside the function's own code
    # is what allow_public_api_access actually toggles. See the header
    # comment on why this can't be a dynamic block instead.
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.ip_allowlist.arn
    }
  }

  ordered_cache_behavior {
    path_pattern     = "/api/*"
    target_origin_id = "ec2-backend"

    # The API needs the full HTTP verb set, not just GET/HEAD — it's a
    # real Spring Boot backend (POST/PUT/PATCH/DELETE), not a static
    # site.
    allowed_methods = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods  = ["GET", "HEAD"]
    compress        = true

    viewer_protocol_policy = "redirect-to-https"

    # AWS managed "CachingDisabled": API responses are per-request and
    # must never be served from the edge cache.
    cache_policy_id = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"

    # AWS managed "AllViewer": forwards every header, cookie, and query
    # string through untouched, since this is a proxy to a real API, not
    # a cacheable static asset — CachingOptimized (used above for S3)
    # would strip most of that.
    origin_request_policy_id = "216adef6-5c7f-47e4-b989-5492eafa07d3"

    # Same edge-level IP allowlist as the frontend (via its own function,
    # not the shared one — this one has no SPA rewrite). The API is
    # exactly as sensitive as the frontend it sits behind (no auth
    # either way), so it gets the same protection.
    #
    # Deliberately NOT a custom_error_response for 403/404 on this
    # behavior: custom_error_response is distribution-wide in the AWS
    # provider, not scoped per path pattern, so a 403/404->index.html
    # mapping here would silently rewrite genuine API error responses
    # (e.g. a real "employee not found" 404) into an HTML page instead of
    # JSON. The S3 behavior's SPA fallback is handled inside its own
    # CloudFront Function instead, precisely to avoid needing
    # custom_error_response at the distribution level at all.
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.api_ip_allowlist.arn
    }
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
