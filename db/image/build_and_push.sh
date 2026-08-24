#!/bin/bash
# Builds the hr-portal-db image (db/image/Dockerfile: MySQL 8.0 +
# db/init/01_schema.sql + db/seed/01_seed_demo_data.sql baked in as init
# scripts) and pushes it to the ECR repository Terraform creates
# (aws_ecr_repository.db in infra/ecr.tf). Mirrors deploy.sh's login
# pattern — same account, same registry host as the backend app image.
#
# Run this whenever db/init or db/seed change; it is not part of CI,
# since the demo dataset does not change on every commit the way the
# application code does.
#
# Usage:
#   ./build_and_push.sh [tag]
#
# [tag] defaults to "latest", which is what infra/variables.tf's
# db_image_tag defaults to as well — pass a different tag only if you
# also set that variable to match.
set -euo pipefail

TAG="${1:-latest}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_DIR="$(dirname "$SCRIPT_DIR")"   # backend/db
INFRA_DIR="$(dirname "$DB_DIR")/infra"

REPO_URL=$(terraform -chdir="$INFRA_DIR" output -raw ecr_db_repository_url)
REGISTRY="${REPO_URL%%/*}"

echo "Building hr-portal-db:$TAG from $DB_DIR ..."
docker build -t "hr-portal-db:$TAG" -t "$REPO_URL:$TAG" -f "$SCRIPT_DIR/Dockerfile" "$DB_DIR"

echo "Logging in to $REGISTRY ..."
aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"

echo "Pushing $REPO_URL:$TAG ..."
docker push "$REPO_URL:$TAG"

echo "Done. infra/variables.tf's db_image_tag default (\"latest\") already points at this unless you passed a different tag."
