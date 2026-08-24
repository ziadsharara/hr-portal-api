# Deployment

This repo (`hr-portal-api`) is one half of a two-repo deployment —
`hr-portal-frontend` is the sibling repo, expected checked out next to
this one on disk (`../frontend`) for local dev, and deployed as its own
separate ECS service in AWS.

## ⚠️ No authentication yet — read this before touching `ALLOWED_CIDR`

**The API has no authentication or authorization of any kind.** Every
endpoint is open to whatever can reach it over the network. The entire
safety of this deployment rests on ONE control: the ALB's security group
only allows inbound traffic from `var.allowed_cidr` (Terraform, see
`infra/variables.tf`) — your office/VPN IP range.

- `allowed_cidr` has **no default** and a validation rule that hard-fails
  `terraform plan`/`apply` if it's ever set to `0.0.0.0/0`. This is
  deliberate: it must never be possible to accidentally open this API to
  the entire internet by omission.
- **Do not** widen `allowed_cidr`, add an internet-facing listener, or
  put this behind anything other than the office/VPN CIDR **until real
  authentication (e.g. Spring Security + SSO/OAuth2) is added to the
  API.** If you're reading this because someone wants to "just open it
  up for a demo" — don't, until that's fixed first.
- This applies to the whole HR dataset the API serves — names,
  employment data, everything — not just a hypothetical risk.

## Local development (docker-compose)

Brings up MySQL + backend + frontend together. Requires both repos
checked out as siblings (`../frontend` relative to this file).

```bash
cp .env.compose.example .env.compose   # fill in local values — never commit this file
docker compose --env-file .env.compose up --build
```

- Frontend: http://localhost:5173
- Backend: http://localhost:8080/api
- Backend health: http://localhost:8080/api/actuator/health

The backend runs with `SPRING_PROFILES_ACTIVE=prod` in Docker (not
`dev`) — that profile is fully environment-variable-driven with no
hardcoded host/credentials, which is exactly what makes it safe to use
identically in local Docker and in ECS. "prod" here names the *config
style* (externally configured), not literally the AWS production
environment. The `dev` profile (hardcoded `localhost` MySQL, username/
password overridable via `DEV_DB_USERNAME`/`DEV_DB_PASSWORD` env vars) is
only for running the app directly on your machine via
`mvn spring-boot:run`, outside Docker.

An identical `docker-compose.yml` / `.env.compose.example` pair lives in
`../frontend` for people who clone that repo first. If you change one,
change the other the same way.

## CI (`.github/workflows/ci.yml`)

Runs on every push and PR: `mvn clean verify` (compile + test). No
`-DskipTests` — a failing test fails the build. There's a sibling
workflow of the same name in the frontend repo; they're independent and
don't block each other.

## CD (`.github/workflows/cd.yml`)

Runs on every push to `main`, plus manual `workflow_dispatch`. Builds the
Docker image, tags it with the git SHA (and `latest`), pushes to the
`hr-portal-api` ECR repo, then updates the `hr-portal-api` ECS service to
a new task definition revision and waits for the deployment to
stabilize — the job fails if it doesn't.

Authenticates to AWS via **GitHub OIDC**, not stored access keys: the
workflow exchanges its own short-lived OIDC token for temporary AWS
credentials by assuming `infra`'s `github_deploy_backend` IAM role, which
trusts *only* this repo (`token.actions.githubusercontent.com:sub` is
pinned to `repo:<org>/hr-portal-api:ref:refs/heads/main` — see
`infra/iam_github_oidc.tf`). That role can push to the `hr-portal-api`
ECR repo and update the `hr-portal-api` ECS service — nothing else, and
nothing belonging to the frontend.

## One-time AWS setup

Needed once, before the first real deploy — not on every deploy.

1. **`terraform init` + `apply`** from `infra/` (this repo's copy is
   canonical — see `infra/README.md` for why only one of the two repo
   copies should ever be applied). You'll need:
   - `terraform.tfvars` (copy from `terraform.tfvars.example`, gitignored):
     - `allowed_cidr` — your real office/VPN CIDR. **Never `0.0.0.0/0`.**
     - `github_repo_backend` — e.g. `"my-org/hr-portal-api"`
     - `github_repo_frontend` — e.g. `"my-org/hr-portal-frontend"`
   - AWS credentials for whoever runs `terraform apply` (a human, with
     their own IAM permissions — this is separate from the GitHub OIDC
     deploy role, which only CI ever assumes).
   - Consider setting up remote state (S3 bucket, `versions.tf` has a
     commented template) before more than one person ever runs
     `terraform apply` against this.

2. **The GitHub OIDC provider** (`token.actions.githubusercontent.com`)
   is created by Terraform (`aws_iam_openid_connect_provider.github`) —
   but AWS only allows one such provider per account. If your AWS
   account already has one (e.g. from another project), set
   `create_github_oidc_provider = false` in `terraform.tfvars` instead of
   letting `apply` fail on a duplicate.

3. **After `apply` succeeds**, set these in each repo's GitHub settings
   (Settings → Secrets and variables → Actions → Variables):
   - In `hr-portal-api`: `AWS_DEPLOY_ROLE_ARN` = Terraform's
     `github_deploy_role_arn_backend` output, `AWS_REGION`,
     `ECS_CLUSTER_NAME` = Terraform's `ecs_cluster_name` output.
   - In `hr-portal-frontend`: the same three, but
     `github_deploy_role_arn_frontend` for `AWS_DEPLOY_ROLE_ARN`.

4. **First-ever deploy is a chicken-and-egg step**: Terraform registers
   an initial ECS task definition pointing at an image tag
   (`container_image_tag`, defaults to `:latest`) that doesn't exist in
   ECR yet on a brand-new repo, so the ECS service will sit unable to
   start tasks until an image is actually pushed. After `apply`,
   manually trigger this repo's CD workflow once (`workflow_dispatch`) to
   push a real image and get the service running — then push-to-main
   deploys take over from there.

5. **HTTPS/custom domain**: intentionally not set up yet — the ALB
   serves plain HTTP on its default AWS DNS name (`alb_dns_name`
   Terraform output). `infra/variables.tf`'s `acm_certificate_arn` is a
   placeholder TODO for when a domain is chosen; don't treat the current
   HTTP-only state as permanent, but it's an accepted gap for this first
   pass, layered under `allowed_cidr` restricting who can reach it at
   all.

## What Terraform provisions (`infra/`)

- Default VPC (no custom VPC yet — cost/simplicity tradeoff for an
  internal tool's first deployment).
- RDS MySQL, single instance (not Multi-AZ), private-only (`publicly_accessible
  = false` + security group scoped to the backend ECS tasks' SG — never
  reachable from the internet even though the default VPC's subnets are
  technically "public" subnets).
- ECS Fargate cluster, one service each for backend and frontend, task
  definitions that read DB credentials from **Secrets Manager** via the
  `secrets` block (never plain `environment` values).
- ALB with path-based routing (`/api/*` → backend target group,
  everything else → frontend), security group locked to `allowed_cidr`.
- IAM: a minimal ECS task execution role (ECR pull, Secrets Manager read
  for exactly one secret, log write) and two GitHub OIDC deploy roles
  (backend-scoped, frontend-scoped) — none of these are admin-equivalent.
- CloudWatch log groups for both services.
