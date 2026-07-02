terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # CloudFront + ACM (us-east-1) need a second, aliased AWS provider.
      # The root passes both the default (regional) and us_east_1 aliases.
      version               = "~> 6.53"
      configuration_aliases = [aws.us_east_1]
    }
  }
}
