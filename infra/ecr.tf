# Only the backend has an image registry now. The frontend used to build
# an nginx container and push it here too; it is a static bundle on S3
# (see s3.tf), so that repository is gone.
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
