use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;

const CONTRACT_VERSION: u8 = 1;
pub const MAX_INPUT_BYTES: usize = 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VialenRustError {
    InvalidInput,
    InternalError,
}

pub fn ffi_probe(input: &[u8]) -> Result<Vec<u8>, VialenRustError> {
    if input.len() > MAX_INPUT_BYTES {
        return Err(VialenRustError::InvalidInput);
    }

    let checksum = fnv1a64(input);
    Ok(format!("SUCCESS|{CONTRACT_VERSION}|{}|{checksum:016x}", input.len()).into_bytes())
}

fn fnv1a64(input: &[u8]) -> u64 {
    let mut hash = 0xcbf29ce484222325_u64;
    for byte in input {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

fn guarded<T, F>(operation: F) -> Result<T, VialenRustError>
where
    F: FnOnce() -> Result<T, VialenRustError>,
{
    catch_unwind(AssertUnwindSafe(operation)).unwrap_or(Err(VialenRustError::InternalError))
}

fn encode_error(error: VialenRustError, input_len: usize) -> Vec<u8> {
    let status = match error {
        VialenRustError::InvalidInput => "INVALID_INPUT",
        VialenRustError::InternalError => "INTERNAL_ERROR",
    };
    format!("{status}|{CONTRACT_VERSION}|{input_len}|-").into_bytes()
}

fn native_probe_impl(env: &JNIEnv<'_>, input: &JByteArray<'_>) -> Vec<u8> {
    let owned_input = match env.convert_byte_array(input) {
        Ok(bytes) => bytes,
        Err(_) => return encode_error(VialenRustError::InvalidInput, 0),
    };

    match guarded(|| ffi_probe(&owned_input)) {
        Ok(output) => output,
        Err(error) => encode_error(error, owned_input.len()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeProbe<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JByteArray<'local>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let output = match catch_unwind(AssertUnwindSafe(|| native_probe_impl(&env, &input))) {
            Ok(output) => output,
            Err(_) => encode_error(VialenRustError::InternalError, 0),
        };

        match env.byte_array_from_slice(&output) {
            Ok(array) => array.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normal_input_is_deterministic() {
        let first = ffi_probe(b"vialen").expect("normal input should succeed");
        let second = ffi_probe(b"vialen").expect("normal input should succeed");
        assert_eq!(first, second);
        assert_eq!(
            String::from_utf8(first).unwrap(),
            "SUCCESS|1|6|9269a2a30227b142"
        );
    }

    #[test]
    fn empty_input_is_supported() {
        assert_eq!(
            String::from_utf8(ffi_probe(&[]).unwrap()).unwrap(),
            "SUCCESS|1|0|cbf29ce484222325"
        );
    }

    #[test]
    fn binary_non_utf8_input_is_supported() {
        let output = String::from_utf8(ffi_probe(&[0x00, 0xff, 0x80]).unwrap()).unwrap();
        assert!(output.starts_with("SUCCESS|1|3|"));
    }

    #[test]
    fn large_reasonable_input_is_supported() {
        let input = vec![0x5a; 256 * 1024];
        let output = String::from_utf8(ffi_probe(&input).unwrap()).unwrap();
        assert!(output.starts_with("SUCCESS|1|262144|"));
    }

    #[test]
    fn oversized_input_is_rejected() {
        let input = vec![0; MAX_INPUT_BYTES + 1];
        assert_eq!(ffi_probe(&input), Err(VialenRustError::InvalidInput));
    }

    #[test]
    fn panic_is_mapped_to_internal_error() {
        let result: Result<Vec<u8>, VialenRustError> = guarded(|| panic!("synthetic panic"));
        assert_eq!(result, Err(VialenRustError::InternalError));
    }
}
