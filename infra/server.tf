# One EC2 instance runs the app and Litestream with Docker Compose. SQLite allows a single
# writer, so there is exactly one server; app.db lives on its own EBS volume.

data "aws_vpc" "default" {
  default = true
}

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# Only CloudFront reaches the app, so nobody can skip HTTPS. There is no SSH; use Session Manager.
resource "aws_security_group" "app" {
  name        = "${var.name}-app"
  description = "HTTP from CloudFront only"
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_vpc_security_group_ingress_rule" "http_from_cloudfront" {
  security_group_id = aws_security_group.app.id
  description       = "HTTP from CloudFront"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  description       = "Image pulls, S3 backups, SSM"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_iam_role" "server" {
  name = "${var.name}-server"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.server.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "server" {
  name = "${var.name}-server"
  role = aws_iam_role.server.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "PullImages"
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
        Resource = aws_ecr_repository.app.arn
      },
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid      = "Backups"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.backups.arn}/*"
      },
      {
        Sid      = "ListBackups"
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = aws_s3_bucket.backups.arn
      },
      {
        Sid      = "AdminPassword"
        Effect   = "Allow"
        Action   = "ssm:GetParameter"
        Resource = aws_ssm_parameter.admin_password.arn
      },
      {
        Sid      = "Logs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.app.arn}:*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "server" {
  name = "${var.name}-server"
  role = aws_iam_role.server.name
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/${var.name}/app"
  retention_in_days = 30
}

resource "aws_instance" "server" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnet.server.id
  iam_instance_profile   = aws_iam_instance_profile.server.name
  vpc_security_group_ids = [aws_security_group.app.id]

  # Containers reach the instance role through IMDSv2, which needs a hop limit of 2.
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 20
    encrypted   = true
  }

  user_data = templatefile("${path.module}/templates/user-data.sh.tftpl", {
    region          = var.region
    data_volume_id  = aws_ebs_volume.data.id
    registry        = split("/", aws_ecr_repository.app.repository_url)[0]
    image           = aws_ecr_repository.app.repository_url
    admin_parameter = aws_ssm_parameter.admin_password.name
    log_group       = aws_cloudwatch_log_group.app.name
    compose_file    = file("${path.module}/templates/compose.yml")
    deploy_script   = file("${path.module}/templates/deploy.sh")
    litestream_yml = templatefile("${path.module}/templates/litestream.yml.tftpl", {
      region        = var.region
      backup_bucket = aws_s3_bucket.backups.bucket
    })
  })
  user_data_replace_on_change = true

  # A newer Amazon Linux image should not replace the server by itself.
  lifecycle {
    ignore_changes = [ami]
  }

  tags = {
    Name = var.name
  }
}

# The database disk outlives the instance: replacing the server reattaches it. Deleting it
# (terraform destroy, see destroy.sh) leaves a final snapshot holding app.db.
resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.server.availability_zone
  size              = var.data_volume_size
  type              = "gp3"
  encrypted         = true

  final_snapshot = true
  snapshot_id    = var.data_snapshot_id == "" ? null : var.data_snapshot_id

  tags = {
    Name = "${var.name}-data"
  }
}

# The instance and its data volume must share an availability zone, so pin the subnet.
data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

data "aws_subnet" "server" {
  id = sort(data.aws_subnets.default.ids)[0]
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.server.id

  # Stop the server first, so SQLite is never detached mid-write.
  stop_instance_before_detaching = true
}

# A fixed address, so the CloudFront origin name stays the same.
resource "aws_eip" "server" {
  domain   = "vpc"
  instance = aws_instance.server.id

  tags = {
    Name = var.name
  }
}
