# openbao-config-admin — what `tofu apply` in envs/sandbox-openbao needs, and nothing more.
#
# It manages ACL policies and the auth roles declared in this stack. It cannot read or write
# any secret (no openbank/* path), cannot unseal, rekey or generate a root token, and cannot
# enable arbitrary auth methods — only the `aws` mount this stack owns.

path "sys/policies/acl" {
  capabilities = ["list"]
}

path "sys/policies/acl/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

path "sys/auth" {
  capabilities = ["read"]
}

path "sys/auth/aws" {
  capabilities = ["create", "read", "update", "sudo"]
}

path "sys/mounts/auth/aws/tune" {
  capabilities = ["read", "update"]
}

path "auth/aws/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

path "auth/oidc/role/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}
