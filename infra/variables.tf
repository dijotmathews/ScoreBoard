variable "region" {
  description = "AWS region for the server, image registry and backups."
  type        = string
  default     = "ap-south-1"
}

variable "name" {
  description = "Prefix for resource names."
  type        = string
  default     = "scoreboard"
}

variable "domain_name" {
  description = "Public hostname. Its DNS (GoDaddy) gets the CNAME records from the outputs."
  type        = string
  default     = "scoreboard.enigma-tech.in"
}

variable "instance_type" {
  description = "An ARM (Graviton) type; the image is built for linux/arm64."
  type        = string
  default     = "t4g.small"
}

variable "data_volume_size" {
  description = "Size in GiB of the disk holding app.db."
  type        = number
  default     = 10
}

variable "github_repository" {
  description = "owner/name of the repository whose main branch may deploy."
  type        = string
  default     = "dijotmathews/ScoreBoard"
}

variable "create_github_oidc_provider" {
  description = "Set to false if the account already has the token.actions.githubusercontent.com provider."
  type        = bool
  default     = true
}

variable "backup_retention_days" {
  description = "Days to keep replaced backup files in S3."
  type        = number
  default     = 30
}

variable "alarm_email" {
  description = "Optional email for instance health alarms (confirm the subscription email)."
  type        = string
  default     = ""
}

variable "allow_destroy" {
  description = "Lets terraform destroy delete the images in ECR and the backups in S3. Set by destroy.sh."
  type        = bool
  default     = false
}

variable "data_snapshot_id" {
  description = "Create the data volume from this snapshot (e.g. the one destroy.sh leaves) instead of empty. Changing it replaces an existing volume."
  type        = string
  default     = ""
}
