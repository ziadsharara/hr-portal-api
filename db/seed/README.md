# Demo data seed

`01_seed_demo_data.sql` is 90 fictional employees (SAP consulting roster,
`company='CIC'`) with 6-8 experience rows each. **Every value is
fake/generated** — names, IDs, addresses, project history, all of it —
built to match the shape of the real data (SAP module positions, real
client/industry/country vocabulary already used elsewhere in this
project) without containing anyone's actual personal information. Safe
to commit, safe to load into any environment.

It is data-only (`INSERT`s against `employees`/`experiences`), not
schema — schema is `db/init/01_schema.sql`, applied automatically by the
MySQL container's entrypoint on first boot. This file does the DELETE +
re-INSERT itself, so re-running it is safe and replaces prior contents of
just these two tables rather than erroring or duplicating rows.

## Local dev

```bash
mysql -h127.0.0.1 -P3306 -uroot -p hr_portal_dev < db/seed/01_seed_demo_data.sql
```

## Deployed (EC2)

The deployed MySQL container never publishes port 3306 (see the comment
in `docker-compose.yml` written by `infra/user_data.sh.tftpl`) — the only
way in is through the instance itself, and the only way to the instance
is SSM (no SSH). `seed_via_ssm.sh` does exactly what DEPLOYMENT.md's
manual-inspection note describes, scripted:

```bash
cd infra && terraform output -raw backend_instance_id   # get the instance id
cd ../db/seed
./seed_via_ssm.sh <instance-id> <region>
```

This sends the dump (gzip+base64, ~30KB) as an SSM Run Command payload,
decodes it on the box, and loads it into the running `mysql` container
using the credentials already in `/opt/hr-portal/.env`. Requires AWS
credentials for the hr-portal account with `ssm:SendCommand` /
`ssm:GetCommandInvocation` on that instance — nothing else, and it never
touches the DB credentials directly (they stay on the instance, sourced
from the `.env` file `deploy.sh` already relies on).

Run it once after the stack first comes up (or any time you want to
reset back to this fixture set) — not on every deploy.
