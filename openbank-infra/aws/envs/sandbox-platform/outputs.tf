output "cluster_name" {
  value = local.cluster_name
}

# ArgoCD bootstrap admin password is stored in a k8s secret, never in tofu
# state. Retrieve it with:
#   kubectl -n argocd get secret argocd-initial-admin-secret \
#     -o jsonpath='{.data.password}' | base64 -d
output "argocd_admin_password_hint" {
  value = "kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"
}
