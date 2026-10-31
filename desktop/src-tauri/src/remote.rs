//! 远程控制：把屏幕画面推给手机，并把手机发来的触控、键盘操作注入到系统。
//!
//! 画面流格式（HTTP 响应体里连续写）：每帧 = [u32 JPEG 长度][f32 光标 x][f32 光标 y][JPEG 数据]，
//! 坐标是相对当前显示器的 0~1。长度为 0 表示画面没变、只更新光标（也兼作心跳）。

use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use windows::Win32::Foundation::POINT;
use windows::Win32::UI::Input::KeyboardAndMouse::*;
use windows::Win32::UI::WindowsAndMessaging::{GetCursorPos, SetCursorPos};
use xcap::Monitor;

/// 清晰度档位：(画面最大宽度, JPEG 质量)。越清晰，每帧越大、帧率越低
fn preset(quality: &str) -> (u32, u8) {
    match quality {
        "smooth" => (1280, 60),
        "full" => (u32::MAX, 85),
        _ => (1920, 75), // 高清，默认
    }
}

const FRAME_INTERVAL: Duration = Duration::from_millis(55);
const HEARTBEAT: Duration = Duration::from_secs(2);

/// 每次「断开」加一，正在推流的线程发现后自行退出
static GENERATION: AtomicU64 = AtomicU64::new(0);
static ACTIVE_STREAMS: AtomicUsize = AtomicUsize::new(0);
/// 当前被控制的显示器区域（物理像素）：x, y, 宽, 高
static RECT: Mutex<(i32, i32, u32, u32)> = Mutex::new((0, 0, 1, 1));

#[derive(Serialize, Clone)]
pub struct MonitorInfo {
    pub index: usize,
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub primary: bool,
}

pub fn monitors() -> Vec<MonitorInfo> {
    Monitor::all()
        .unwrap_or_default()
        .iter()
        .enumerate()
        .map(|(index, m)| MonitorInfo {
            index,
            name: m.name().unwrap_or_default(),
            width: m.width().unwrap_or(0),
            height: m.height().unwrap_or(0),
            primary: m.is_primary().unwrap_or(false),
        })
        .collect()
}

pub fn disconnect_all() {
    GENERATION.fetch_add(1, Ordering::SeqCst);
}

/// 推流线程计数：从 0 变 1、从 1 变 0 时回调，用来在电脑上显示/隐藏「正在被控制」提示
pub struct StreamGuard<F: Fn(bool)> {
    notify: F,
}

impl<F: Fn(bool)> StreamGuard<F> {
    pub fn new(notify: F) -> Self {
        if ACTIVE_STREAMS.fetch_add(1, Ordering::SeqCst) == 0 {
            notify(true);
        }
        StreamGuard { notify }
    }
}

impl<F: Fn(bool)> Drop for StreamGuard<F> {
    fn drop(&mut self) {
        if ACTIVE_STREAMS.fetch_sub(1, Ordering::SeqCst) == 1 {
            (self.notify)(false);
        }
    }
}

/// 在独立线程里循环截屏、压缩、发送，直到手机断开或电脑端点了「断开」。
pub fn stream(index: usize, quality: &str, tx: tokio::sync::mpsc::Sender<Vec<u8>>) {
    let (max_width, jpeg_quality) = preset(quality);
    let generation = GENERATION.load(Ordering::SeqCst);
    let monitors = Monitor::all().unwrap_or_default();
    let Some(monitor) = monitors
        .get(index)
        .or_else(|| monitors.iter().find(|m| m.is_primary().unwrap_or(false)))
        .or(monitors.first())
    else {
        return;
    };
    let rect = (
        monitor.x().unwrap_or(0),
        monitor.y().unwrap_or(0),
        monitor.width().unwrap_or(1).max(1),
        monitor.height().unwrap_or(1).max(1),
    );
    *RECT.lock().unwrap() = rect;

    let mut prev: Vec<u8> = Vec::new();
    let mut last_cursor = (-1.0f32, -1.0f32);
    let mut last_send = Instant::now();
    while GENERATION.load(Ordering::SeqCst) == generation && !tx.is_closed() {
        let started = Instant::now();
        let cursor = cursor_in(rect);
        let mut frame: Option<Vec<u8>> = None;

        if let Ok(img) = monitor.capture_image() {
            let (w, h) = (img.width(), img.height());
            let dw = w.min(max_width);
            let dh = ((h as u64 * dw as u64) / w as u64).max(1) as u32;
            let (pixels, dw, dh) = downscale(img.as_raw(), w, h, dw, dh);
            if pixels != prev {
                let mut jpeg = Vec::with_capacity(200 * 1024);
                let enc = jpeg_encoder::Encoder::new(&mut jpeg, jpeg_quality);
                if enc.encode(&pixels, dw as u16, dh as u16, jpeg_encoder::ColorType::Rgba).is_ok() {
                    frame = Some(jpeg);
                    prev = pixels;
                }
            }
        }

        let cursor_moved = (cursor.0 - last_cursor.0).abs() > 0.0005 || (cursor.1 - last_cursor.1).abs() > 0.0005;
        let payload = match frame {
            Some(jpeg) => Some(packet(&jpeg, cursor)),
            None if cursor_moved || last_send.elapsed() > HEARTBEAT => Some(packet(&[], cursor)),
            None => None,
        };
        if let Some(p) = payload {
            if tx.blocking_send(p).is_err() {
                break;
            }
            last_cursor = cursor;
            last_send = Instant::now();
        }
        let spent = started.elapsed();
        std::thread::sleep(FRAME_INTERVAL.saturating_sub(spent).max(Duration::from_millis(8)));
    }
}

fn packet(jpeg: &[u8], cursor: (f32, f32)) -> Vec<u8> {
    let mut p = Vec::with_capacity(12 + jpeg.len());
    p.extend_from_slice(&(jpeg.len() as u32).to_be_bytes());
    p.extend_from_slice(&cursor.0.to_be_bytes());
    p.extend_from_slice(&cursor.1.to_be_bytes());
    p.extend_from_slice(jpeg);
    p
}

/// 区域平均缩小到任意尺寸：每个目标像素取它覆盖的源像素平均值，比插值清晰，也足够快
fn downscale(src: &[u8], w: u32, h: u32, dw: u32, dh: u32) -> (Vec<u8>, u32, u32) {
    if dw >= w {
        return (src.to_vec(), w, h);
    }
    let mut out = vec![0u8; (dw * dh * 4) as usize];
    let xs: Vec<(usize, usize)> = (0..dw)
        .map(|x| {
            let a = (x as u64 * w as u64 / dw as u64) as usize;
            let b = (((x + 1) as u64 * w as u64 / dw as u64) as usize).max(a + 1);
            (a, b)
        })
        .collect();
    for y in 0..dh {
        let y0 = (y as u64 * h as u64 / dh as u64) as usize;
        let y1 = (((y + 1) as u64 * h as u64 / dh as u64) as usize).max(y0 + 1);
        for (x, &(x0, x1)) in xs.iter().enumerate() {
            let mut acc = [0u32; 3];
            for sy in y0..y1 {
                let row = sy * w as usize * 4;
                for sx in x0..x1 {
                    let i = row + sx * 4;
                    acc[0] += src[i] as u32;
                    acc[1] += src[i + 1] as u32;
                    acc[2] += src[i + 2] as u32;
                }
            }
            let n = ((y1 - y0) * (x1 - x0)) as u32;
            let o = (y as usize * dw as usize + x) * 4;
            out[o] = (acc[0] / n) as u8;
            out[o + 1] = (acc[1] / n) as u8;
            out[o + 2] = (acc[2] / n) as u8;
            out[o + 3] = 255;
        }
    }
    (out, dw, dh)
}

fn cursor_in(rect: (i32, i32, u32, u32)) -> (f32, f32) {
    let mut p = POINT::default();
    if unsafe { GetCursorPos(&mut p) }.is_err() {
        return (-1.0, -1.0);
    }
    ((p.x - rect.0) as f32 / rect.2 as f32, (p.y - rect.1) as f32 / rect.3 as f32)
}

// ---------- 输入注入 ----------

#[derive(Deserialize)]
#[serde(tag = "t", rename_all = "lowercase")]
pub enum Event {
    /// 把鼠标移到画面上的相对位置（0~1）
    Move { x: f32, y: f32 },
    /// 触控板模式：按比例相对移动（单位是显示器宽/高）
    Moveby { dx: f32, dy: f32 },
    Down { b: String },
    Up { b: String },
    /// 没带坐标时在鼠标当前位置点击
    Click { b: String, #[serde(default)] x: Option<f32>, #[serde(default)] y: Option<f32>, #[serde(default)] n: u8 },
    /// 滚轮：正数向上，120 为一格
    Scroll { #[serde(default)] dy: i32, #[serde(default)] dx: i32 },
    Text { s: String },
    Key { k: String, #[serde(default)] mods: Vec<String> },
}

pub fn handle(events: Vec<Event>) {
    for e in events {
        match e {
            Event::Move { x, y } => move_to(x, y),
            Event::Moveby { dx, dy } => move_by(dx, dy),
            Event::Down { b } => button(&b, true),
            Event::Up { b } => button(&b, false),
            Event::Click { b, x, y, n } => {
                if let (Some(x), Some(y)) = (x, y) {
                    move_to(x, y);
                }
                for _ in 0..n.clamp(1, 3) {
                    button(&b, true);
                    button(&b, false);
                }
            }
            Event::Scroll { dy, dx } => {
                if dy != 0 {
                    mouse(MOUSEEVENTF_WHEEL, dy);
                }
                if dx != 0 {
                    mouse(MOUSEEVENTF_HWHEEL, dx);
                }
            }
            Event::Text { s } => type_text(&s),
            Event::Key { k, mods } => press_key(&k, &mods),
        }
    }
}

fn move_to(x: f32, y: f32) {
    let (rx, ry, rw, rh) = *RECT.lock().unwrap();
    let px = rx + (x.clamp(0.0, 1.0) * (rw - 1) as f32).round() as i32;
    let py = ry + (y.clamp(0.0, 1.0) * (rh - 1) as f32).round() as i32;
    let _ = unsafe { SetCursorPos(px, py) };
}

/// 相对移动的小数部分累积起来，慢慢挪时也不会因为取整而卡住
static REMAINDER: Mutex<(f32, f32)> = Mutex::new((0.0, 0.0));

fn move_by(dx: f32, dy: f32) {
    let (rx, ry, rw, rh) = *RECT.lock().unwrap();
    let mut rem = REMAINDER.lock().unwrap();
    let fx = dx * rw as f32 + rem.0;
    let fy = dy * rh as f32 + rem.1;
    let (ix, iy) = (fx.trunc() as i32, fy.trunc() as i32);
    *rem = (fx - ix as f32, fy - iy as f32);
    let mut p = POINT::default();
    if unsafe { GetCursorPos(&mut p) }.is_err() {
        return;
    }
    let nx = (p.x + ix).clamp(rx, rx + rw as i32 - 1);
    let ny = (p.y + iy).clamp(ry, ry + rh as i32 - 1);
    let _ = unsafe { SetCursorPos(nx, ny) };
}

fn button(b: &str, down: bool) {
    let flag = match (b, down) {
        ("right", true) => MOUSEEVENTF_RIGHTDOWN,
        ("right", false) => MOUSEEVENTF_RIGHTUP,
        ("middle", true) => MOUSEEVENTF_MIDDLEDOWN,
        ("middle", false) => MOUSEEVENTF_MIDDLEUP,
        (_, true) => MOUSEEVENTF_LEFTDOWN,
        (_, false) => MOUSEEVENTF_LEFTUP,
    };
    mouse(flag, 0);
}

fn mouse(flags: MOUSE_EVENT_FLAGS, data: i32) {
    let input = INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT { dx: 0, dy: 0, mouseData: data as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    };
    unsafe { SendInput(&[input], std::mem::size_of::<INPUT>() as i32) };
}

fn key_input(vk: VIRTUAL_KEY, scan: u16, flags: KEYBD_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: vk, wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
    }
}

/// 直接按 Unicode 字符输入，中文、表情都可以，不依赖电脑当前的输入法
fn type_text(s: &str) {
    let mut inputs = Vec::new();
    for unit in s.encode_utf16() {
        if unit == '\n' as u16 {
            inputs.push(key_input(VK_RETURN, 0, KEYBD_EVENT_FLAGS(0)));
            inputs.push(key_input(VK_RETURN, 0, KEYEVENTF_KEYUP));
            continue;
        }
        inputs.push(key_input(VIRTUAL_KEY(0), unit, KEYEVENTF_UNICODE));
        inputs.push(key_input(VIRTUAL_KEY(0), unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
    }
    if !inputs.is_empty() {
        unsafe { SendInput(&inputs, std::mem::size_of::<INPUT>() as i32) };
    }
}

fn vk_of(name: &str) -> Option<(VIRTUAL_KEY, bool)> {
    let extended = |vk| Some((vk, true));
    let plain = |vk| Some((vk, false));
    match name {
        "enter" => plain(VK_RETURN),
        "backspace" => plain(VK_BACK),
        "tab" => plain(VK_TAB),
        "esc" => plain(VK_ESCAPE),
        "space" => plain(VK_SPACE),
        "delete" => extended(VK_DELETE),
        "left" => extended(VK_LEFT),
        "right" => extended(VK_RIGHT),
        "up" => extended(VK_UP),
        "down" => extended(VK_DOWN),
        "home" => extended(VK_HOME),
        "end" => extended(VK_END),
        "pageup" => extended(VK_PRIOR),
        "pagedown" => extended(VK_NEXT),
        "win" => extended(VK_LWIN),
        "ctrl" => plain(VK_CONTROL),
        "alt" => plain(VK_MENU),
        "shift" => plain(VK_SHIFT),
        k if k.len() == 1 && k.as_bytes()[0].is_ascii_alphanumeric() => {
            plain(VIRTUAL_KEY(k.to_ascii_uppercase().as_bytes()[0] as u16))
        }
        k if k.starts_with('f') && k[1..].parse::<u16>().is_ok_and(|n| (1..=12).contains(&n)) => {
            plain(VIRTUAL_KEY(VK_F1.0 + k[1..].parse::<u16>().unwrap() - 1))
        }
        _ => None,
    }
}

fn press_key(key: &str, mods: &[String]) {
    let Some((vk, ext)) = vk_of(key) else { return };
    let mods: Vec<(VIRTUAL_KEY, bool)> = mods.iter().filter_map(|m| vk_of(m)).collect();
    let flag = |ext: bool, up: bool| {
        let mut f = KEYBD_EVENT_FLAGS(0);
        if ext {
            f |= KEYEVENTF_EXTENDEDKEY;
        }
        if up {
            f |= KEYEVENTF_KEYUP;
        }
        f
    };
    let mut inputs = Vec::new();
    for &(m, e) in &mods {
        inputs.push(key_input(m, 0, flag(e, false)));
    }
    inputs.push(key_input(vk, 0, flag(ext, false)));
    inputs.push(key_input(vk, 0, flag(ext, true)));
    for &(m, e) in mods.iter().rev() {
        inputs.push(key_input(m, 0, flag(e, true)));
    }
    unsafe { SendInput(&inputs, std::mem::size_of::<INPUT>() as i32) };
}

// ---------- 管理员权限的输入助手 ----------
//
// Windows 不允许普通权限的程序向「以管理员身份运行」的窗口（很多游戏、任务管理器）发送鼠标键盘操作。
// 需要时以管理员身份另起一个只负责注入输入的小进程（同一个 exe，带 --input-helper 参数），
// 主程序本身仍是普通权限，拖放文件等功能不受影响。助手主动连回主程序，只听这一条连接的指令。

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use windows::core::{HSTRING, w};
use windows::Win32::Foundation::{CloseHandle, HANDLE};
use windows::Win32::Security::{GetTokenInformation, TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY};
use windows::Win32::System::Threading::{OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION};
use windows::Win32::UI::Shell::ShellExecuteW;
use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId, SW_HIDE};

static HELPER: Mutex<Option<TcpStream>> = Mutex::new(None);

pub fn helper_active() -> bool {
    HELPER.lock().unwrap().is_some()
}

/// 启动输入助手：Windows 会弹出一次「是否允许更改」的确认。用户同意并连上后返回 Ok。
pub fn start_helper() -> Result<(), String> {
    if helper_active() {
        return Ok(());
    }
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let token = crate::state::random_hex(16);
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let args = format!("--input-helper {port} {token}");
    let result = unsafe {
        ShellExecuteW(None, w!("runas"), &HSTRING::from(exe.as_os_str()), &HSTRING::from(args), None, SW_HIDE)
    };
    if result.0 as isize <= 32 {
        return Err(crate::state::tr(
            "没有获得管理员授权（可能在确认框里点了「否」）",
            "Administrator permission was not granted (\"No\" may have been clicked)",
        )
        .into());
    }
    listener.set_nonblocking(true).map_err(|e| e.to_string())?;
    let deadline = Instant::now() + Duration::from_secs(60);
    while Instant::now() < deadline {
        match listener.accept() {
            Ok((stream, _)) => {
                let _ = stream.set_nonblocking(false);
                let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
                let mut line = String::new();
                if BufReader::new(stream.try_clone().map_err(|e| e.to_string())?).read_line(&mut line).is_ok()
                    && line.trim() == token
                {
                    let _ = stream.set_nodelay(true);
                    *HELPER.lock().unwrap() = Some(stream);
                    return Ok(());
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(100)),
            Err(e) => return Err(e.to_string()),
        }
    }
    Err(crate::state::tr("等待授权超时", "Timed out waiting for permission").into())
}

/// 处理手机发来的一批操作：助手在就交给助手（它能操作管理员窗口），否则自己执行。
pub fn handle_json(raw: &str) -> Result<(), String> {
    {
        let mut guard = HELPER.lock().unwrap();
        if let Some(stream) = guard.as_mut() {
            let (x, y, w, h) = *RECT.lock().unwrap();
            let line = format!("{{\"rect\":[{x},{y},{w},{h}],\"events\":{}}}\n", raw.trim());
            if stream.write_all(line.as_bytes()).is_ok() {
                return Ok(());
            }
            *guard = None; // 助手已退出，回到普通权限
        }
    }
    let events: Vec<Event> = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    handle(events);
    Ok(())
}

#[derive(Deserialize)]
struct HelperMessage {
    rect: (i32, i32, u32, u32),
    events: Vec<Event>,
}

/// 输入助手进程的主循环：主程序退出（连接断开）时随之退出。
pub fn run_helper(port: u16, token: &str) {
    let Ok(mut stream) = TcpStream::connect(("127.0.0.1", port)) else { return };
    if stream.write_all(format!("{token}\n").as_bytes()).is_err() {
        return;
    }
    for line in BufReader::new(stream).lines() {
        let Ok(line) = line else { break };
        if let Ok(msg) = serde_json::from_str::<HelperMessage>(&line) {
            *RECT.lock().unwrap() = msg.rect;
            handle(msg.events);
        }
    }
}

/// 当前前台窗口是否属于管理员权限的程序（普通权限下点不动它）
pub fn foreground_elevated() -> bool {
    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.is_invalid() {
            return false;
        }
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        if pid == 0 || pid == std::process::id() {
            return false;
        }
        let Ok(process) = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) else { return false };
        let mut token = HANDLE::default();
        let elevated = if OpenProcessToken(process, TOKEN_QUERY, &mut token).is_err() {
            true // 普通权限读不到管理员进程的令牌
        } else {
            let mut info = TOKEN_ELEVATION::default();
            let mut len = 0u32;
            let ok = GetTokenInformation(
                token,
                TokenElevation,
                Some(&mut info as *mut _ as *mut std::ffi::c_void),
                std::mem::size_of::<TOKEN_ELEVATION>() as u32,
                &mut len,
            )
            .is_ok();
            let _ = CloseHandle(token);
            ok && info.TokenIsElevated != 0
        };
        let _ = CloseHandle(process);
        elevated
    }
}
