variable "aws_region" {
  type        = string
  description = "AWS region to deploy into."
  default     = "us-east-1"
}

variable "project_name" {
  type        = string
  description = "Short name used to prefix/tag all resources."
  default     = "hr-portal"
}

# --- Safety-critical: DO NOT set a default here ---------------------
# The API has NO authentication as of this deployment. There is no
# Cloudflare, no WAF, and no ALB in front of it any more — the EC2
# security group below is now the ONLY control standing between the open
# internet and the full HR dataset (names, employment data, bulk Excel
# import, full CV export).
#
# Leaving this required with no default means `terraform plan`/`apply`
# HARD-FAILS until it is set explicitly. It cannot be silently left open
# by omission, and the validation rule rejects 0.0.0.0/0 outright.
#
# The browser is what calls this API: the Vue app is served from S3 but
# every XHR goes directly to the EC2 public endpoint. So this list must
# contain the public IP of whoever is *using* the demo, not just the
# machine that runs Terraform. Add a CIDR per demo location.
#
#   Find your current public IP with:  curl -s https://checkip.amazonaws.com
variable "api_allowed_cidrs" {
  type        = list(string)
  description = "CIDR blocks allowed to reach the backend API on EC2 and the frontend S3 website. The API has no authentication — this must never contain 0.0.0.0/0."

  validation {
    condition     = !contains(var.api_allowed_cidrs, "0.0.0.0/0")
    error_message = "api_allowed_cidrs must not contain 0.0.0.0/0 — the API has no authentication yet and this would expose the entire HR dataset to the internet. Use your own/office/VPN CIDR instead."
  }

  validation {
    condition     = length(var.api_allowed_cidrs) > 0
    error_message = "api_allowed_cidrs must list at least one CIDR — an empty list would make the deployment unreachable."
  }
}

# --- Safety-critical: DO NOT set a default here ---------------------
# SSH is a direct shell on the box that holds the database. Scoped to a
# single CIDR, separate from api_allowed_cidrs, so that widening API
# access for a demo never silently widens shell access too.
variable "ssh_allowed_cidr" {
  type        = string
  description = "Single CIDR allowed to SSH to the EC2 instance (your IP only, e.g. \"203.0.113.4/32\")."

  validation {
    condition     = var.ssh_allowed_cidr != "0.0.0.0/0"
    error_message = "ssh_allowed_cidr must not be 0.0.0.0/0 — this instance hosts the database. Use your own IP, e.g. \"203.0.113.4/32\"."
  }
}

variable "ssh_key_name" {
  type        = string
  description = "Name of an existing EC2 key pair for SSH. Empty string disables SSH entirely — use SSM Session Manager instead (the instance profile already allows it), which needs no key, no open port 22, and no public IP."
  default     = ""
}

# --- Required for the GitHub OIDC trust policies in iam_github_oidc.tf ---
# hr-portal-api and hr-portal-frontend are separate GitHub repos with
# their own CD workflows (cd.yml in each), so each gets its own deploy
# role: the backend's can push to ECR and redeploy the EC2 instance, the
# frontend's can only write to the S3 site bucket. Never wildcard these,
# and never let one repo's role reach the other's resources.
variable "github_repo_backend" {
  type        = string
  description = "GitHub \"org/repo\" for the hr-portal-api backend, e.g. \"ziadsharara/hr-portal-api\"."
}

variable "github_repo_frontend" {
  type        = string
  description = "GitHub \"org/repo\" for the hr-portal-frontend frontend, e.g. \"ziadsharara/hr-portal-frontend\"."
}

# --- GitHub OIDC subject claim IDs ------------------------------------
# GitHub has begun issuing OIDC tokens whose `sub` claim embeds immutable
# numeric IDs:
#
#   repo:<owner>@<owner_id>/<repo>@<repo_id>:ref:refs/heads/<branch>
#
# rather than the long-documented plain form:
#
#   repo:<owner>/<repo>:ref:refs/heads/<branch>
#
# This was found the hard way: the first CD run failed with "Not
# authorized to perform sts:AssumeRoleWithWebIdentity", and CloudTrail
# showed the presented sub carrying the IDs while the trust policy
# matched only the plain form.
#
# Setting these makes the trust policy accept BOTH forms, so it works
# whichever GitHub sends. Leave them empty to allow only the plain form.
# Pinning the IDs is the stronger of the two: numeric IDs survive a
# rename, so an attacker cannot claim a freed-up owner or repo name and
# inherit the trust.
#
# Find them with:
#   gh api users/<owner>       --jq .id
#   gh api repos/<owner>/<repo> --jq .id
variable "github_owner_id" {
  type        = string
  description = "Numeric GitHub account ID of the repositories' owner. Empty disables the ID-bearing sub form."
  default     = ""
}

variable "github_repo_backend_id" {
  type        = string
  description = "Numeric GitHub repository ID for the backend repo."
  default     = ""
}

variable "github_repo_frontend_id" {
  type        = string
  description = "Numeric GitHub repository ID for the frontend repo."
  default     = ""
}

variable "create_github_oidc_provider" {
  type        = bool
  description = "Whether to create the GitHub Actions OIDC identity provider. AWS allows only one provider per issuer URL per account — set this to false if token.actions.githubusercontent.com is already registered (e.g. by another repo's Terraform)."
  default     = true
}

variable "github_deploy_branch" {
  type        = string
  description = "Branch the CD deploy roles' trust policies are scoped to (matches cd.yml's trigger branch)."
  default     = "main"
}

# --- Database (MySQL container on the EC2 instance) -------------------
# Not RDS. The database runs as a container beside the backend on the
# same instance, with its data directory on a dedicated EBS volume so it
# survives instance replacement. See ec2.tf and DEPLOYMENT.md for the
# tradeoffs this carries versus the managed RDS instance it replaced.
variable "db_name" {
  type    = string
  default = "hr_portal"
}

variable "db_username" {
  type        = string
  description = "Application DB username. The password is generated by Terraform (random_password) and stored only in Secrets Manager — never set it here."
  default     = "hr_portal_app"
}

variable "db_image_tag" {
  type        = string
  description = "Tag of the hr-portal-db image (aws_ecr_repository.db, built from db/image/Dockerfile) to run. That image is MySQL 8.0 plus db/init/01_schema.sql and db/seed/01_seed_demo_data.sql baked in as init scripts — do not swap the base image for Postgres without migrating the application layer first (mysql-connector-j, jdbc:mysql:// URLs, hand-written schema DDL)."
  default     = "latest"
}

# --- EC2 -------------------------------------------------------------
# PIN THIS ONCE DEPLOYED, and do not change it afterwards.
#
# This subnet decides the availability zone, and the database volume
# lives in that zone. An EBS volume cannot move between zones, so
# changing this value asks Terraform to destroy and recreate the volume —
# i.e. to delete the HR database. prevent_destroy on the volume turns
# that into a failed apply rather than data loss, but the way to avoid
# the situation entirely is to leave this alone.
#
# Empty picks the lowest-sorted subnet in the default VPC, which is fine
# for a first apply into a fresh account and wrong for every apply after.
variable "subnet_id" {
  type        = string
  description = "Subnet for the instance; also fixes the AZ of the database volume. Pin it after the first apply — changing it cannot move the volume."
  default     = ""
}

variable "instance_type" {
  type        = string
  description = "Runs both the Spring Boot container and the MySQL container, so it needs headroom for both. t3.small (2 GiB) is the practical floor; t3.micro (1 GiB) will OOM once the JVM and MySQL are both warm."
  default     = "t3.small"
}

variable "backend_port" {
  type        = number
  description = "Host port the backend container publishes. MySQL deliberately publishes NO host port at all — see ec2.tf."
  default     = 8080
}

variable "db_data_volume_size_gb" {
  type        = number
  description = "Size of the dedicated EBS volume holding MySQL's data directory, mounted at /var/lib/hr-portal/mysql."
  default     = 20
}

variable "root_volume_size_gb" {
  type    = number
  default = 20
}

# --- Bootstrapping ---------------------------------------------------
# The very first `terraform apply` boots the instance before CD has ever
# pushed an image, so ":latest" will not exist in ECR yet on a brand-new
# repository. The backend container will fail to start until the first CD
# run pushes a real image. This is expected — see DEPLOYMENT.md's
# first-time setup (apply, then trigger CD once).
variable "container_image_tag" {
  type    = string
  default = "latest"
}
