# ScoreBoard on AWS

```
https://scoreboard.enigma-tech.in (CNAME at GoDaddy)
        │
CloudFront ── ACM certificate (us-east-1), no caching, WebSockets passed through
        │ HTTP :80, only from CloudFront
EC2 t4g.small, ap-south-1 (Amazon Linux 2023, Docker Compose)
  ├─ app          the image from ECR, prod profile, /data/app.db
  ├─ litestream   streams app.db to the S3 backup bucket
  └─ /data        its own EBS volume, kept when the server is replaced
```

GitHub Actions builds each push to `main`, pushes the image to ECR and runs
`/opt/scoreboard/deploy.sh <tag>` on the server through SSM. If the new image does not
answer within 3 minutes, the script starts the previous one again.

## First-time setup

You need the AWS CLI (signed in as an administrator of the account) and Terraform 1.10 or later.

1. **State bucket** (once per account):
   ```bash
   ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
   aws s3api create-bucket --bucket scoreboard-tfstate-$ACCOUNT --region ap-south-1 \
     --create-bucket-configuration LocationConstraint=ap-south-1
   aws s3api put-bucket-versioning --bucket scoreboard-tfstate-$ACCOUNT \
     --versioning-configuration Status=Enabled
   cp backend.hcl.example backend.hcl   # then set the bucket name in it
   terraform init -backend-config=backend.hcl
   ```
   If the account already has a GitHub OIDC provider, add
   `-var create_github_oidc_provider=false` to every `plan`/`apply` below.

2. **Certificate request.** Create the certificate first, so you have its validation record:
   ```bash
   terraform apply -target=aws_acm_certificate.site
   terraform output -json dns_records | jq .certificate_validation
   ```
   At GoDaddy (DNS → Add record), add that **CNAME**. In GoDaddy's Name field, leave out
   the trailing `.enigma-tech.in.`, e.g. `_3f9a….scoreboard`.

3. **Everything else:**
   ```bash
   terraform apply
   ```
   This waits until ACM sees the validation record, usually a few minutes. CloudFront then
   takes about 5 minutes more.

4. **Site record.** At GoDaddy, add a **CNAME** named `scoreboard` pointing to the
   `dns_records.site.value` output (`d….cloudfront.net`).

5. **GitHub.** In the repository settings, under Secrets and variables → Actions → Variables, add
   `AWS_DEPLOY_ROLE_ARN` = the `github_deploy_role_arn` output. Then push to `main` (or run
   the Deploy workflow) to build and start the first image.

6. **Sign in** at https://scoreboard.enigma-tech.in as `admin`, with the password from:
   ```bash
   $(terraform output -raw admin_password_command)
   ```
   Change it in the app afterwards. The stored password is only used on an empty database.

## Day to day

- **Shell on the server:** `aws ssm start-session --target $(terraform output -raw instance_id)`
  (needs the Session Manager plugin). There is no SSH.
- **Logs:** CloudWatch log group `/scoreboard/app` (streams `app` and `litestream`), or
  `cd /opt/scoreboard && sudo docker compose logs -f` on the server.
- **Redeploy or roll back to an image:** `sudo /opt/scoreboard/deploy.sh <git-sha>` on the server.

## Backups and restore

Litestream copies every change to `app.db` to the backup bucket within seconds. It keeps
daily snapshots for 7 days, and S3 keeps replaced files for 30 days.

- **New server, same data volume** (the usual case, e.g. after changing the boot script): nothing to do.
- **Lost data volume:** the compose `restore` step brings `app.db` back from S3 when
  `/data/app.db` is missing. Recreate the volume and replace the server:
  `terraform apply -replace=aws_ebs_volume.data -replace=aws_instance.server`. First remove
  `prevent_destroy` from the `aws_ebs_volume.data` resource.
- **Back to a point in time** on the server:
  ```bash
  cd /opt/scoreboard && sudo docker compose stop app litestream
  sudo mv /data/app.db /data/app.db.bad && sudo rm -f /data/app.db-wal /data/app.db-shm
  sudo docker compose run --rm restore restore -config /etc/litestream.yml \
    -timestamp 2026-11-01T10:00:00Z /data/app.db
  sudo docker compose up -d
  ```

Run a restore once on purpose before the tournament, to know that it works.

## Cost (ap-south-1, approximate)

t4g.small about $12/month; disks (20 + 10 GB gp3) about $2.50; the Elastic IP about $3.60;
CloudFront, S3, ECR and CloudWatch about $1 at tournament traffic.
