//! 全局状态与通用工具：配置、密钥、文件名处理、局域网地址。

use base64::Engine;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};

/// 界面是否用英文（由设置里的语言决定，"auto" 跟随 Windows 显示语言）
static EN: AtomicBool = AtomicBool::new(false);

pub fn is_en() -> bool {
    EN.load(Ordering::Relaxed)
}

/// 按当前语言二选一
pub fn tr(zh: &'static str, en: &'static str) -> &'static str {
    if is_en() { en } else { zh }
}

/// Windows 显示语言不是中文时用英文
pub fn system_is_en() -> bool {
    const LANG_CHINESE: u16 = 0x04;
    let id = unsafe { windows::Win32::Globalization::GetUserDefaultUILanguage() };
    id & 0x3ff != LANG_CHINESE
}

fn apply_lang(lang: &str) {
    let en = match lang {
        "en" => true,
        "zh" => false,
        _ => system_is_en(),
    };
    EN.store(en, Ordering::Relaxed);
}

pub const HTTP_PORT: u16 = 8765;
pub const DISCOVERY_PORT: u16 = 8766;

#[derive(Serialize, Deserialize, Clone)]
pub struct Config {
    pub key: String,
    pub dir: PathBuf,
    /// 是否允许已配对的手机远程控制这台电脑（默认关闭）
    #[serde(default)]
    pub remote: bool,
    /// 手机发来消息时弹系统通知（默认开）
    #[serde(default = "yes")]
    pub notify: bool,
    /// 界面语言："auto" | "zh" | "en"
    #[serde(default = "auto")]
    pub lang: String,
}

fn yes() -> bool {
    true
}

fn auto() -> String {
    "auto".into()
}

/// 聊天记录里的一条消息：文字或文件（图片也是文件）。
#[derive(Serialize, Deserialize, Clone)]
pub struct Message {
    pub seq: u64,
    /// "text" | "file"
    pub kind: String,
    /// 发送方在哪一端："pc" | "phone"
    pub side: String,
    pub from: String,
    pub time: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<u64>,
}

const HISTORY_LIMIT: usize = 2000;

#[derive(Serialize, Clone)]
pub struct FileEntry {
    pub name: String,
    pub size: u64,
    pub mtime: f64,
}

pub struct Device {
    pub ip: IpAddr,
    pub last_seen: Instant,
}

pub struct AppState {
    pub app: OnceLock<AppHandle>,
    pub name: String,
    cfg: Mutex<Config>,
    pub messages: Mutex<Vec<Message>>,
    pub devices: Mutex<HashMap<String, Device>>,
    pub pending_pair: Mutex<Option<tokio::sync::oneshot::Sender<bool>>>,
    /// 有新消息时唤醒手机的长轮询
    pub new_message: tokio::sync::Notify,
    /// 手机投屏到电脑
    pub mirror: crate::mirror::Mirror,
    pub pair_rejected: Mutex<HashMap<IpAddr, Instant>>,
}

impl AppState {
    pub fn load() -> Self {
        let path = config_path();
        let mut cfg: Option<Config> = std::fs::read_to_string(&path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok());
        let fresh = cfg.is_none();
        apply_lang(cfg.as_ref().map_or("auto", |c| c.lang.as_str()));
        let cfg = cfg
            .get_or_insert_with(|| Config { key: random_key(), dir: default_dir(), remote: false, notify: true, lang: auto() })
            .clone();
        let _ = std::fs::create_dir_all(&cfg.dir);
        let state = AppState {
            app: OnceLock::new(),
            name: std::env::var("COMPUTERNAME").map(|n| n.to_lowercase()).unwrap_or_else(|_| "电脑".into()),
            cfg: Mutex::new(cfg),
            messages: Mutex::new(load_history()),
            devices: Mutex::new(HashMap::new()),
            pending_pair: Mutex::new(None),
            new_message: tokio::sync::Notify::new(),
            mirror: crate::mirror::Mirror::new(),
            pair_rejected: Mutex::new(HashMap::new()),
        };
        if fresh {
            state.save();
        }
        state
    }

    pub fn key(&self) -> String {
        self.cfg.lock().unwrap().key.clone()
    }

    pub fn id(&self) -> String {
        server_id(&self.key())
    }

    pub fn dir(&self) -> PathBuf {
        self.cfg.lock().unwrap().dir.clone()
    }

    pub fn notify_enabled(&self) -> bool {
        self.cfg.lock().unwrap().notify
    }

    pub fn set_notify(&self, enable: bool) {
        self.cfg.lock().unwrap().notify = enable;
        self.save();
    }

    pub fn lang(&self) -> String {
        self.cfg.lock().unwrap().lang.clone()
    }

    pub fn set_lang(&self, lang: &str) {
        let lang = if matches!(lang, "zh" | "en") { lang } else { "auto" };
        self.cfg.lock().unwrap().lang = lang.into();
        apply_lang(lang);
        self.save();
    }

    pub fn remote_enabled(&self) -> bool {
        self.cfg.lock().unwrap().remote
    }

    pub fn set_remote(&self, enable: bool) {
        self.cfg.lock().unwrap().remote = enable;
        self.save();
    }

    pub fn set_dir(&self, dir: PathBuf) -> std::io::Result<()> {
        std::fs::create_dir_all(&dir)?;
        self.cfg.lock().unwrap().dir = dir;
        self.save();
        Ok(())
    }

    /// 更换密钥：所有已配对的手机都需要重新配对。
    pub fn reset_key(&self) {
        self.cfg.lock().unwrap().key = random_key();
        self.devices.lock().unwrap().clear();
        self.save();
    }

    fn save(&self) {
        let path = config_path();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let cfg = self.cfg.lock().unwrap().clone();
        let _ = std::fs::write(path, serde_json::to_string_pretty(&cfg).unwrap());
    }

    pub fn emit<S: Serialize + Clone>(&self, event: &str, payload: S) {
        if let Some(app) = self.app.get() {
            let _ = app.emit(event, payload);
        }
    }

    pub fn show_window(&self) {
        if let Some(app) = self.app.get() {
            show_main(app);
        }
    }

    fn push(&self, mut msg: Message) -> Message {
        let mut list = self.messages.lock().unwrap();
        msg.seq = list.last().map_or(1, |m| m.seq + 1);
        list.push(msg.clone());
        let excess = list.len().saturating_sub(HISTORY_LIMIT);
        list.drain(..excess);
        save_history(&list);
        drop(list);
        self.emit("message", self.message_json(&msg));
        self.new_message.notify_waiters();
        if msg.side == "phone" {
            self.notify_phone_message(&msg);
        }
        msg
    }

    pub fn add_text(&self, text: &str, side: &str, from: &str) -> Message {
        self.push(Message {
            seq: 0,
            kind: "text".into(),
            side: side.into(),
            from: from.into(),
            time: now_secs(),
            text: Some(text.chars().take(100_000).collect()),
            name: None,
            size: None,
        })
    }

    pub fn add_file(&self, name: &str, size: u64, side: &str, from: &str) -> Message {
        self.push(Message {
            seq: 0,
            kind: "file".into(),
            side: side.into(),
            from: from.into(),
            time: now_secs(),
            text: None,
            name: Some(name.into()),
            size: Some(size),
        })
    }

    /// 窗口在前台时不打扰；藏在托盘或被别的窗口挡住时弹系统通知，点击打开聊天
    fn notify_phone_message(&self, m: &Message) {
        if !self.notify_enabled() {
            return;
        }
        let Some(app) = self.app.get() else { return };
        let focused = app
            .get_webview_window("main")
            .map(|w| w.is_visible().unwrap_or(false) && w.is_focused().unwrap_or(false) && !w.is_minimized().unwrap_or(false))
            .unwrap_or(false);
        if focused {
            return;
        }
        let body = match (m.kind.as_str(), &m.name) {
            ("text", _) => m.text.clone().unwrap_or_default(),
            (_, Some(name)) if is_image(name) => format!("{} {name}", tr("[图片]", "[Image]")),
            (_, Some(name)) => format!("{} {name}", tr("[文件]", "[File]")),
            _ => return,
        };
        let app = app.clone();
        crate::notify::show(&format!("{}{}", tr("来自 ", "From "), m.from), &body, move || {
            show_main(&app);
            let _ = app.emit("open-chat", ());
        });
    }

    /// 序列化消息，并附上文件是否还在共享文件夹里（可能已被删除或移走）。
    pub fn message_json(&self, m: &Message) -> serde_json::Value {
        let mut v = serde_json::to_value(m).unwrap();
        if let Some(name) = &m.name {
            v["exists"] = self.dir().join(name).is_file().into();
        }
        v
    }

    /// seq 大于 after 的消息，最多 limit 条（取最新的）。
    pub fn messages_after(&self, after: u64, limit: usize) -> Vec<serde_json::Value> {
        let list = self.messages.lock().unwrap();
        let newer: Vec<&Message> = list.iter().filter(|m| m.seq > after).collect();
        let skip = newer.len().saturating_sub(limit);
        newer[skip..].iter().map(|m| self.message_json(m)).collect()
    }

    /// 留下一个 "deleted" 标记而不是直接移除，保证序号只增不减，手机端增量拉取不会漏消息。
    pub fn delete_message(&self, seq: u64) {
        let mut list = self.messages.lock().unwrap();
        if let Some(m) = list.iter_mut().find(|m| m.seq == seq) {
            m.kind = "deleted".into();
            m.text = None;
            m.name = None;
            m.size = None;
        }
        save_history(&list);
    }

    /// 清空记录，但保留序号，避免手机端的增量拉取错乱。
    pub fn clear_history(&self) {
        let mut list = self.messages.lock().unwrap();
        if let Some(last) = list.last().cloned() {
            list.clear();
            // 用一个新序号的标记告诉手机“记录已清空”，它增量拉取时才能看到
            list.push(Message { seq: last.seq + 1, kind: "cleared".into(), text: None, name: None, size: None, ..last });
        }
        save_history(&list);
    }

    pub fn browser_url(&self) -> String {
        let ip = lan_ips().into_iter().next().unwrap_or_else(|| "127.0.0.1".into());
        format!("http://{ip}:{HTTP_PORT}/?k={}", self.key())
    }
}

pub fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn data_dir() -> PathBuf {
    let base = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    base.join("FileTransfer")
}

fn config_path() -> PathBuf {
    data_dir().join("config.json")
}

fn load_history() -> Vec<Message> {
    std::fs::read_to_string(data_dir().join("history.json"))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_history(list: &[Message]) {
    let _ = std::fs::create_dir_all(data_dir());
    let tmp = data_dir().join("history.json.tmp");
    if std::fs::write(&tmp, serde_json::to_string(list).unwrap()).is_ok() {
        let _ = std::fs::rename(tmp, data_dir().join("history.json"));
    }
}

fn default_dir() -> PathBuf {
    let home = std::env::var_os("USERPROFILE").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    home.join(tr("手机互传", "LanDrop"))
}

pub fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    getrandom::fill(&mut buf).expect("系统随机数不可用");
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

fn random_key() -> String {
    let mut buf = [0u8; 12];
    getrandom::fill(&mut buf).expect("系统随机数不可用");
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buf)
}

/// 与密钥绑定的电脑 ID，App 用它在 IP 变化后重新找到同一台电脑。
pub fn server_id(key: &str) -> String {
    let digest = Sha256::digest(key.as_bytes());
    digest.iter().take(6).map(|b| format!("{b:02x}")).collect()
}

pub fn now_secs() -> f64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0)
}

pub fn is_image(name: &str) -> bool {
    let lower = name.to_lowercase();
    [".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".avif"].iter().any(|e| lower.ends_with(e))
}

pub fn safe_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let cleaned: String = base
        .chars()
        .map(|c| if "<>:\"/\\|?*".contains(c) || c.is_control() { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim().trim_matches(|c| c == '.' || c == ' ').to_string();
    if cleaned.is_empty() {
        format!("file_{}", now_secs() as u64)
    } else {
        cleaned
    }
}

pub fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let mut path = dir.join(name);
    let stem = path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let ext = path.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    let mut i = 1;
    while path.exists() {
        path = dir.join(format!("{stem} ({i}){ext}"));
        i += 1;
    }
    path
}

pub fn resolve_shared(dir: &Path, name: &str) -> Option<PathBuf> {
    let path = dir.join(safe_name(name));
    path.is_file().then_some(path)
}

fn is_hidden(meta: &std::fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const HIDDEN: u32 = 0x2;
    const SYSTEM: u32 = 0x4;
    meta.file_attributes() & (HIDDEN | SYSTEM) != 0
}

/// 共享文件夹里的文件（不含子文件夹、隐藏文件和上传中的临时文件），新的在前。
pub fn list_dir(dir: &Path) -> Vec<FileEntry> {
    let mut files: Vec<FileEntry> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let meta = e.metadata().ok()?;
            let name = e.file_name().to_string_lossy().into_owned();
            if !meta.is_file() || is_hidden(&meta) || name.ends_with(".part") || name.starts_with("~$") {
                return None;
            }
            let mtime = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_secs_f64())
                .unwrap_or(0.0);
            Some(FileEntry { name, size: meta.len(), mtime })
        })
        .collect();
    files.sort_by(|a, b| b.mtime.total_cmp(&a.mtime));
    files
}

/// 手机可能访问到的本机 IPv4 地址，真实网卡在前，WSL / Hyper-V 等虚拟网卡在后。
pub fn lan_ips() -> Vec<String> {
    let mut list: Vec<(u8, String)> = local_ip_address::list_afinet_netifas()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(iface, ip)| {
            let IpAddr::V4(v4) = ip else { return None };
            let o = v4.octets();
            let mut rank = match (o[0], o[1]) {
                (192, 168) => 0,
                (10, _) => 1,
                (172, 16..=31) => 2,
                _ => return None,
            };
            let lower = iface.to_lowercase();
            if ["vethernet", "wsl", "hyper-v", "vmware", "virtualbox", "bluetooth", "蓝牙", "docker"]
                .iter()
                .any(|v| lower.contains(v))
            {
                rank += 10;
            }
            Some((rank, v4.to_string()))
        })
        .collect();
    list.sort_by_key(|(rank, _)| *rank);
    let mut seen = std::collections::HashSet::new();
    list.into_iter().filter(|(_, ip)| seen.insert(ip.clone())).map(|(_, ip)| ip).collect()
}
