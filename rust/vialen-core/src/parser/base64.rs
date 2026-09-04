pub fn decode_base64_url_safe(input: &str) -> Result<Vec<u8>, &'static str> {
    let mut clean = Vec::with_capacity(input.len());
    for &b in input.as_bytes() {
        if b.is_ascii_whitespace() {
            continue;
        }
        clean.push(b);
    }

    if clean.is_empty() || clean.iter().all(|&b| b == b'=') {
        return Ok(Vec::new());
    }

    let eq_count = clean.iter().filter(|&&b| b == b'=').count();
    if eq_count > 2 {
        return Err("invalid base64 padding");
    }

    let unpadded_len = clean.iter().take_while(|&&b| b != b'=').count();
    if unpadded_len % 4 == 1 {
        return Err("invalid base64 length");
    }

    // Decode 4 characters into 3 bytes
    let mut out = Vec::with_capacity(clean.len() * 3 / 4);
    let mut buf = 0u32;
    let mut bits = 0;

    for &b in &clean {
        if b == b'=' {
            break;
        }
        let val = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            _ => return Err("invalid base64 character"),
        };
        buf = (buf << 6) | (val as u32);
        bits += 6;

        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
            buf &= (1 << bits) - 1;
        }
    }

    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_edge_cases() {
        assert!(decode_base64_url_safe("A").is_err());
        assert!(decode_base64_url_safe("A===").is_err());
        assert_eq!(decode_base64_url_safe("AA").unwrap(), vec![0]);
        assert_eq!(decode_base64_url_safe("AAA").unwrap(), vec![0, 0]);
        assert_eq!(decode_base64_url_safe("====").unwrap(), Vec::<u8>::new());
        assert_eq!(decode_base64_url_safe("aGVsbG8=").unwrap(), b"hello");
        assert_eq!(decode_base64_url_safe("aGVsbG8").unwrap(), b"hello");
        assert_eq!(decode_base64_url_safe("aGVsbG8==").unwrap(), b"hello");
    }

    #[test]
    fn test_url_safe_base64() {
        assert_eq!(decode_base64_url_safe("PD4_Pz8-").unwrap(), b"<>???>");
    }

    #[test]
    fn test_whitespace_tolerance() {
        assert_eq!(
            decode_base64_url_safe("  aGVs\nbG8= \r\n").unwrap(),
            b"hello"
        );
    }
}
