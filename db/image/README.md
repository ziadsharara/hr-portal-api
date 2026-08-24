# hr-portal-db image

`Dockerfile` here builds on stock `mysql:8.0` and bakes in
`db/init/01_schema.sql` and `db/seed/01_seed_demo_data.sql` as
`/docker-entrypoint-initdb.d/` scripts. MySQL's own entrypoint runs those
automatically, in filename order, the first time a container from this
image starts against an **empty** data directory — so a fresh deploy
gets the schema and the 90-employee demo dataset with no extra seed step.
An existing, already-populated data directory (the EC2 instance's EBS
volume, once it has real data on it) is left untouched; the init scripts
only ever run once, against empty.

**Credentials are not baked into this image.** `MYSQL_ROOT_PASSWORD` /
`MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` are still supplied at
container start exactly as with the stock image — in the deployed stack
that's `infra/secrets.tf`'s Terraform-generated passwords, delivered via
Secrets Manager and `user_data.sh.tftpl` into `/opt/hr-portal/.env`, then
into `docker-compose.yml`'s `mysql` service. This image only changes
*what* those credentials get handed to.

## Build & run locally

```bash
docker build -t hr-portal-db:demo -f Dockerfile ..   # context is backend/db
docker run -d --name hr-portal-db-test \
  -e MYSQL_ROOT_PASSWORD=<pick one> \
  -e MYSQL_DATABASE=hr_portal \
  -e MYSQL_USER=hr_portal_app \
  -e MYSQL_PASSWORD=<pick one> \
  -p 3307:3306 \
  hr-portal-db:demo
```

## Push to AWS

Requires `infra/`'s `aws_ecr_repository.db` to already exist (i.e.
`terraform apply` has run at least once) and AWS credentials for the
hr-portal account with `ecr:GetAuthorizationToken` / push access to that
repository.

```bash
./build_and_push.sh          # tags & pushes :latest
```

`infra/variables.tf`'s `db_image_tag` (default `"latest"`) controls which
tag the deployed instance actually pulls — `ec2.tf` wires
`aws_ecr_repository.db.repository_url:${var.db_image_tag}` into
`docker-compose.yml`'s `mysql.image`. Push a new tag and bump
`db_image_tag` (or just push `:latest` again and replace the instance)
to roll out a change.

**This only affects a container started against an empty volume.** If
the EC2 instance's EBS volume already has data on it (i.e. this isn't
the very first boot), pushing a new image here does nothing to existing
data — MySQL's init scripts do not re-run. That is by design: it is what
keeps a routine image update from ever silently wiping a live database.
