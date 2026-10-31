output "bucket" {
  value = module.site.bucket
}

output "distribution_id" {
  value = module.site.distribution_id
}

output "distribution_domain" {
  value = module.site.distribution_domain
}

output "urls" {
  value = module.site.urls
}

output "status_bucket" {
  value = module.status_site.bucket
}

output "status_distribution_id" {
  value = module.status_site.distribution_id
}

output "status_url" {
  value = module.status_site.urls[0]
}
