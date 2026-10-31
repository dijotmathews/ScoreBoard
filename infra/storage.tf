# Image registry, backup bucket and the first admin's password.

resource "aws_ecr_repository" "app" {
  name                 = var.name
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.allow_destroy

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

# Litestream streams app.db here continuously.
resource "aws_s3_bucket" "backups" {
  bucket_prefix = "${var.name}-backups-"
  force_destroy = var.allow_destroy
}

resource "aws_s3_bucket_public_access_block" "backups" {
  bucket                  = aws_s3_bucket.backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "backups" {
  bucket = aws_s3_bucket.backups.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    id     = "expire-replaced-files"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.backup_retention_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# Used once, when the app starts on an empty database (AdminBootstrap).
resource "random_password" "admin" {
  length           = 20
  override_special = "!@#%^*-_=+"
}

resource "aws_ssm_parameter" "admin_password" {
  name        = "/${var.name}/bootstrap-admin-password"
  description = "Password of the first admin account"
  type        = "SecureString"
  value       = random_password.admin.result
}
