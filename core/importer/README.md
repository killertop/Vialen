# Standard profile imports

`Parse(Request) (Result, error)` is the single Go import path for new profiles.
It returns validated `profile.Profile` values for storage and compilation.

Formats: `auto`, `links`, `base64`, `clash`, `singbox`, `wireguard`.

- Links use one URI per non-comment line. Base64 accepts standard or URL-safe
  encoding with optional padding and ASCII whitespace for folded subscriptions.
- Clash uses standard typed scalar values and explicit per-protocol mappings.
  YAML merges are supported; duplicate keys, cycles, unsupported tags and
  excessive expansion are rejected. Strings are not coerced to ports or booleans.
- sing-box imports nodes from `outbounds` / `endpoints`, or an array of nodes.
  Routing/selection entries and dependent `detour` nodes produce visible issues.
  A complete config is not stored inside a profile.
- WireGuard parses one Interface and separate Peer sections. Each remote peer
  becomes a profile with shared interface keys/addresses. Optional MTU stays
  optional. DNS, hooks, routing-table and local-listener settings generate
  `INTERFACE_SETTING_OMITTED` issues; hooks are never executed.

Supported protocol families: SOCKS, HTTP, Shadowsocks, VMess, VLESS, Trojan,
Hysteria2, TUIC, AnyTLS and WireGuard. WS/HTTP/gRPC/HTTPUpgrade transports retain
supported headers and TLS options through the typed profile model. URI decoding
and profile validation are owned by `profile`.

Issues explicitly carry `severity: warning` or `severity: error`. Non-proxy
selection entries and omitted interface-only settings are warnings. Automatic
refresh should fail closed on `Result.HasErrors()`; warnings remain visible and
do not discard otherwise valid nodes. Unknown severities also fail closed.

An identified format's syntax failure returns an error; it does not retry an
unrelated parser. Individual invalid/unsupported entries appear in `Issues` while
other entries can succeed. **Callers must show issues before persisting a partial
batch.** Index is zero-based: source line for links, array entry for Clash,
concatenated outbound/endpoint entry for sing-box, or peer for WireGuard.
WireGuard interface-wide omissions use index -1.

Limits: 16 MiB input, 10,000 source entries/profiles, JSON/YAML nesting depth 64,
and YAML expansion limited to 1,000,000 visited nodes / 32 MiB scalar text.
Errors do not echo source payloads or credential values.

Tests exercise format interoperability, typed validation, partial failures,
Unicode preservation, duplicate-key rejection, document isolation, resource
limits and fuzzed inputs.
