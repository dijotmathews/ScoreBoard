#!/bin/bash
# Deletes everything terraform created for ScoreBoard: the server, CloudFront, the certificate,
# ECR with its images, the S3 backups, the admin password and the GitHub deploy role.
# The data volume leaves a final snapshot holding app.db, which this script does not delete.
#
# Usage: ./destroy.sh [extra terraform options], e.g. -var create_github_oidc_provider=false
set -euo pipefail
cd "$(dirname "$0")"

domain=$(terraform output -raw url | sed 's|https://||')
region=$(terraform output -raw region)
volume=$(terraform state show -no-color aws_ebs_volume.data | awk '$1 == "id" { print $3 }')

cat <<EOF
This deletes the ScoreBoard infrastructure in $region, including:
  - the server, its disks and the site at https://$domain
  - every image in ECR and every Litestream backup in S3
app.db stays in a final snapshot of the data volume $volume.

EOF
read -r -p "Type the domain ($domain) to continue: " answer
[ "$answer" = "$domain" ] || { echo "Cancelled."; exit 1; }

# force_delete and force_destroy must be in the state before terraform destroy can use them.
terraform apply -auto-approve -var allow_destroy=true \
  -target=aws_ecr_repository.app -target=aws_s3_bucket.backups "$@"
terraform destroy -var allow_destroy=true "$@"

snapshot=$(aws ec2 describe-snapshots --region "$region" --owner-ids self \
  --filters "Name=volume-id,Values=$volume" \
  --query 'reverse(sort_by(Snapshots, &StartTime))[0].SnapshotId' --output text)

cat <<EOF

Done. Left to do by hand:
  1. GoDaddy: delete the CNAME "scoreboard" and the certificate validation CNAME (_….scoreboard).
  2. The final snapshot $snapshot holds app.db (about \$0.05 per GB a month).
     Rebuild from it:  terraform apply -var data_snapshot_id=$snapshot
     Delete it:        aws ec2 delete-snapshot --region $region --snapshot-id $snapshot
  3. The Terraform state bucket, if you are not coming back (see backend.hcl):
     aws s3 rb s3://<state bucket> --force   (also delete its old object versions in the console)
  4. GitHub: remove the repository variable AWS_DEPLOY_ROLE_ARN, so pushes to main skip deploying.
EOF
