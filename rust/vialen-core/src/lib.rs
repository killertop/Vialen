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

pub mod config;
pub mod engine;
pub mod full_config;
pub mod model;
pub mod parser;
pub mod raw_subscription;
mod subscription_wire;

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeParseRawSubscriptionOptimized(
    env: JNIEnv, _class: JClass, input: JByteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> jni::errors::Result<_> {
            let input = env.convert_byte_array(&input)?;
            Ok(env.byte_array_from_slice(&raw_subscription::generate_optimized(&input))?.into_raw())
        })();
        result.unwrap_or(ptr::null_mut())
    })).unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeParseRawSubscription(
    env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> jni::errors::Result<_> {
            let input = env.convert_byte_array(&input)?;
            let output = raw_subscription::generate(&input);
            Ok(env.byte_array_from_slice(&output)?.into_raw())
        })();
        result.unwrap_or(ptr::null_mut())
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeGenerateConfig(
    env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> jni::errors::Result<_> {
            let input = env.convert_byte_array(&input)?;
            let output = full_config::generate(&input);
            Ok(env.byte_array_from_slice(&output)?.into_raw())
        })();
        result.unwrap_or(ptr::null_mut())
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeGenerateOutbound(
    env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> jni::errors::Result<_> {
            let input = env.convert_byte_array(&input)?;
            let output = config::generate(&input);
            Ok(env.byte_array_from_slice(&output)?.into_raw())
        })();
        result.unwrap_or(ptr::null_mut())
    }))
    .unwrap_or(ptr::null_mut())
}

fn read_utf16_matrix(
    env: &mut JNIEnv<'_>,
    input: &jni::objects::JObjectArray<'_>,
) -> jni::errors::Result<Vec<Vec<u16>>> {
    let count = env.get_array_length(input)?;
    (0..count)
        .map(|i| {
            let object = env.get_object_array_element(input, i)?;
            let array = env.auto_local(jni::objects::JCharArray::from(object));
            let mut units = vec![0; env.get_array_length(&*array)? as usize];
            env.get_char_array_region(&*array, 0, &mut units)?;
            Ok(units)
        })
        .collect()
}

fn read_byte_matrix(
    env: &mut JNIEnv<'_>,
    input: &jni::objects::JObjectArray<'_>,
) -> jni::errors::Result<Vec<Vec<u8>>> {
    let count = env.get_array_length(input)?;
    (0..count)
        .map(|i| {
            let object = env.get_object_array_element(input, i)?;
            let array = env.auto_local(jni::objects::JByteArray::from(object));
            env.convert_byte_array(&*array)
        })
        .collect()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativePlanSubscription<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    old_names: jni::objects::JObjectArray<'local>,
    old_content: jni::objects::JObjectArray<'local>,
    old_orders: jni::objects::JLongArray<'local>,
    new_names: jni::objects::JObjectArray<'local>,
    new_content: jni::objects::JObjectArray<'local>,
) -> jni::sys::jintArray {
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> jni::errors::Result<_> {
            let old_names = read_utf16_matrix(&mut env, &old_names)?;
            let old_content = read_byte_matrix(&mut env, &old_content)?;
            let mut orders = vec![0; env.get_array_length(&old_orders)? as usize];
            env.get_long_array_region(&old_orders, 0, &mut orders)?;
            let new_names = read_utf16_matrix(&mut env, &new_names)?;
            let new_content = read_byte_matrix(&mut env, &new_content)?;
            let Some(plan) = engine::persistence::plan(
                &old_names,
                &old_content,
                &orders,
                &new_names,
                &new_content,
            )
            .ok()
            .and_then(|p| p.encode().ok()) else {
                return Ok(ptr::null_mut());
            };
            let array = env.new_int_array(plan.len() as i32)?;
            env.set_int_array_region(&array, 0, &plan)?;
            Ok(array.into_raw())
        })();
        result.unwrap_or(ptr::null_mut())
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeParseProxy<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    uri: jni::objects::JString<'local>,
) -> jni::sys::jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let uri_str: String = match env.get_string(&uri) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let result = parser::parse_proxy_to_canonical(&uri_str);
        match env.new_string(result) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeRankDedupKeys<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    keys: jni::objects::JObjectArray<'local>,
) -> jni::sys::jintArray {
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> jni::errors::Result<_> {
            let count = env.get_array_length(&keys)?;
            let ranks = engine::dedup::DedupEngine::rank_keys((0..count).map(|index| {
                let object = env.get_object_array_element(&keys, index)?;
                let array = env.auto_local(jni::objects::JCharArray::from(object));
                let mut units = vec![0; env.get_array_length(&*array)? as usize];
                env.get_char_array_region(&*array, 0, &mut units)?;
                Ok::<_, jni::errors::Error>(units)
            }))?;
            let result = env.new_int_array(count)?;
            env.set_int_array_region(&result, 0, &ranks)?;
            Ok(result.into_raw())
        })();
        result.unwrap_or(ptr::null_mut())
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeParseProxyBatch<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: jni::objects::JString<'local>,
) -> jni::sys::jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let payload_str: String = match env.get_string(&payload) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let result = engine::batch::BatchParser::parse_batch_raw(&payload_str);
        match env.new_string(result) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeDecodeSubscription<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: jni::objects::JString<'local>,
) -> jni::sys::jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let text_str: String = match env.get_string(&text) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let lines = match parser::decode_subscription_lines(&text_str) {
            Ok(l) => l.join("\n"),
            Err(e) => format!("ERROR|{}", e),
        };
        match env.new_string(lines) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeDiffSubscription<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    old_payload: jni::objects::JString<'local>,
    new_payload: jni::objects::JString<'local>,
) -> jni::sys::jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let old_str: String = match env.get_string(&old_payload) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let new_str: String = match env.get_string(&new_payload) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let result = engine::diff::SubscriptionDiffEngine::diff_raw(&old_str, &new_str);
        match env.new_string(result) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeDiffSubscriptionPipeline<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    old_payload: jni::objects::JString<'local>,
    new_payload: jni::objects::JString<'local>,
    deduplicate: jni::sys::jboolean,
) -> jni::sys::jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let old_str: String = match env.get_string(&old_payload) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let new_str: String = match env.get_string(&new_payload) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let result = engine::diff::SubscriptionDiffEngine::diff_raw_pipeline(
            &old_str,
            &new_str,
            deduplicate != 0,
        );
        match env.new_string(result) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeDisambiguateNames<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: jni::objects::JString<'local>,
) -> jni::sys::jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let payload_str: String = match env.get_string(&payload) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let result = engine::dedup::DedupEngine::disambiguate_names_raw(&payload_str);
        match env.new_string(result) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeDedupByEndpoint<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: jni::objects::JString<'local>,
) -> jni::sys::jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let payload_str: String = match env.get_string(&payload) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let result = engine::dedup::DedupEngine::dedup_by_endpoint_raw(&payload_str);
        match env.new_string(result) {
            Ok(js) => js.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_nekohasekai_sagernet_rust_RustNative_nativeGetDisplayName<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    protocol: jni::objects::JString<'local>,
    server: jni::objects::JString<'local>,
    port: jni::sys::jint,
    name: jni::objects::JString<'local>,
) -> jni::sys::jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let proto_str: String = match env.get_string(&protocol) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let server_str: String = match env.get_string(&server) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let name_str: String = match env.get_string(&name) {
            Ok(js) => js.into(),
            Err(_) => return ptr::null_mut(),
        };
        let node = model::node::CanonicalNode::new(
            &proto_str,
            &server_str,
            port as u16,
            "",
            "",
            "",
            &name_str,
        );
        match env.new_string(node.display_name()) {
            Ok(js) => js.into_raw(),
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
