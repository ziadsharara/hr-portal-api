# infra/ — read this before running `terraform apply` anywhere

This Terraform module provisions **one shared AWS stack** for both
services: VPC (default), RDS, ECS cluster, both ECS services, the ALB,
and the two ECR repos. It's intentionally a single stack, not two,
because the ALB routes to both services and both need the same cluster
and network.

Because `hr-portal-api` and `hr-portal-frontend` are separate git repos,
an **identical copy of this `infra/` directory exists in both repos**
(that was a deliberate simplicity tradeoff — see the repo's history for
the alternative of a dedicated third "infra" repo, which was passed
over). That duplication creates one real risk that doesn't show up until
someone hits it:

> **Only ever run `terraform apply` from ONE of the two copies.**
> Applying from both would try to create the same ECS cluster, ALB, RDS
> instance, etc. twice — either a hard failure on name collisions, or
> (worse) two divergent Terraform state files each believing they own
> the same real-world resources, fighting each other on every future
> apply.

**Canonical copy: `hr-portal-api/infra/`.** That's the one with real
remote state (once configured — see `versions.tf`) and the one anyone
should `terraform apply` from. `hr-portal-frontend/infra/` is a synced
read-only mirror, kept for visibility/review in that repo's PRs, but
never applied from.

If you change anything under `infra/`, change it in the backend repo
first, then copy the same change into the frontend repo's `infra/` so
the two don't drift apart and confuse the next person.
