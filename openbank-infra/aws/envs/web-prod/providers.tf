# Default provider — regional (eu-north-1), same account as the rest of the stack.
provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "openbank"
      ManagedBy   = "opentofu"
      Env         = "prod"
      Environment = "prod"
      Service     = "openbank"
      Component   = "web-landing"
    }
  }
}

# CloudFront's ACM cert must live in us-east-1, regardless of where we operate.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "openbank"
      ManagedBy   = "opentofu"
      Env         = "prod"
      Environment = "prod"
      Service     = "openbank"
      Component   = "web-landing"
    }
  }
}
