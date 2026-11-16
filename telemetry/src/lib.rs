//! Standard OBD-II mode 01 response parsing and telemetry conversion.

/// Parse a response without guessing a value from an unsupported or incomplete PID.
/// Returns km/h, RPM, or integer percent, depending on `pid`.
pub fn decode(response: &str, pid: u8) -> Option<i32> {
    if !matches!(pid, 0x0c | 0x0d | 0x11 | 0x49) {
        return None;
    }
    // ATH0/ATS0 responses: parse each complete line independently. Do not join
    // truncated frames or strip arbitrary text into plausible hexadecimal bytes.
    for line in response.split(['\r', '\n', '>']) {
        let mut bytes = [0u8; 16];
        let mut count = 0;
        let mut high = None;
        let mut valid = true;
        for c in line.bytes().filter(|c| !c.is_ascii_whitespace()) {
            let nibble = match c {
                b'0'..=b'9' => c - b'0',
                b'a'..=b'f' => c - b'a' + 10,
                b'A'..=b'F' => c - b'A' + 10,
                _ => {
                    valid = false;
                    break;
                }
            };
            if let Some(h) = high.take() {
                if count == bytes.len() {
                    valid = false;
                    break;
                }
                bytes[count] = h * 16 + nibble;
                count += 1;
            } else {
                high = Some(nibble);
            }
        }
        let size = if pid == 0x0c { 4 } else { 3 };
        if !valid || high.is_some() || count < size || bytes[0] != 0x41 || bytes[1] != pid {
            continue;
        }
        let value = i32::from(bytes[2]);
        return Some(match pid {
            0x0c => (value * 256 + i32::from(bytes[3])) / 4,
            0x0d => value,
            _ => value * 100 / 255,
        });
    }
    None
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
    fn never_combines_frames_or_accepts_garbage() {
        for bad in [
            "410C1A\rF8",
            "x410D64",
            "0410D64",
            "410D6",
            "410D64garbage",
            "410D6Z",
        ] {
            assert_eq!(decode(bad, 0x0d), None);
        }
        assert_eq!(decode("410C1A\rF8", 0x0c), None);
        assert_eq!(decode("SEARCHING...\r410D64\r>", 0x0d), Some(100));
        assert_eq!(decode("410C1A\r410C1AF8", 0x0c), Some(1726));
        assert_eq!(decode("41 49 00", 0x49), Some(0));
    }
    #[test]
    fn rejects_absent_incomplete_and_unknown() {
        assert_eq!(decode("NO DATA", 0x49), None);
        assert_eq!(decode("41 0C 1A", 0x0c), None);
        assert_eq!(decode("41 49 7F", 0x11), None);
        assert_eq!(decode("41 FF 7F", 0xff), None);
    }
}
