# --- The whole backend: one EC2 instance running two containers --------
# Replaces the ECS Fargate cluster, both ECS services, the ALB, and the
# RDS instance. The Spring Boot container and the MySQL container share a
# private Docker bridge network; only the backend publishes a host port.

# A stable address matters more here than it looks. The Vue app is built
# by CD with VITE_API_BASE_URL baked into the bundle at build time, so
# the API's address is a compile-time constant of the frontend. Without
# an Elastic IP, every instance stop/start would hand out a new public IP
# and silently break the deployed frontend until it was rebuilt.
resource "aws_eip" "backend" {
  domain = "vpc"
  tags   = merge(local.tags, { Name = "${local.name_prefix}-backend" })
}

resource "aws_eip_association" "backend" {
  instance_id   = aws_instance.backend.id
  allocation_id = aws_eip.backend.id
}

# MySQL's data directory lives here, not on the root volume, so that
# replacing the instance (an AMI refresh, an instance-type change, a
# user_data edit) does not destroy the database. This volume is the only
# stateful thing in the deployment.
resource "aws_ebs_volume" "db_data" {
  availability_zone = aws_instance.backend.availability_zone
  size              = var.db_data_volume_size_gb
  type              = "gp3"
  encrypted         = true

  tags = merge(local.tags, { Name = "${local.name_prefix}-db-data" })

  lifecycle {
    # This volume holds the HR dataset. Terraform must never destroy it
    # as a side effect of an unrelated change; deleting it has to be a
    # deliberate act (remove this block, or `terraform state rm` first).
    prevent_destroy = true
  }
}

resource "aws_volume_attachment" "db_data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.db_data.id
  instance_id = aws_instance.backend.id
}

resource "aws_instance" "backend" {
  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  # Empty ssh_key_name means no key pair is attached at all, which pairs
  # with security_groups.tf omitting the port 22 rule. SSM Session
  # Manager is then the only way in.
  key_name = var.ssh_key_name == "" ? null : var.ssh_key_name

  # Still needed despite the Elastic IP above. The EIP is associated by a
  # separate resource that runs *after* the instance exists, but user_data
  # runs at first boot and has to reach ECR, Secrets Manager and the
  # package mirrors before that association happens.
  associate_public_ip_address = true

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
    encrypted   = true
  }

  # IMDSv2 required. With v1 optional, any SSRF in the application could
  # read the instance profile's credentials with a plain GET — and this
  # profile can read the DB secret.
  metadata_options {
    http_tokens                 = "required"
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 2 # containers are one network hop from the host
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    aws_region    = data.aws_region.current.name
    db_secret_arn = aws_secretsmanager_secret.db.arn
    ecr_repo_url  = aws_ecr_repository.backend.repository_url
    # `docker login` authenticates against the REGISTRY host, not a
    # repository path — passing the full repository_url to it fails to
    # authorise the pull.
    ecr_registry    = split("/", aws_ecr_repository.backend.repository_url)[0]
    image_tag       = var.container_image_tag
    db_engine_image = var.db_engine_image
    backend_port    = var.backend_port
    log_group       = aws_cloudwatch_log_group.backend.name
    db_mount_point  = local.db_mount_point
    schema_sql      = file("${path.module}/../db/init/01_schema.sql")
  })

  # Editing user_data rewrites the bootstrap, but cloud-init only runs it
  # on first boot — so a changed script would otherwise sit unapplied on
  # a running instance while Terraform reported success. Replacing the
  # instance is the honest behaviour; the database survives on its own
  # EBS volume.
  user_data_replace_on_change = true

  tags = merge(local.tags, { Name = "${local.name_prefix}-backend" })
}
