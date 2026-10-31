output "dns_records" {
  description = "CNAME records to add at GoDaddy: first the validation record, then the site record."
  value = {
    certificate_validation = [for o in aws_acm_certificate.site.domain_validation_options : {
      type  = o.resource_record_type
      name  = o.resource_record_name
      value = o.resource_record_value
    }]
    site = {
      type  = "CNAME"
      name  = var.domain_name
      value = aws_cloudfront_distribution.site.domain_name
    }
  }
}

output "region" {
  value = var.region
}

output "url" {
  value = "https://${var.domain_name}"
}

output "cloudfront_url" {
  description = "The site's address before the GoDaddy site record exists."
  value       = "https://${aws_cloudfront_distribution.site.domain_name}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "instance_id" {
  value = aws_instance.server.id
}

output "github_deploy_role_arn" {
  description = "GitHub repository variable AWS_DEPLOY_ROLE_ARN."
  value       = aws_iam_role.github_deploy.arn
}

output "backup_bucket" {
  value = aws_s3_bucket.backups.bucket
}

output "admin_password_command" {
  description = "Prints the first admin's password."
  value       = "aws ssm get-parameter --region ${var.region} --name ${aws_ssm_parameter.admin_password.name} --with-decryption --query Parameter.Value --output text"
}
