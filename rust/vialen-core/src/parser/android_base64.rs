//! Android Base64 decoder semantics used by the legacy NGUtil format.
//! Non-alphabet bytes are skipped; padding is a state transition, not truncation.
fn decode(input: &str, url_safe: bool) -> Result<Vec<u8>, &'static str> {
    let mut out = Vec::new();
    let mut value = 0u32;
    let mut state = 0;
    for byte in input.bytes() {
        let digit = match byte {
            b'A'..=b'Z' => Some(byte - b'A'),
            b'a'..=b'z' => Some(byte - b'a' + 26),
            b'0'..=b'9' => Some(byte - b'0' + 52),
            b'+' if !url_safe => Some(62),
            b'/' if !url_safe => Some(63),
            b'-' if url_safe => Some(62),
            b'_' if url_safe => Some(63),
            _ => None,
        };
        if let Some(digit) = digit {
            if state >= 4 {
                return Err("invalid base64 padding");
            }
            value = (value << 6) | u32::from(digit);
            state += 1;
            if state == 4 {
                out.extend_from_slice(&[(value >> 16) as u8, (value >> 8) as u8, value as u8]);
                state = 0;
                value = 0;
            }
        } else if byte == b'=' {
            match state {
                2 => {
                    out.push((value >> 4) as u8);
                    state = 4;
                }
                3 => {
                    out.extend_from_slice(&[(value >> 10) as u8, (value >> 2) as u8]);
                    state = 5;
                }
                4 => state = 5,
                _ => return Err("invalid base64 padding"),
            }
        }
    }
    match state {
        1 | 4 => return Err("invalid base64 length"),
        2 => out.push((value >> 4) as u8),
        3 => out.extend_from_slice(&[(value >> 10) as u8, (value >> 2) as u8]),
        _ => {}
    }
    Ok(out)
}

pub fn ng_decode(input: &str) -> Result<Vec<u8>, &'static str> {
    decode(input, false)
        .or_else(|_| decode(input, true))
        .or_else(|error| {
            if input.ends_with('=') {
                let input = input.trim_end_matches('=');
                decode(input, false).or_else(|_| decode(input, true))
            } else {
                Err(error)
            }
        })
}

pub fn util_decode(input: &str) -> Result<Vec<u8>, &'static str> {
    decode(&input.replace('-', "+").replace('_', "/"), false)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn padding_and_skip_semantics_are_not_truncation() {
        assert_eq!(ng_decode("aGV!sbG8====").unwrap(), b"hello");
        assert!(util_decode("aGVsbG8====").is_err());
        assert!(ng_decode("aGVsbG8=AAAA").is_err());
        assert!(util_decode("A").is_err());
        assert_eq!(util_decode("aG Vs\nbG8=").unwrap(), b"hello");
    }
}
