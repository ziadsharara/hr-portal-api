# The backend app image and the demo-data MySQL image. The frontend used
# to build an nginx container and push it here too; it is a static bundle
# on S3 (see s3.tf), so that repository is gone.
resource "aws_ecr_repository" "backend" {
  name                 = "hr-portal-api"
  image_tag_mutability = "MUTABLE" # see the note on the :latest tag below

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = local.tags
}

# MUTABLE, unlike the ECS version of this stack, and that is a deliberate
# downgrade worth understanding. cd.yml pushes each build under both the
# git SHA and :latest, and the EC2 bootstrap pulls :latest on first boot
# because a brand-new instance has no way to know the current SHA. A
# moving :latest tag is incompatible with IMMUTABLE, which rejects any
# re-push of an existing tag.
#
# The SHA tags are still effectively immutable in practice (nothing ever
# re-pushes the same commit), and every actual deploy pins the SHA — only
# the bootstrap uses :latest. If that tradeoff stops being acceptable,
# the fix is to have Terraform pass a real tag into container_image_tag
# rather than to make this repository immutable.

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  # Keep the last 20 tagged images (enough history to roll back several
  # deploys) and expire untagged manifest layers left behind by re-pushes
  # quickly, so the registry does not grow without bound.
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 3 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 3
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the last 20 tagged images"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 20
        }
        action = { type = "expire" }
      },
    ]
  })
}

# Replaces the stock `mysql:8.0` image referenced by docker-compose.yml
# (see user_data.sh.tftpl) with one built from db/image/Dockerfile: same
# base image, plus db/init/01_schema.sql and db/seed/01_seed_demo_data.sql
# copied to /docker-entrypoint-initdb.d/, so a container started against
# an empty data volume bootstraps itself with the demo dataset — no
# separate seed step needed after deploy.
#
# Credentials are NOT baked into this image. MYSQL_ROOT_PASSWORD /
# MYSQL_DATABASE / MYSQL_USER / MYSQL_PASSWORD are still supplied at
# container start from Secrets Manager via user_data (see secrets.tf),
# exactly as before — this repository only changes which image those
# credentials get handed to.
resource "aws_ecr_repository" "db" {
  name                 = "hr-portal-db"
  image_tag_mutability = "MUTABLE" # see the note on aws_ecr_repository.backend's :latest tag

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = local.tags
}

resource "aws_ecr_lifecycle_policy" "db" {
  repository = aws_ecr_repository.db.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 3 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 3
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the last 5 tagged images"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 5
        }
        action = { type = "expire" }
      },
    ]
  })
}
