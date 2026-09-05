use crate::parser::parse_proxy_uri;
use std::fmt::Write;

pub const MAX_BATCH_ITEMS: usize = 10_000;
pub const MAX_PAYLOAD_BYTES: usize = 10 * 1024 * 1024; // 10 MB

/// Decodes length-prefixed items strictly from a buffer.
/// Each item is formatted as `{utf16_len}:{content}`.
/// Strict requirements:
/// - utf16_len must consist strictly of ASCII digits [0-9] (no sign, no spaces).
/// - utf16_len must not overflow usize.
/// - content must have exact utf16_len UTF-16 code units.
/// - entire buffer must be consumed with zero trailing garbage.
pub fn decode_length_prefixed_items_strict(buffer: &str) -> Result<Vec<String>, &'static str> {
    if buffer.is_empty() {
        return Ok(Vec::new());
    }

    let mut items = Vec::new();
    let mut idx = 0;

    while idx < buffer.len() {
        if items.len() >= MAX_BATCH_ITEMS {
            return Err("Exceeded maximum item count limit");
        }

        // Find colon
        let colon = match buffer[idx..].find(':') {
            Some(p) => idx + p,
            None => return Err("Missing colon delimiter in length prefix"),
        };

        if colon == idx {
            return Err("Empty length prefix before colon");
        }

        let len_slice = &buffer[idx..colon];
        if !len_slice.bytes().all(|c| c.is_ascii_digit()) {
            return Err("Length prefix contains non-digit characters");
        }

        let utf16_len = match len_slice.parse::<usize>() {
            Ok(l) => l,
            Err(_) => return Err("Length prefix overflowed usize"),
        };

        // Scan the original UTF-8 allocation; only the returned items are copied.
        // Consume UTF-16 units without ever splitting a supplementary character.
        let start = colon + 1;
        let mut remaining = utf16_len;
        let mut end = start;
        for c in buffer[start..].chars() {
            if remaining == 0 {
                break;
            }
            remaining = remaining
                .checked_sub(c.len_utf16())
                .ok_or("Item truncated or invalid UTF-16 code unit boundary")?;
            end += c.len_utf8();
        }

        if remaining != 0 {
            return Err("Item truncated or invalid UTF-16 code unit boundary");
        }

        items.push(buffer[start..end].to_owned());
        idx = end;
    }

    Ok(items)
}

/// Decodes length-prefixed items from a buffer (permissive fallback for non-critical paths).
pub fn decode_length_prefixed_items(buffer: &str) -> Vec<String> {
    decode_length_prefixed_items_strict(buffer).unwrap_or_default()
}

/// Encodes items into length-prefixed items buffer:
/// `{utf16_len}:{item}` for each item.
pub fn encode_length_prefixed_items(items: &[&str]) -> String {
    let mut buf = String::with_capacity(items.iter().map(|s| s.len() + 8).sum());
    for item in items {
        let _ = write!(buf, "{}:{}", item.encode_utf16().count(), item);
    }
    buf
}

pub struct BatchParser;

impl BatchParser {
    /// Parses a batch of proxy URIs provided as a raw framed payload.
    /// Input framing:
    /// `{count}\n` header, followed by `{len}:{uri}` entries.
    /// Output framing:
    /// `{count}\n` header, followed by `{len}:{serialized_canonical_result}` entries.
    /// If input is malformed, returns `ERROR|INTERNAL_ERROR|{reason}\n` or `ERROR|INVALID_INPUT|{reason}\n`.
    pub fn parse_batch_raw(payload: &str) -> String {
        if payload.len() > MAX_PAYLOAD_BYTES {
            return "ERROR|INVALID_INPUT|Payload exceeds maximum 10MB limit\n".to_string();
        }

        if payload.is_empty() {
            return "0\n".to_string();
        }

        let (header, body) = match payload.split_once('\n') {
            Some((h, b)) => (h, b),
            None => {
                return "ERROR|INTERNAL_ERROR|Missing header newline in batch frame\n".to_string();
            }
        };

        if header.is_empty() || !header.chars().all(|c| c.is_ascii_digit()) {
            return "ERROR|INTERNAL_ERROR|Malformed batch header count\n".to_string();
        }

        let expected_count: usize = match header.parse() {
            Ok(c) => c,
            Err(_) => return "ERROR|INTERNAL_ERROR|Batch count overflow\n".to_string(),
        };

        if expected_count > MAX_BATCH_ITEMS {
            return "ERROR|INVALID_INPUT|Batch item count exceeds limit\n".to_string();
        }

        if expected_count == 0 && body.is_empty() {
            return "0\n".to_string();
        }

        let uris = match decode_length_prefixed_items_strict(body) {
            Ok(u) => u,
            Err(err) => return format!("ERROR|INTERNAL_ERROR|{}\n", err),
        };

        if uris.len() != expected_count {
            return format!(
                "ERROR|INTERNAL_ERROR|Count mismatch: header says {} but frame contained {}\n",
                expected_count,
                uris.len()
            );
        }

        let mut results = Vec::with_capacity(uris.len());
        for uri in &uris {
            let result_str = parse_proxy_uri(uri);
            results.push(result_str);
        }

        let mut out =
            String::with_capacity(results.iter().map(|r| r.len() + 12).sum::<usize>() + 16);
        let _ = writeln!(out, "{}", results.len());
        for r in results {
            let _ = write!(out, "{}:{}", r.encode_utf16().count(), r);
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strict_decoder_preserves_unicode_empty_and_prefix_boundaries() {
        let items = ["", "节点", "🔥", "a🔥é\n:0", "", "tail"];
        let encoded = encode_length_prefixed_items(&items);
        assert_eq!(
            decode_length_prefixed_items_strict(&encoded).unwrap(),
            items
        );
        assert_eq!(
            decode_length_prefixed_items_strict("0002:🔥0:").unwrap(),
            ["🔥", ""]
        );
        for malformed in ["1:🔥", "3:a🔥1:🔥", "4:节点", "2:é"] {
            assert_eq!(
                decode_length_prefixed_items_strict(malformed),
                Err("Item truncated or invalid UTF-16 code unit boundary")
            );
        }
        assert_eq!(
            decode_length_prefixed_items_strict("节点:x"),
            Err("Length prefix contains non-digit characters")
        );
        assert_eq!(
            decode_length_prefixed_items_strict("999999999999999999999999999999:"),
            Err("Length prefix overflowed usize")
        );
    }

    #[test]
    fn strict_decoder_accepts_exact_item_limit_and_rejects_next_item() {
        let mut frame = "0:".repeat(MAX_BATCH_ITEMS);
        assert_eq!(
            decode_length_prefixed_items_strict(&frame).unwrap().len(),
            MAX_BATCH_ITEMS
        );
        frame.push_str("0:");
        assert_eq!(
            decode_length_prefixed_items_strict(&frame),
            Err("Exceeded maximum item count limit")
        );
    }

    #[test]
    fn strict_decoder_handles_large_ascii_item() {
        let item = "a".repeat(MAX_PAYLOAD_BYTES - 16);
        let frame = encode_length_prefixed_items(&[&item]);
        assert_eq!(decode_length_prefixed_items_strict(&frame).unwrap(), [item]);
    }

    #[test]
    fn test_batch_empty() {
        let res = BatchParser::parse_batch_raw("");
        assert_eq!(res, "0\n");

        let res0 = BatchParser::parse_batch_raw("0\n");
        assert_eq!(res0, "0\n");
    }

    #[test]
    fn test_batch_roundtrip_mixed() {
        let uris = [
            "ss://chacha20-ietf-poly1305:secret@192.168.1.1:8388#Node1",
            "socks5://user:pass@10.0.0.1:1080#SocksNode",
            "invalid://scheme:123",
            "ss://aes-128-gcm:pass@127.0.0.1:99999",
        ];

        let framed_input = format!("4\n{}", encode_length_prefixed_items(&uris));
        let raw_output = BatchParser::parse_batch_raw(&framed_input);

        assert!(raw_output.starts_with("4\n"));
        let body = &raw_output[raw_output.find('\n').unwrap() + 1..];
        let items = decode_length_prefixed_items_strict(body).unwrap();

        assert_eq!(items.len(), 4);
        assert!(items[0].starts_with("SUCCESS\n"));
        assert!(items[0].contains("shadowsocks"));
        assert!(items[0].contains("Node1"));

        assert!(items[1].starts_with("SUCCESS\n"));
        assert!(items[1].contains("socks5"));
        assert!(items[1].contains("SocksNode"));

        assert!(items[2].starts_with("INVALID_SCHEME\n"));
        assert!(items[3].starts_with("INVALID_PORT\n"));
    }

    #[test]
    fn test_batch_unicode_and_delimiter_safety() {
        let uris = ["ss://chacha20-ietf-poly1305:p|a|s|s@127.0.0.1:8388#节点|🔥|Unicode"];
        let framed_input = format!("1\n{}", encode_length_prefixed_items(&uris));
        let raw_output = BatchParser::parse_batch_raw(&framed_input);

        let body = &raw_output[raw_output.find('\n').unwrap() + 1..];
        let items = decode_length_prefixed_items_strict(body).unwrap();
        assert_eq!(items.len(), 1);
        assert!(items[0].starts_with("SUCCESS\n"));
        assert!(items[0].contains("节点|🔥|Unicode"));
    }

    #[test]
    fn test_strict_decoding_rejects_trailing_garbage() {
        let valid = "5:hello";
        let with_garbage = "5:hello!";
        assert!(decode_length_prefixed_items_strict(valid).is_ok());
        assert_eq!(
            decode_length_prefixed_items_strict(with_garbage),
            Err("Missing colon delimiter in length prefix")
        );
    }

    #[test]
    fn test_strict_decoding_rejects_non_digit_length() {
        let bad_len = "+5:hello";
        assert_eq!(
            decode_length_prefixed_items_strict(bad_len),
            Err("Length prefix contains non-digit characters")
        );

        let letters_len = "5a:hello";
        assert_eq!(
            decode_length_prefixed_items_strict(letters_len),
            Err("Length prefix contains non-digit characters")
        );
    }

    #[test]
    fn test_strict_decoding_rejects_truncation() {
        let truncated = "10:hello";
        assert_eq!(
            decode_length_prefixed_items_strict(truncated),
            Err("Item truncated or invalid UTF-16 code unit boundary")
        );
    }

    #[test]
    fn test_batch_rejects_count_mismatch() {
        let uris = ["ss://chacha20-ietf-poly1305:secret@1.1.1.1:8388#Node1"];
        let framed = format!("2\n{}", encode_length_prefixed_items(&uris));
        let res = BatchParser::parse_batch_raw(&framed);
        assert!(res.starts_with("ERROR|INTERNAL_ERROR|Count mismatch"));
    }

    #[test]
    fn test_batch_rejects_oversized_payload() {
        let huge = "a".repeat(MAX_PAYLOAD_BYTES + 1);
        let res = BatchParser::parse_batch_raw(&huge);
        assert!(res.starts_with("ERROR|INVALID_INPUT|Payload exceeds maximum"));
    }

    #[test]
    fn test_batch_raw_rejects_malformed_framing_in_body() {
        // Count says 1, but body has trailing characters or malformed length prefix
        let bad_framing = "1\n5:hello!";
        let res = BatchParser::parse_batch_raw(bad_framing);
        assert!(res.starts_with("ERROR|INTERNAL_ERROR|"));
        assert!(res.contains("colon delimiter"));
    }
}
