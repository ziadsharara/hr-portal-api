terraform {
  required_version = ">= 1.7.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # No remote backend configured yet — state defaults to local
  # terraform.tfstate, which is fine for a first solo apply but not once
  # more than one person runs terraform. Before the first REAL apply,
  # create an S3 bucket (+ DynamoDB table for locking, or use S3 native
  # locking on TF >= 1.10) and uncomment this block. See DEPLOYMENT.md.
  #
  # backend "s3" {
  #   bucket       = "CHANGE-ME-hr-portal-terraform-state"
  #   key          = "hr-portal/terraform.tfstate"
  #   region       = "us-east-1"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.aws_region
}
