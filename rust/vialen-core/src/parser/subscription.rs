use super::base64::decode_base64_url_safe;

pub const MAX_SUBSCRIPTION_BYTES: usize = 1024 * 1024;
pub const MAX_SUBSCRIPTION_LINES: usize = 10_000;

pub fn decode_subscription_lines(input: &str) -> Result<Vec<String>, &'static str> {
    if input.len() > MAX_SUBSCRIPTION_BYTES {
        return Err("input exceeds maximum size");
    }

    // Attempt base64 decoding
    let text = match decode_base64_url_safe(input) {
        Ok(bytes) if !bytes.is_empty() => {
            String::from_utf8(bytes).unwrap_or_else(|_| input.to_string())
        }
        _ => input.to_string(),
    };

    let mut lines = Vec::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if !trimmed.is_empty() {
            // Also split whitespace within a line if multiple URIs exist on one line
            for token in trimmed.split_whitespace() {
                if !token.is_empty() {
                    lines.push(token.to_string());
                    if lines.len() > MAX_SUBSCRIPTION_LINES {
                        return Err("exceeded maximum subscription lines");
                    }
                }
            }
        }
    }

    Ok(lines)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_plain_lines() {
        let text = "ss://node1\n\n  ss://node2  \n";
        let lines = decode_subscription_lines(text).unwrap();
        assert_eq!(lines, vec!["ss://node1", "ss://node2"]);
    }

    #[test]
    fn test_base64_encoded_subscription() {
        // "ss://node1\nss://node2\n" in base64: c3M6Ly9ub2RlMQpzczovL25vZGUyCg==
        let b64 = "c3M6Ly9ub2RlMQpzczovL25vZGUyCg==";
        let lines = decode_subscription_lines(b64).unwrap();
        assert_eq!(lines, vec!["ss://node1", "ss://node2"]);
    }
}
