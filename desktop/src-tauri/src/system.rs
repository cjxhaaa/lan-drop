//! Windows 系统集成：开机自启、右键「发送到」、快速访问、在资源管理器中定位。

use base64::Engine;
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use winreg::enums::{HKEY_CURRENT_USER, KEY_READ, KEY_WRITE};
use winreg::RegKey;

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
const RUN_VALUE: &str = "FileTransfer";

/// 用 -EncodedCommand 运行 PowerShell，避免路径里的引号、空格、中文被命令行转义搞坏。
fn powershell(script: &str) -> Result<(), String> {
    let utf16: Vec<u8> = script.encode_utf16().flat_map(u16::to_le_bytes).collect();
    let encoded = base64::engine::general_purpose::STANDARD.encode(utf16);
    let out = Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-EncodedCommand", &encoded])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| e.to_string())?;
    if out.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }
}

fn ps_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "''"))
}

pub fn sendto_link() -> PathBuf {
    let appdata = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_default();
    appdata.join(r"Microsoft\Windows\SendTo\手机互传.lnk")
}

/// 右键菜单「发送到 → 手机互传」：本质是指向接收文件夹的快捷方式，系统会把文件复制进去。
pub fn add_sendto(dir: &Path) -> Result<(), String> {
    powershell(&format!(
        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut({});$s.TargetPath={};$s.Save()",
        ps_quote(&sendto_link().to_string_lossy()),
        ps_quote(&dir.to_string_lossy())
    ))
}

pub fn pin_quick_access(dir: &Path) -> Result<(), String> {
    powershell(&format!(
        "(New-Object -ComObject Shell.Application).Namespace({}).Self.InvokeVerb('pintohome')",
        ps_quote(&dir.to_string_lossy())
    ))
}

pub fn autostart_enabled() -> bool {
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey_with_flags(RUN_KEY, KEY_READ)
        .and_then(|k| k.get_value::<String, _>(RUN_VALUE))
        .is_ok()
}

pub fn set_autostart(enable: bool) -> Result<(), String> {
    let (key, _) = RegKey::predef(HKEY_CURRENT_USER)
        .create_subkey_with_flags(RUN_KEY, KEY_WRITE)
        .map_err(|e| e.to_string())?;
    if enable {
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        key.set_value(RUN_VALUE, &format!("\"{}\" --minimized", exe.display()))
            .map_err(|e| e.to_string())
    } else {
        match key.delete_value(RUN_VALUE) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e.to_string()),
            _ => Ok(()),
        }
    }
}

pub fn reveal(path: &Path) {
    let _ = Command::new("explorer")
        .raw_arg(format!("/select,\"{}\"", path.display()))
        .spawn();
}
