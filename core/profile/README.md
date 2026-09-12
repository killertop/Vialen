# Typed profiles

`Profile` is the shared domain model for import, storage and compilation. Exactly
one protocol options object must match `type`. TLS, transport, UDP-over-TCP and
multiplex settings are explicit structures validated for their protocol.

`ParseURI` accepts interoperable share links and returns a validated profile.
`ExportURI` emits standard links where every connection option is representable;
otherwise it returns a field-specific error and callers offer Profile JSON.
WireGuard and ShadowTLS use structured import and JSON export.

`Validate` checks addresses, ports, credentials, protocol options and advanced
settings without network or Android calls. Protocol fixtures, round-trip tests,
resource checks and fuzz tests run independently of the mobile runtime.
