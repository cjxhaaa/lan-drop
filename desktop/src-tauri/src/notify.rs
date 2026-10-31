//! Windows 系统通知（右下角弹窗）。
//!
//! 免安装的 exe 没有开始菜单快捷方式，需要先在注册表里登记一个通知身份（AUMID），
//! 通知才会显示为「文件互传」和它的图标。

use windows::core::HSTRING;
use windows::Data::Xml::Dom::XmlDocument;
use windows::Foundation::TypedEventHandler;
use windows::UI::Notifications::{ToastNotification, ToastNotificationManager};
use windows::Win32::UI::Shell::SetCurrentProcessExplicitAppUserModelID;
use winreg::enums::HKEY_CURRENT_USER;
use winreg::RegKey;

const AUMID: &str = "FileTransfer.Desktop";
const ICON: &[u8] = include_bytes!("../icons/128x128.png");

pub fn init() {
    let base = std::env::var_os("APPDATA").map(std::path::PathBuf::from).unwrap_or_default().join("FileTransfer");
    let _ = std::fs::create_dir_all(&base);
    let icon = base.join("icon.png");
    let _ = std::fs::write(&icon, ICON);
    if let Ok((key, _)) = RegKey::predef(HKEY_CURRENT_USER).create_subkey(format!(r"Software\Classes\AppUserModelId\{AUMID}")) {
        let _ = key.set_value("DisplayName", &crate::state::tr("文件互传", "LanDrop"));
        let _ = key.set_value("IconUri", &icon.to_string_lossy().into_owned());
    }
    unsafe {
        let _ = SetCurrentProcessExplicitAppUserModelID(&HSTRING::from(AUMID));
    }
}

fn escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// 弹出通知；用户点击时调用 on_click（在后台线程里）
pub fn show(title: &str, body: &str, on_click: impl Fn() + Send + Sync + 'static) {
    let body: String = body.chars().take(200).collect();
    let xml = format!(
        r#"<toast launch="open"><visual><binding template="ToastGeneric"><text>{}</text><text>{}</text></binding></visual></toast>"#,
        escape(title),
        escape(&body)
    );
    let result = (|| -> windows::core::Result<()> {
        let doc = XmlDocument::new()?;
        doc.LoadXml(&HSTRING::from(xml))?;
        let toast = ToastNotification::CreateToastNotification(&doc)?;
        toast.Activated(&TypedEventHandler::new(move |_, _| {
            on_click();
            Ok(())
        }))?;
        ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(AUMID))?.Show(&toast)
    })();
    if let Err(e) = result {
        eprintln!("通知发送失败：{e}");
    }
}
