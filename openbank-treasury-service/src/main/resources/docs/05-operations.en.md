# 05 — Operations

## camt.053 XML intake

Treasury parses untrusted camt.053 uploads through `SecureXml`. Its DOM builder rejects DOCTYPE declarations and external entities before constructing a document. A rejected or malformed upload must be corrected at its source; do not weaken parser settings to accept it. The service does not make an outbound fetch for entities named by the file.
