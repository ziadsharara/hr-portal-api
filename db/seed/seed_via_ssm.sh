#!/bin/bash
# Loads db/seed/01_seed_demo_data.sql into the MySQL container running on
# the deployed EC2 instance, over SSM Run Command — never over a
# published port, since docker-compose.yml deliberately never publishes
# 3306 (see the comment there). This is the same access pattern
# DEPLOYMENT.md already documents for ad-hoc DB inspection, just scripted
# and fed a file instead of dropping into an interactive shell.
#
# The dump (~30KB gzip+base64) travels as the SSM command payload itself
# rather than via S3 — comfortably under the ~100,000 character command
# size limit, and it means no new S3 bucket / IAM permission is needed
# just to seed demo data once.
#
# Usage:
#   ./seed_via_ssm.sh <instance-id> [region]
#
# <instance-id> is `terraform output -raw backend_instance_id` from
# infra/. Requires AWS credentials for the hr-portal account with
# ssm:SendCommand / ssm:GetCommandInvocation on that instance.
set -euo pipefail

INSTANCE_ID="${1:?usage: seed_via_ssm.sh <instance-id> [region]}"
REGION="${2:-us-east-1}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DUMP_FILE="$SCRIPT_DIR/01_seed_demo_data.sql"

if [ ! -f "$DUMP_FILE" ]; then
  echo "FATAL: $DUMP_FILE not found" >&2
  exit 1
fi

echo "Encoding $DUMP_FILE ..."
ENCODED=$(gzip -c "$DUMP_FILE" | base64 -w0)
echo "Payload size: ${#ENCODED} chars (gzip+base64)"

# Decode into a temp file, load it into the mysql container using the
# credentials already on the box (/opt/hr-portal/.env — the same file
# deploy.sh reads), then remove the temp file regardless of outcome.
read -r -d '' REMOTE_CMD <<'EOF' || true
set -euo pipefail
cd /opt/hr-portal
set -a; source .env; set +a
echo "__PAYLOAD__" | base64 -d | gunzip > /tmp/seed_demo_data.sql
trap 'rm -f /tmp/seed_demo_data.sql' EXIT
docker compose exec -T mysql mysql -u root -p"$DB_ROOT_PASSWORD" "$DB_NAME" < /tmp/seed_demo_data.sql
echo "Seed applied."
docker compose exec -T mysql mysql -u root -p"$DB_ROOT_PASSWORD" "$DB_NAME" \
  -N -B -e "SELECT CONCAT('employees=', (SELECT COUNT(*) FROM employees), ' experiences=', (SELECT COUNT(*) FROM experiences));"
EOF

REMOTE_CMD="${REMOTE_CMD//__PAYLOAD__/$ENCODED}"

echo "Sending SSM command to $INSTANCE_ID ..."
CMD_ID=$(aws ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --region "$REGION" \
  --document-name "AWS-RunShellScript" \
  --parameters "commands=[$(printf '%s' "$REMOTE_CMD" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')]" \
  --query "Command.CommandId" --output text)

echo "Command ID: $CMD_ID — waiting for completion ..."
aws ssm wait command-executed --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" --region "$REGION" || true

STATUS=$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" --region "$REGION" --query "Status" --output text)
echo "--- stdout ---"
aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" --region "$REGION" --query "StandardOutputContent" --output text
echo "--- stderr ---"
aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" --region "$REGION" --query "StandardErrorContent" --output text
echo "Status: $STATUS"

if [ "$STATUS" != "Success" ]; then
  echo "FAILED: seed command did not succeed" >&2
  exit 1
fi
