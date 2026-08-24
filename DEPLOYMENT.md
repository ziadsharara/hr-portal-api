# Deployment

This repo (`hr-portal-api`) is one half of a two-repo deployment.
`hr-portal-frontend` is the sibling repo, expected checked out next to
this one on disk (`../frontend`) for local dev, and deployed separately
as static files to S3.

## Architecture

    Browser
      |
      +--> S3 website endpoint ............... the Vue bundle (static files)
      |      http://<bucket>.s3-website.<region>.amazonaws.com
      |
      +--> EC2 Elastic IP :8080 .............. the API (cross-origin XHR)
                |
                +-- backend container ........ Spring Boot, publishes 8080
                +-- mysql container .......... NO published port, ever
                       |
                       +-- /var/lib/hr-portal/mysql -> dedicated EBS volume

There is no load balancer, no CDN, no DNS, and no TLS. The two public
surfaces are the S3 website endpoint and the instance's Elastic IP, and
both are restricted by `api_allowed_cidrs`.

## WARNING - no authentication, and now nothing in front of it

**The API has no authentication or authorization of any kind.** Every
endpoint, including bulk Excel import and full CV export, is open to
whatever can reach it over the network.

In the previous ECS architecture the ALB's security group was the single
control protecting the HR dataset. That is still true in shape, but the
control now lives in two places, and **both** must stay narrow:

- `api_allowed_cidrs` (`infra/variables.tf`) governs the EC2 security
  group **and** the S3 bucket policy's `aws:SourceIp` condition.
- `ssh_allowed_cidr` governs shell access to the box that holds the
  database. It is a separate variable on purpose, so that widening API
  access for a demo never silently widens shell access too.

Both have no default and a validation rule that hard-fails
`terraform plan`/`apply` on `0.0.0.0/0`. That is deliberate: it must not
be possible to expose the HR dataset by omission. **Do not remove those
rules to "just open it up for a demo"** until real authentication (Spring
Security plus SSO/OAuth2) exists in the API.

Two things make this weaker than the ECS setup it replaced, and you
should know both before treating this as anything but a dev/demo stack:

1. **There is no TLS anywhere.** S3 website endpoints cannot serve
   HTTPS, and nothing terminates TLS on the instance. All traffic,
   including whatever HR data the app displays, crosses the network in
   plaintext. Restoring a CDN (CloudFront or Cloudflare) in front of both
   surfaces is what fixes this.
2. **The database is no longer managed.** See "What moving off RDS gave
   up" below.

`api_allowed_cidrs` must contain the public IP of **whoever is using the
demo**, not just the machine that runs Terraform: the Vue app is served
from S3, but every API call goes from the user's browser straight to the
EC2 endpoint. Find an IP with `curl -s https://checkip.amazonaws.com`.

## What moving off RDS gave up

The database is now a `mysql:8.0` container on the same instance as the
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
3. **No TLS.** Every surface is plaintext HTTP.
4. **No schema migration tool**, and the current schema is reconstructed
   rather than original.
5. **No remote Terraform state**, so two people applying will corrupt
   each other's view of the stack.
6. **Single instance, no redundancy.** Losing it means downtime until a
   new one boots; the data survives on its own volume.
