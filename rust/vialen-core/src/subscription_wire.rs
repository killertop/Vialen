//! Versioned binary subscription boundary, selected only for Universal payloads.
//! JSON scalars retain exact text semantics; strings are unescaped UTF-8.
use serde_json::Value;

pub fn encode(value: &Value) -> Vec<u8> {
    let mut output = b"VCW1".to_vec();
    write(value, &mut output);
    output
}

fn length(size: usize, output: &mut Vec<u8>) {
    output.extend_from_slice(&u32::try_from(size).expect("wire length overflow").to_le_bytes());
}

fn bytes(value: &[u8], output: &mut Vec<u8>) {
    length(value.len(), output);
    output.extend_from_slice(value);
}

fn write(value: &Value, output: &mut Vec<u8>) {
    match value {
        Value::Null => output.push(0),
        Value::Bool(false) => output.push(1),
        Value::Bool(true) => output.push(2),
        Value::Number(n) => { output.push(3); bytes(n.to_string().as_bytes(), output); }
        Value::String(s) => { output.push(4); bytes(s.as_bytes(), output); }
        Value::Array(items) => {
            if !items.is_empty() && items.iter().all(|v| v.as_u64().is_some_and(|n| n <= 255)) {
                output.push(7); length(items.len(), output);
                output.extend(items.iter().map(|v| v.as_u64().unwrap() as u8));
                return;
            }
            output.push(5); length(items.len(), output);
            for item in items { write(item, output); }
        }
        Value::Object(fields) => {
            output.push(6); length(fields.len(), output);
            for (key, value) in fields { bytes(key.as_bytes(), output); write(value, output); }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stable_scalar_and_collection_contract() {
        assert_eq!(encode(&Value::Null), b"VCW1\0");
        assert_eq!(encode(&serde_json::json!([0, 255, 1])), b"VCW1\x07\x03\0\0\0\0\xff\x01");
        assert_eq!(encode(&serde_json::json!([false, true, "é"])),
            [b"VCW1\x05\x03\0\0\0\x01\x02\x04\x02\0\0\0".as_slice(), "é".as_bytes()].concat());
        assert_eq!(encode(&serde_json::json!({"n": -12})), b"VCW1\x06\x01\0\0\0\x01\0\0\0n\x03\x03\0\0\0-12");
    }
}
