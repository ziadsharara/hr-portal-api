# Deployment

This repo (`hr-portal-api`) is one half of a two-repo deployment.
`hr-portal-frontend` is the sibling repo, expected checked out next to
this one on disk (`../frontend`) for local dev, and deployed separately
as static files to S3.

## Architecture

    Browser
      |
      +--> CloudFront (HTTPS) ................ ONE origin as far as the browser is concerned
      |      https://<distribution>.cloudfront.net
      |      |
      |      +-- /*      --> S3 bucket (private, OAC) ... the Vue bundle
      |      +-- /api/*  --> EC2 Elastic IP :8080 ........ the API, over plain HTTP
      |                        |
      |                        +-- backend container .... Spring Boot, publishes 8080
      |                        +-- mysql container ...... NO published port, ever
      |                               |
      |                               +-- /var/lib/hr-portal/mysql -> dedicated EBS volume
      |
      +--> EC2 Elastic IP :8080 directly ..... still reachable, unchanged, for
                                                debugging (see api_base_url output)

There is no load balancer, no DNS, and no custom domain. CloudFront
terminates TLS for both the frontend and `/api/*`; the EC2 instance
itself still has no TLS listener of its own — CloudFront simply talks
plain HTTP to it as the origin for that path (see `infra/cloudfront.tf`).
This exists because putting only the frontend on HTTPS while the API
stayed on plain HTTP made the browser block every API call outright
(mixed content), not just leave it insecure — routing `/api/*` through
CloudFront too is what fixes that. Calling the EC2 endpoint directly, as
`api_base_url` still does, remains plain HTTP and is for debugging only.

## WARNING - no authentication, and mostly nothing in front of it

**The API has no authentication or authorization of any kind.** Every
endpoint, including bulk Excel import and full CV export, is open to
whatever can reach it over the network.

In the previous ECS architecture the ALB's security group was the single
control protecting the HR dataset. That is still true in shape, but the
control now lives in two places, and **both** must stay narrow:

- `api_allowed_cidrs` (`infra/variables.tf`) governs the EC2 security
  group **and** two CloudFront Functions — one per path
  (`infra/cloudfront_function.js.tftpl` for the frontend,
  `infra/cloudfront_function_api.js.tftpl` for `/api/*`) — that gate both
  at the edge. The frontend's S3 bucket is private now (Origin Access
  Control) — CloudFront is the only reader — and these Functions are what
  stand in for the old bucket-policy `aws:SourceIp` condition, since
  CloudFront doesn't support that condition and a request reaching either
  origin from CloudFront no longer carries the end user's IP.
- `ssh_allowed_cidr` governs shell access to the box that holds the
  database. It is a separate variable on purpose, so that widening API
  access for a demo never silently widens shell access too.

Both have no default. `ssh_allowed_cidr` still hard-fails on `0.0.0.0/0`
with no way around it. `api_allowed_cidrs` accepts `0.0.0.0/0` only when
`allow_public_api_access = true` is also set — a second, explicit switch,
so the dataset cannot be exposed by omission and so anyone reading
`terraform.tfvars` can see the exposure was chosen deliberately.

**As currently deployed, `api_allowed_cidrs` is `["0.0.0.0/0"]`.** The
app and the API are open to the entire internet, at the owner's explicit
instruction, so the demo could be shown to people on other networks.

What that means concretely, and it is worth being blunt about: there is
no authentication, so anyone who finds the address can read every
employee record, create and modify records, run the bulk Excel import,
and export the full CV set. The `employees` table holds names, emails,
phone numbers, home addresses, national ID numbers, dates of birth,
gender, nationality and insurance numbers.

This is only defensible because the database currently holds **zero
rows**. Before a single real HR record is loaded, either add real
authentication (Spring Security plus SSO/OAuth2) or set
`api_allowed_cidrs` back to specific CIDRs and `allow_public_api_access`
back to `false`. Loading real data while this stands is a reportable
personal-data breach waiting to happen, not a configuration preference.

Note also that both CloudFront Functions are **always** attached to the
distribution — `allow_public_api_access` is baked into their compiled
code as a runtime flag (`enforceAllowlist`) rather than controlling
whether they're attached at all. That's deliberate: the frontend's
Function also does the Vue Router SPA-fallback rewrite, which has to run
on every request regardless of this flag, and CloudFront allows only one
function per event type per behavior — there was no way to swap in a
"no-op" function while public without either losing the rewrite or
duplicating it into a second file kept in sync with the first. When
public access is on, the allowlist check inside the function is simply
skipped; an `IpAddress`-style check only ever matches IPv4 regardless, so
a `0.0.0.0/0` allowlist would still deny every visitor arriving over
IPv6, which is why `is_ipv6_enabled` follows the same flag — IPv6 is only
turned on once there's no allowlist left for it to bypass.

One thing makes this weaker than the ECS setup it replaced, and you
should know it before treating this as anything but a dev/demo stack:
**the database is no longer managed** — see "What moving off RDS gave up"
below. (TLS is no longer a gap for either surface: CloudFront terminates
it for both the frontend and `/api/*` — see Architecture above. The EC2
instance itself still has no TLS listener, but nothing reaches it that
way except direct debugging access via `api_base_url`.)

`api_allowed_cidrs` must contain the public IP of **whoever is using the
demo**, not just the machine that runs Terraform: every request — page
load or API call — goes from the user's browser to CloudFront first.
Find an IP with `curl -s https://checkip.amazonaws.com`.

## What moving off RDS gave up

The database is now a container (`hr-portal-db` — MySQL 8.0 plus the
schema and demo dataset baked in as init scripts, see
[`db/image/README.md`](db/image/README.md)) on the same instance as the
application, with its data directory on a dedicated EBS volume. Relative
to the RDS instance this replaced:

| | RDS (before) | Container on EC2 (now) |
|---|---|---|
| Backups | Automated, 7-day retention, point-in-time recovery | **None.** Nothing snapshots the volume. |
| Failure domain | Separate from the app | Same instance as the app |
| Patching | Managed by AWS | Yours, by hand |
| Deletion safety | `deletion_protection`, final snapshot | `prevent_destroy` on the volume only |

The volume carries `prevent_destroy`, so a `terraform destroy` or an
instance replacement will not take the database with it. But **there is
no backup**, and that is the gap most likely to hurt. Until something
automates it, take a snapshot by hand before any risky change:

```bash
aws ec2 create-snapshot \
  --volume-id "$(terraform -chdir=infra output -raw db_data_volume_id)" \
  --description "hr-portal manual snapshot $(date +%F)"
```

A scheduled AWS Backup plan or a DLM lifecycle policy is the real fix.

## Local development (docker-compose)

Brings up MySQL, the backend, and the frontend's nginx container
together. Requires both repos checked out as siblings (`../frontend`).

```bash
cp .env.compose.example .env.compose   # fill in local values — never commit this file
docker compose --env-file .env.compose up --build
```

- Frontend: http://localhost:5173
- Backend: http://localhost:8080/api
- Backend health: http://localhost:8080/api/actuator/health

Note that the local stack is **not** shaped like the deployment any more:
locally, nginx serves the frontend and proxies `/api` to the backend on
one origin, so `VITE_API_BASE_URL` stays the relative `/api`. In AWS the
frontend is on S3 and the API is on EC2, so the deployed bundle is built
with an absolute cross-origin URL. Both work because the controllers
carry `@CrossOrigin("*")`.

The backend runs with `SPRING_PROFILES_ACTIVE=prod` in Docker, not `dev`.
That profile is entirely environment-variable-driven with no hardcoded
host or credentials, which is what makes it safe to use identically in
local Docker and on the EC2 instance. "prod" names the *config style*
(externally configured), not the AWS production environment.

## Database schema

`spring.jpa.hibernate.ddl-auto=none`, so nothing creates tables at
runtime. The schema lives in `db/init/01_schema.sql` and is applied by
the MySQL container's entrypoint **on first boot only**, against an empty
data directory. The same file is used locally and on EC2.

Two things to know:

- **That file was reconstructed from the JPA entities.** The schema files
  `application.properties` refers to (`01_create_schema.sql`,
  `02_create_dev_schema.sql`) do not exist in this repo or its git
  history, so a fresh database had no schema at all. Column names,
  nullability and the foreign key come straight from the annotations and
  are reliable; VARCHAR lengths, indexes, and collation are inferred. See
  the header comment in that file. If the original DDL is recovered,
  diff it against this before replacing.
- **There is no migration tool.** No Flyway, no Liquibase. Editing the
  schema file does not migrate a running database — it only affects a
  database created from empty. Adding a migration tool is worth doing
  before the schema next changes.

## CI (`.github/workflows/ci.yml`)

Runs on every push and PR: `mvn clean verify` (compile plus test), with
no `-DskipTests`. A failing test fails the build. The frontend repo has
an independent workflow of the same name; they do not block each other.

## CD (`.github/workflows/cd.yml`)

Runs on every push to `main`, plus manual `workflow_dispatch`. It:

1. Builds the Docker image and tags it with the git SHA and `latest`.
2. Pushes both to the `hr-portal-api` ECR repo.
3. Calls **SSM Run Command** to execute `/opt/hr-portal/deploy.sh <sha>`
   on the instance, which pulls the new tag and restarts the container.
4. Polls the command to completion and fails the job if it fails.
5. Polls `/api/actuator/health` afterwards, so a container that starts
   and then dies does not report a green deploy.

The deploy runs over SSM rather than SSH deliberately: there is no key
for CI to hold, no inbound path from GitHub to the instance, and port 22
can stay closed entirely.

Auth is **GitHub OIDC**, not stored access keys. The workflow exchanges
its short-lived OIDC token for temporary AWS credentials by assuming
`infra`'s `github_deploy_backend` role, which trusts only this repo on
only the `main` branch (`token.actions.githubusercontent.com:sub` is
pinned in `infra/iam_github_oidc.tf`). That role can push to one ECR repo
and send SSM commands to one instance. It cannot read the database
secret, and it cannot touch anything belonging to the frontend.

## One-time AWS setup

Needed once, before the first real deploy.

1. **`terraform init` and `apply`** from `infra/` (this repo's copy is
   canonical — see `infra/README.md` for why only one of the two repo
   copies should ever be applied). You will need a `terraform.tfvars`
   (copy from `terraform.tfvars.example`, gitignored) with:
   - `api_allowed_cidrs` — your real CIDRs. **Never `0.0.0.0/0`.**
   - `ssh_allowed_cidr` — your IP only.
   - `ssh_key_name` — leave `""` to disable SSH entirely and use SSM
     Session Manager instead.
   - `github_repo_backend` / `github_repo_frontend` — your `org/repo`
     strings. If you forked these repos, these must point at *your*
     forks or CD cannot assume the roles.

   Confirm which account you are about to build in before applying:

   ```bash
   aws sts get-caller-identity
   ```

   Consider configuring remote state (S3 backend, commented template in
   `versions.tf`) before more than one person runs `terraform apply`.

2. **The GitHub OIDC provider** is created by Terraform, but AWS allows
   only one per issuer URL per account. If the account already has one,
   set `create_github_oidc_provider = false` instead of letting `apply`
   fail on a duplicate.

3. **After `apply` succeeds**, set these repository variables (Settings →
   Secrets and variables → Actions → Variables):

   In `hr-portal-api`:

   | Variable | Value (Terraform output) |
   |---|---|
   | `AWS_REGION` | your region, e.g. `us-east-1` |
   | `AWS_DEPLOY_ROLE_ARN` | `github_deploy_role_arn_backend` |
   | `EC2_INSTANCE_ID` | `backend_instance_id` |

   In `hr-portal-frontend`:

   | Variable | Value (Terraform output) |
   |---|---|
   | `AWS_REGION` | your region |
   | `AWS_DEPLOY_ROLE_ARN` | `github_deploy_role_arn_frontend` |
   | `S3_BUCKET` | `frontend_bucket_name` |
   | `VITE_API_BASE_URL` | `api_base_url` |
   | `CLOUDFRONT_DISTRIBUTION_ID` | `cloudfront_distribution_id` — CD uses this to invalidate the cache after each deploy |

4. **The first deploy is a chicken-and-egg step.** The instance boots and
   tries to pull `:latest` from an ECR repo that has no images yet, so
   the backend container will not start. MySQL still comes up and
   initialises the schema. Trigger this repo's CD workflow once by hand
   (`workflow_dispatch`) to push a real image, then push-to-main deploys
   take over.

5. **Then deploy the frontend**, because `VITE_API_BASE_URL` is baked
   into the bundle at build time and is only known after `apply`.

## Operations

Get a shell without SSH or an open port 22:

```bash
aws ssm start-session --target "$(terraform -chdir=infra output -raw backend_instance_id)"
```

On the box:

```bash
cd /opt/hr-portal
docker compose ps
docker compose logs -f backend
docker compose exec mysql mysql -u root -p     # password is in .env, mode 0600
./deploy.sh <image-tag>                        # same script CD invokes
```

Application logs also go to the CloudWatch log group `/hr-portal/backend`
via the Docker `awslogs` driver.

Bootstrap output, if the instance comes up wrong, is in
`/var/log/user-data.log`.

## Known gaps

Ranked by how likely they are to cause real damage:

1. **No authentication on the API.** Everything else here is mitigation
   for this one fact.
2. **No database backups.** Nothing snapshots the EBS volume.
3. **The EC2 instance itself has no TLS listener.** Not reachable by a
   normal browser flow any more — CloudFront terminates HTTPS for both
   the frontend and `/api/*` — but direct debugging access to the
   instance (`api_base_url`) is still plain HTTP.
4. **No schema migration tool**, and the current schema is reconstructed
   rather than original.
5. **No remote Terraform state**, so two people applying will corrupt
   each other's view of the stack.
6. **Single instance, no redundancy.** Losing it means downtime until a
   new one boots; the data survives on its own volume.
