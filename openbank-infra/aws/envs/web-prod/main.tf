# The hosted zone is owned by the substrate stack (modules/dns). We only read it.
data "aws_route53_zone" "public" {
  name         = var.domain
  private_zone = false
}

module "site" {
  source = "../../modules/static-site"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  domain      = var.domain
  aliases     = [var.domain, "www.${var.domain}"]
  zone_id     = data.aws_route53_zone.public.zone_id
  bucket_name = "openbank-web-landing-prod"

  tags = {
    Project     = "openbank"
    ManagedBy   = "opentofu"
    Environment = "prod"
    Component   = "static-site"
    Adr         = "0027"
  }
}
