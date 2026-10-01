# openbao-config-admin — what `tofu apply` in envs/sandbox-openbao needs, and nothing more.
#
# It manages ACL policies and the OIDC roles declared in this stack. It cannot read or write
# any secret (no openbank/* path), cannot unseal, rekey or generate a root token, and cannot
# enable, disable or tune any auth method.

path "sys/policies/acl" {
  capabilities = ["list"]
}

path "sys/policies/acl/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

path "auth/oidc/role/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}
