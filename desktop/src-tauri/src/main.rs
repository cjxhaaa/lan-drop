// 发布版不弹出控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod mirror;
mod notify;
mod remote;
mod server;
mod state;
mod system;

use serde::Serialize;
use serde_json::json;
use state::{list_dir, resolve_shared, safe_name, show_main, unique_path, AppState, HTTP_PORT};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Manager, State, WebviewWindow, WindowEvent};

type St<'a> = State<'a, Arc<AppState>>;

const DRAG_ICON: &[u8] = include_bytes!("../icons/32x32.png");

#[derive(Serialize)]
struct FileView {
    name: String,
    size: u64,
    mtime: f64,
    from: Option<String>,
}

#[derive(Serialize)]
struct DeviceView {
    name: String,
    ip: String,
    online: bool,
}

#[tauri::command]
fn get_info(st: St) -> serde_json::Value {
    json!({
        "name": st.name,
        "ips": state::lan_ips(),
        "port": HTTP_PORT,
        "dir": st.dir(),
        "autostart": system::autostart_enabled(),
        "sendto": system::sendto_link().exists(),
        "remote": st.remote_enabled(),
        "helper": remote::helper_active(),
        "notify": st.notify_enabled(),
    })
}

#[tauri::command]
fn list_files(st: St) -> Vec<FileView> {
    // 文件来自哪台手机：从聊天记录里找
    let mut origins = std::collections::HashMap::new();
    for m in st.messages.lock().unwrap().iter() {
        if let (Some(name), "phone") = (&m.name, m.side.as_str()) {
            origins.insert(name.clone(), m.from.clone());
        }
    }
    list_dir(&st.dir())
        .into_iter()
        .map(|f| FileView { from: origins.get(&f.name).cloned(), name: f.name, size: f.size, mtime: f.mtime })
        .collect()
}

#[tauri::command]
fn get_devices(st: St) -> Vec<DeviceView> {
    let devices = st.devices.lock().unwrap();
    let mut list: Vec<_> = devices.iter().collect();
    list.sort_by_key(|(_, d)| std::cmp::Reverse(d.last_seen));
    list.into_iter()
        .map(|(name, d)| DeviceView {
            name: name.clone(),
            ip: d.ip.to_string(),
            // App 在前台时每 3 秒刷新一次列表
            online: d.last_seen.elapsed() < Duration::from_secs(12),
        })
        .collect()
}

#[tauri::command]
fn get_messages(st: St, after: u64) -> Vec<serde_json::Value> {
    st.messages_after(after, 500)
}

#[tauri::command]
fn send_text(st: St, text: String) -> Result<(), String> {
    let text = text.trim();
    if text.is_empty() {
        return Err("内容为空".into());
    }
    st.add_text(text, "pc", "电脑");
    Ok(())
}

#[tauri::command]
fn delete_message(st: St, seq: u64) {
    st.delete_message(seq);
}

#[tauri::command]
fn clear_history(st: St) {
    st.clear_history();
    st.emit("history-cleared", ());
}

#[tauri::command]
fn open_item(st: St, name: String) -> Result<(), String> {
    let path = resolve_shared(&st.dir(), &name).ok_or("文件不存在")?;
    open::that_detached(path).map_err(|e| e.to_string())
}

#[tauri::command]
fn reveal_item(st: St, name: String) {
    match resolve_shared(&st.dir(), &name) {
        Some(path) => system::reveal(&path),
        None => {
            let _ = open::that_detached(st.dir());
        }
    }
}

#[tauri::command]
fn open_folder(st: St) {
    let _ = open::that_detached(st.dir());
}

#[tauri::command]
fn open_url(url: String) {
    if url.starts_with("http://") || url.starts_with("https://") {
        let _ = open::that_detached(url);
    }
}

#[tauri::command]
fn delete_items(st: St, names: Vec<String>) -> Result<(), String> {
    let dir = st.dir();
    let paths: Vec<PathBuf> = names.iter().filter_map(|n| resolve_shared(&dir, n)).collect();
    trash::delete_all(&paths).map_err(|e| e.to_string())
}

#[derive(Serialize)]
struct ImportResult {
    copied: usize,
    skipped_dirs: usize,
}

/// 发送电脑上的文件：复制到接收文件夹，并记一条聊天消息，手机即可下载。
async fn send_paths(st: Arc<AppState>, paths: Vec<PathBuf>) -> Result<ImportResult, String> {
    let dir = st.dir();
    let st2 = st.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let mut r = ImportResult { copied: 0, skipped_dirs: 0 };
        for p in paths {
            if p.is_dir() {
                r.skipped_dirs += 1;
                continue;
            }
            if !p.is_file() {
                continue;
            }
            let name = safe_name(&p.file_name().unwrap_or_default().to_string_lossy());
            // 本来就在共享文件夹里的文件不用再复制一份
            let dest = if p.parent() == Some(dir.as_path()) { p.clone() } else { unique_path(&dir, &name) };
            if dest == p || std::fs::copy(&p, &dest).is_ok() {
                let size = dest.metadata().map(|m| m.len()).unwrap_or(0);
                st2.add_file(&dest.file_name().unwrap().to_string_lossy(), size, "pc", "电脑");
                r.copied += 1;
            }
        }
        r
    })
    .await
    .map_err(|e| e.to_string())?;
    st.emit("files-changed", ());
    Ok(result)
}

#[tauri::command]
async fn import_paths(st: State<'_, Arc<AppState>>, paths: Vec<String>) -> Result<ImportResult, String> {
    send_paths(st.inner().clone(), paths.into_iter().map(PathBuf::from).collect()).await
}

#[tauri::command]
async fn pick_files(st: State<'_, Arc<AppState>>) -> Result<ImportResult, String> {
    let picked = rfd::AsyncFileDialog::new().set_title("选择要发送到手机的文件").pick_files().await;
    let paths = picked.unwrap_or_default().iter().map(|f| f.path().to_path_buf()).collect();
    send_paths(st.inner().clone(), paths).await
}

/// 聊天框里 Ctrl+V 粘贴的图片/文件：前端把字节直接传过来，文件名放在 x-name 头里。
#[tauri::command]
async fn paste_file(st: State<'_, Arc<AppState>>, request: tauri::ipc::Request<'_>) -> Result<(), String> {
    let tauri::ipc::InvokeBody::Raw(data) = request.body() else {
        return Err("粘贴的内容无法识别".into());
    };
    let raw_name = request.headers().get("x-name").and_then(|v| v.to_str().ok()).unwrap_or("粘贴的文件");
    let name = safe_name(&percent_encoding::percent_decode_str(raw_name).decode_utf8_lossy());
    let dest = unique_path(&st.dir(), &name);
    std::fs::write(&dest, data).map_err(|e| format!("保存失败：{e}"))?;
    st.add_file(&dest.file_name().unwrap().to_string_lossy(), data.len() as u64, "pc", "电脑");
    st.emit("files-changed", ());
    Ok(())
}

/// 把共享文件拖出窗口（到桌面、资源管理器、微信等）。同步命令在主线程执行，正好满足 OLE 拖放的要求。
#[tauri::command]
fn start_drag(window: WebviewWindow, st: St, names: Vec<String>) -> Result<(), String> {
    let dir = st.dir();
    let paths: Vec<PathBuf> = names.iter().filter_map(|n| resolve_shared(&dir, n)).collect();
    if paths.is_empty() {
        return Ok(());
    }
    drag::start_drag(
        &window,
        drag::DragItem::Files(paths),
        drag::Image::Raw(DRAG_ICON.to_vec()),
        |_, _| {},
        drag::Options::default(),
    )
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn pair_respond(st: St, allow: bool) {
    if let Some(tx) = st.pending_pair.lock().unwrap().take() {
        let _ = tx.send(allow);
    }
}

#[tauri::command]
async fn choose_folder(st: State<'_, Arc<AppState>>) -> Result<Option<String>, String> {
    let picked = rfd::AsyncFileDialog::new()
        .set_title("选择接收文件夹")
        .set_directory(st.dir())
        .pick_folder()
        .await;
    let Some(folder) = picked else { return Ok(None) };
    let path = folder.path().to_path_buf();
    st.set_dir(path.clone()).map_err(|e| e.to_string())?;
    Ok(Some(path.to_string_lossy().into_owned()))
}

#[tauri::command]
fn set_autostart(enable: bool) -> Result<(), String> {
    system::set_autostart(enable)
}

#[tauri::command]
async fn add_sendto(st: State<'_, Arc<AppState>>) -> Result<(), String> {
    let dir = st.dir();
    tauri::async_runtime::spawn_blocking(move || system::add_sendto(&dir)).await.map_err(|e| e.to_string())?
}

#[tauri::command]
async fn pin_quick_access(st: State<'_, Arc<AppState>>) -> Result<(), String> {
    let dir = st.dir();
    tauri::async_runtime::spawn_blocking(move || system::pin_quick_access(&dir)).await.map_err(|e| e.to_string())?
}

#[tauri::command]
fn browser_qr(st: St) -> Result<serde_json::Value, String> {
    let url = st.browser_url();
    let svg = qrcode::QrCode::new(url.as_bytes())
        .map_err(|e| e.to_string())?
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(200, 200)
        .quiet_zone(true)
        .build();
    Ok(json!({ "url": url, "svg": svg }))
}

#[tauri::command]
fn set_remote(st: St, enable: bool) {
    st.set_remote(enable);
    if !enable {
        remote::disconnect_all();
    }
}

/// 投屏状态：是否在投屏、来自哪台手机、能否操作手机
#[tauri::command]
fn mirror_state(st: St) -> serde_json::Value {
    json!({
        "active": st.mirror.active(),
        "device": st.mirror.device.lock().unwrap().clone(),
        "control": st.mirror.can_control(),
    })
}

/// 把电脑上的一次操作（点击、滑动、按键、文字）发给手机
#[tauri::command]
fn mirror_input(st: St, event: serde_json::Value) -> bool {
    st.mirror.send(event.to_string())
}

#[tauri::command]
fn set_notify(st: St, enable: bool) {
    st.set_notify(enable);
}

#[tauri::command]
async fn start_input_helper() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(remote::start_helper).await.map_err(|e| e.to_string())?
}

#[tauri::command]
fn remote_disconnect() {
    remote::disconnect_all();
}

/// 手机扫码安装 App 的二维码
#[tauri::command]
fn install_qr() -> serde_json::Value {
    if server::APP_APK.is_empty() {
        return json!({ "available": false });
    }
    let ip = state::lan_ips().into_iter().next().unwrap_or_else(|| "127.0.0.1".into());
    let url = format!("http://{ip}:{HTTP_PORT}/app.apk");
    let svg = qrcode::QrCode::new(url.as_bytes())
        .map(|q| q.render::<qrcode::render::svg::Color>().min_dimensions(200, 200).quiet_zone(true).build())
        .unwrap_or_default();
    json!({ "available": true, "url": url, "svg": svg, "size": server::APP_APK.len() })
}

#[tauri::command]
fn reset_key(st: St) {
    st.reset_key();
}

#[tauri::command]
fn clipboard_read() -> Option<String> {
    arboard::Clipboard::new().and_then(|mut c| c.get_text()).ok().filter(|t| !t.trim().is_empty())
}

#[tauri::command]
fn clipboard_write(text: String) -> Result<(), String> {
    arboard::Clipboard::new().and_then(|mut c| c.set_text(text)).map_err(|e| e.to_string())
}

/// 程序已在运行时，通知它把窗口显示出来。成功返回 true。
fn wake_existing() -> bool {
    let Ok(mut s) = TcpStream::connect_timeout(&([127, 0, 0, 1], HTTP_PORT).into(), Duration::from_secs(1)) else {
        return false;
    };
    let _ = s.set_read_timeout(Some(Duration::from_secs(2)));
    if s.write_all(b"GET /api/show HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n").is_err() {
        return false;
    }
    let mut resp = String::new();
    let _ = s.read_to_string(&mut resp);
    resp.contains("\"app\":\"ft\"")
}

fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    let menu = Menu::with_items(
        app,
        &[
            &MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?,
            &MenuItem::with_id(app, "folder", "打开接收文件夹", true, None::<&str>)?,
            &PredefinedMenuItem::separator(app)?,
            &MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?,
        ],
    )?;
    TrayIconBuilder::with_id("main")
        .icon(app.default_window_icon().unwrap().clone())
        .tooltip("文件互传")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_main(app),
            "folder" => {
                let _ = open::that_detached(app.state::<Arc<AppState>>().dir());
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_main(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

fn main() {
    // 以管理员身份启动的输入助手进程：只负责注入鼠标键盘，不打开窗口
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("--input-helper") {
        if let (Some(port), Some(token)) = (args.get(2).and_then(|p| p.parse().ok()), args.get(3)) {
            remote::run_helper(port, token);
        }
        return;
    }
    // 先占住端口：占不到说明程序已经在运行（或端口被别的程序占了）
    let listener = match std::net::TcpListener::bind(("0.0.0.0", HTTP_PORT)) {
        Ok(l) => l,
        Err(_) => {
            if !wake_existing() {
                rfd::MessageDialog::new()
                    .set_title("文件互传")
                    .set_description(format!("端口 {HTTP_PORT} 被其他程序占用，无法启动。"))
                    .set_level(rfd::MessageLevel::Error)
                    .show();
            }
            return;
        }
    };
    notify::init();
    let state = Arc::new(AppState::load());
    let minimized = std::env::args().any(|a| a == "--minimized");

    tauri::Builder::default()
        .manage(state.clone())
        .invoke_handler(tauri::generate_handler![
            get_info,
            list_files,
            get_devices,
            get_messages,
            send_text,
            delete_message,
            clear_history,
            pick_files,
            paste_file,
            open_item,
            reveal_item,
            open_folder,
            open_url,
            delete_items,
            import_paths,
            start_drag,
            pair_respond,
            choose_folder,
            set_autostart,
            add_sendto,
            pin_quick_access,
            browser_qr,
            reset_key,
            set_remote,
            remote_disconnect,
            start_input_helper,
            set_notify,
            install_qr,
            mirror_state,
            mirror_input,
            clipboard_read,
            clipboard_write,
        ])
        .setup(move |app| {
            let _ = state.app.set(app.handle().clone());
            server::start(listener, state.clone());
            build_tray(app)?;
            if !minimized {
                show_main(app.handle());
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // 关闭按钮只是隐藏到托盘，保持后台接收
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("启动失败");
}
