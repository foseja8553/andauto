//! Standard OBD-II mode 01 response parsing and telemetry conversion.

/// Parse a response without guessing a value from an unsupported or incomplete PID.
/// Returns km/h, RPM, or integer percent, depending on `pid`.
pub fn decode(response: &str, pid: u8) -> Option<i32> {
    let digits: String = response
        .chars()
        .filter(|c| c.is_ascii_hexdigit())
        .map(|c| c.to_ascii_uppercase())
        .collect();
    let marker = format!("41{pid:02X}");
    let payload = digits.split_once(&marker)?.1;
    let size = if pid == 0x0c { 4 } else { 2 };
    let value = i32::from_str_radix(payload.get(..size)?, 16).ok()?;
    match pid {
        0x0c => Some(value / 4),
        0x0d => Some(value),
        0x11 | 0x49 => Some(value * 100 / 255),
        _ => None,
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_torquescope_MainActivity_decodeObd(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    response: jni::objects::JString,
    pid: jni::sys::jint,
) -> jni::sys::jint {
    let response: String = match env.get_string(&response) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    u8::try_from(pid)
        .ok()
        .and_then(|p| decode(&response, p))
        .unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::decode;
    #[test]
    fn decodes_standard_values_and_echo() {
        assert_eq!(decode("010C\r41 0C 1A F8\r>", 0x0c), Some(1726));
        assert_eq!(decode("41 0D 64", 0x0d), Some(100));
        assert_eq!(decode("41 49 FF", 0x49), Some(100));
        assert_eq!(decode("41 11 80", 0x11), Some(50));
    }
    #[test]
    fn rejects_absent_incomplete_and_unknown() {
        assert_eq!(decode("NO DATA", 0x49), None);
        assert_eq!(decode("41 0C 1A", 0x0c), None);
        assert_eq!(decode("41 49 7F", 0x11), None);
        assert_eq!(decode("41 FF 7F", 0xff), None);
    }
}
