//! 局域网 HTTP 服务 + UDP 发现。接口与安卓 App、网页版保持一致。

use crate::state::{list_dir, random_hex, resolve_shared, safe_name, unique_path, AppState, Device, DISCOVERY_PORT, HTTP_PORT};
use axum::body::Body;
use axum::extract::{ConnectInfo, DefaultBodyLimit, Path, Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::StreamExt;
use percent_encoding::{percent_decode_str, utf8_percent_encode, NON_ALPHANUMERIC};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::SeekFrom;
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio_util::io::ReaderStream;

type St = Arc<AppState>;
type Q = Query<HashMap<String, String>>;

const INDEX_HTML: &str = include_str!("../../web/index.html");

pub fn start(listener: std::net::TcpListener, st: St) {
    let app = Router::new()
        .route("/", get(index))
        .route("/api/hello", get(hello))
        .route("/app.apk", get(app_apk))
        .route("/api/show", get(show))
        .route("/api/info", get(info))
        .route("/api/files", get(files))
        .route("/api/texts", get(get_texts).post(post_text))
        .route("/api/messages", get(messages))
        .route("/api/upload", post(upload))
        .route("/api/delete", post(delete))
        .route("/api/open-folder", post(open_folder))
        .route("/api/pair", post(pair))
        .route("/dl/{name}", get(download))
        .route("/api/mirror/push", post(mirror_push))
        .route("/api/mirror/view", get(mirror_view))
        .route("/api/mirror/control", get(mirror_control))
        .route("/api/remote/info", get(remote_info))
        .route("/api/remote/stream", get(remote_stream))
        .route("/api/remote/input", post(remote_input))
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024)) // 只影响 JSON；上传走流式 Body，不受限制
        .layer(axum::middleware::from_fn(lang_scope))
        .with_state(st.clone());

    tauri::async_runtime::spawn(async move {
        listener.set_nonblocking(true).expect("set_nonblocking");
        let listener = tokio::net::TcpListener::from_std(listener).expect("tokio listener");
        let _ = axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await;
    });

    std::thread::spawn(move || discovery_loop(st));
}

/// 应答 App 的 UDP 广播，让手机无需输入 IP 就能找到电脑。
fn discovery_loop(st: St) {
    let Ok(sock) = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)) else { return };
    let mut buf = [0u8; 1024];
    loop {
        let Ok((n, addr)) = sock.recv_from(&mut buf) else {
            std::thread::sleep(Duration::from_millis(500));
            continue;
        };
        if buf[..n].trim_ascii() == b"FT_DISCOVER" {
            let reply = json!({"app": "ft", "name": st.name, "id": st.id(), "port": HTTP_PORT});
            let _ = sock.send_to(reply.to_string().as_bytes(), addr);
        }
    }
}

struct Ctx {
    local: bool,
    authed: bool,
    device: String,
}

fn header_str<'a>(headers: &'a HeaderMap, name: &str) -> &'a str {
    headers.get(name).and_then(|v| v.to_str().ok()).unwrap_or("")
}

fn ctx(st: &AppState, addr: SocketAddr, headers: &HeaderMap, q: &HashMap<String, String>) -> Ctx {
    let ip = addr.ip();
    let local = ip.is_loopback();
    let key = st.key();
    let cookie_ok = header_str(headers, "cookie")
        .split(';')
        .any(|p| p.trim().strip_prefix("ft_key=") == Some(key.as_str()));
    let authed = local || header_str(headers, "x-key") == key || q.get("k") == Some(&key) || cookie_ok;

    let raw = header_str(headers, "x-device");
    let device: String = percent_decode_str(raw).decode_utf8_lossy().trim().chars().take(40).collect();
    if authed && !local && !device.is_empty() {
        st.devices.lock().unwrap().insert(device.clone(), Device { ip, last_seen: Instant::now() });
    }
    let device = if !device.is_empty() {
        device
    } else if local {
        l("电脑", "PC").into()
    } else {
        l("手机浏览器", "Phone browser").into()
    };
    Ctx { local, authed, device }
}

fn side(c: &Ctx) -> &'static str {
    if c.local { "pc" } else { "phone" }
}

tokio::task_local! {
    /// 这次请求要用英文回复（看 App 发来的 X-Lang，或浏览器的 Accept-Language）
    static REQ_EN: bool;
}

async fn lang_scope(req: axum::extract::Request, next: axum::middleware::Next) -> Response {
    let h = req.headers();
    let lang = h.get("x-lang").or_else(|| h.get(header::ACCEPT_LANGUAGE)).and_then(|v| v.to_str().ok()).unwrap_or("");
    let en = if lang.trim().is_empty() { crate::state::is_en() } else { !lang.trim().to_ascii_lowercase().starts_with("zh") };
    REQ_EN.scope(en, next.run(req)).await
}

/// 按请求方的语言二选一
fn l(zh: &'static str, en: &'static str) -> &'static str {
    if REQ_EN.try_with(|v| *v).unwrap_or_else(|_| crate::state::is_en()) { en } else { zh }
}

fn err(code: StatusCode, msg: &str) -> Response {
    (code, Json(json!({ "error": msg }))).into_response()
}

fn forbidden() -> Response {
    (
        StatusCode::FORBIDDEN,
        Html(format!(
            "<meta name=viewport content='width=device-width'><h2 style='font-family:sans-serif;padding:24px'>{}</h2>",
            l("请用电脑上显示的二维码重新扫码访问", "Please scan the QR code shown on the PC again")
        )),
    )
        .into_response()
}

async fn index(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    let c = ctx(&st, addr, &headers, &q);
    if !c.authed {
        return forbidden();
    }
    let mut res = Html(INDEX_HTML).into_response();
    res.headers_mut().insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    if q.get("k") == Some(&st.key()) {
        // 扫码进来后记住密钥，之后刷新页面无需再带参数
        let cookie = format!("ft_key={}; Path=/; Max-Age=31536000; SameSite=Lax", st.key());
        res.headers_mut().insert(header::SET_COOKIE, HeaderValue::from_str(&cookie).unwrap());
    }
    res
}

pub const APP_APK: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/app.apk"));

/// 手机 App 安装包：不需要配对就能下载，方便第一次安装
async fn app_apk() -> Response {
    if APP_APK.is_empty() {
        return err(StatusCode::NOT_FOUND, l("这个版本没有内置手机 App", "This build doesn't include the Android app"));
    }
    Response::builder()
        .header(header::CONTENT_TYPE, "application/vnd.android.package-archive")
        .header(header::CONTENT_DISPOSITION, "attachment; filename=\"FileTransfer.apk\"")
        .header(header::CONTENT_LENGTH, APP_APK.len())
        .body(Body::from(APP_APK))
        .unwrap()
}

async fn hello(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    let c = ctx(&st, addr, &headers, &q);
    Json(json!({"app": "ft", "name": st.name, "id": st.id(), "paired": c.authed})).into_response()
}

/// 第二次启动程序时，用它把已运行的窗口叫到前台。
async fn show(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>) -> Response {
    if !addr.ip().is_loopback() {
        return forbidden();
    }
    st.show_window();
    Json(json!({"app": "ft", "ok": true})).into_response()
}

async fn info(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    let c = ctx(&st, addr, &headers, &q);
    if !c.authed {
        return forbidden();
    }
    let urls: Vec<String> = crate::state::lan_ips()
        .iter()
        .map(|ip| format!("http://{ip}:{HTTP_PORT}/?k={}", st.key()))
        .collect();
    Json(json!({"urls": urls, "isLocal": c.local, "dir": st.dir(), "name": st.name, "id": st.id()})).into_response()
}

async fn files(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    if !ctx(&st, addr, &headers, &q).authed {
        return forbidden();
    }
    Json(list_dir(&st.dir())).into_response()
}

async fn get_texts(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    if !ctx(&st, addr, &headers, &q).authed {
        return forbidden();
    }
    // 网页版沿用旧格式：只要文字，新的在前
    let texts: Vec<Value> = st
        .messages
        .lock()
        .unwrap()
        .iter()
        .rev()
        .filter(|m| m.kind == "text")
        .take(50)
        .map(|m| json!({"id": m.seq.to_string(), "text": m.text, "time": m.time, "from": m.from}))
        .collect();
    Json(texts).into_response()
}

/// 聊天记录增量拉取：?after=上次拿到的最大 seq
async fn messages(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    if !ctx(&st, addr, &headers, &q).authed {
        return forbidden();
    }
    let after = q.get("after").and_then(|a| a.parse().ok()).unwrap_or(0);
    // 手机后台服务用长轮询：没有新消息就挂起等待，最多 wait 秒，省电又及时
    let wait: u64 = q.get("wait").and_then(|w| w.parse().ok()).unwrap_or(0).min(55);
    if wait > 0 {
        let notified = st.new_message.notified();
        tokio::pin!(notified);
        notified.as_mut().enable();
        if st.messages_after(after, 1).is_empty() {
            let _ = tokio::time::timeout(Duration::from_secs(wait), notified).await;
        }
    }
    Json(st.messages_after(after, 300)).into_response()
}

async fn post_text(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q, Json(body): Json<Value>) -> Response {
    let c = ctx(&st, addr, &headers, &q);
    if !c.authed {
        return forbidden();
    }
    let text = body.get("text").and_then(Value::as_str).unwrap_or("").trim();
    if text.is_empty() {
        return err(StatusCode::BAD_REQUEST, l("内容为空", "Message is empty"));
    }
    let msg = st.add_text(text, side(&c), &c.device);
    Json(st.message_json(&msg)).into_response()
}

async fn delete(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q, Json(body): Json<Value>) -> Response {
    if !ctx(&st, addr, &headers, &q).authed {
        return forbidden();
    }
    let name = body.get("name").and_then(Value::as_str).unwrap_or("");
    let Some(path) = resolve_shared(&st.dir(), name) else {
        return err(StatusCode::NOT_FOUND, l("文件不存在", "File not found"));
    };
    // 手机端删除也放进回收站，误删可以找回
    if trash::delete(&path).is_err() && std::fs::remove_file(&path).is_err() {
        return err(StatusCode::INTERNAL_SERVER_ERROR, l("删除失败", "Delete failed"));
    }
    st.emit("files-changed", ());
    Json(json!({"ok": true})).into_response()
}

async fn open_folder(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>) -> Response {
    if !addr.ip().is_loopback() {
        return err(StatusCode::FORBIDDEN, l("仅限电脑端", "PC only"));
    }
    let _ = open::that_detached(st.dir());
    Json(json!({"ok": true})).into_response()
}

async fn pair(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, Json(body): Json<Value>) -> Response {
    let ip: IpAddr = addr.ip();
    let device: String = body.get("device").and_then(Value::as_str).unwrap_or("").trim().chars().take(40).collect();
    let device = if device.is_empty() { l("未知设备", "Unknown device").to_string() } else { device };

    if st.pair_rejected.lock().unwrap().get(&ip).is_some_and(|t| t.elapsed() < Duration::from_secs(10)) {
        return err(StatusCode::FORBIDDEN, l("电脑拒绝了配对，请稍后再试", "The PC declined pairing. Try again later"));
    }
    let rx = {
        let mut pending = st.pending_pair.lock().unwrap();
        if pending.is_some() {
            return err(StatusCode::TOO_MANY_REQUESTS, l("电脑上已有待确认的配对请求", "Another pairing request is waiting on the PC"));
        }
        let (tx, rx) = tokio::sync::oneshot::channel();
        *pending = Some(tx);
        rx
    };
    st.show_window();
    st.emit("pair-request", json!({"device": device, "ip": ip.to_string()}));

    let allowed = matches!(tokio::time::timeout(Duration::from_secs(60), rx).await, Ok(Ok(true)));
    st.pending_pair.lock().unwrap().take();
    st.emit("pair-closed", ());
    if !allowed {
        st.pair_rejected.lock().unwrap().insert(ip, Instant::now());
        return err(StatusCode::FORBIDDEN, l("电脑拒绝了配对", "The PC declined pairing"));
    }
    st.devices.lock().unwrap().insert(device.clone(), Device { ip, last_seen: Instant::now() });
    st.emit("paired", json!({"device": device}));
    Json(json!({"key": st.key(), "name": st.name, "id": st.id()})).into_response()
}

async fn upload(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q, body: Body) -> Response {
    let c = ctx(&st, addr, &headers, &q);
    if !c.authed {
        return forbidden();
    }
    let name = safe_name(q.get("name").map(String::as_str).unwrap_or("upload"));
    let total: Option<u64> = header_str(&headers, "content-length").parse().ok();
    let dir = st.dir();
    let tmp = dir.join(format!(".{}.{}.part", name, random_hex(4)));
    let id = random_hex(6);
    let progress = |state: &str, done: u64, name: &str| {
        st.emit("transfer", json!({"id": id, "name": name, "done": done, "total": total, "from": c.device, "state": state}));
    };

    let mut file = match tokio::fs::File::create(&tmp).await {
        Ok(f) => f,
        Err(e) => return err(StatusCode::INTERNAL_SERVER_ERROR, &format!("{}{e}", l("无法写入接收文件夹：", "Can't write to the receiving folder: "))),
    };
    progress("start", 0, &name);
    let mut stream = body.into_data_stream();
    let mut done = 0u64;
    let mut last = Instant::now();
    let mut failed = false;
    while let Some(chunk) = stream.next().await {
        match chunk {
            Ok(bytes) => {
                if file.write_all(&bytes).await.is_err() {
                    failed = true;
                    break;
                }
                done += bytes.len() as u64;
                if last.elapsed() > Duration::from_millis(150) {
                    last = Instant::now();
                    progress("progress", done, &name);
                }
            }
            Err(_) => {
                failed = true;
                break;
            }
        }
    }
    let _ = file.flush().await;
    drop(file);
    if failed || total.is_some_and(|t| t != done) {
        let _ = tokio::fs::remove_file(&tmp).await;
        progress("failed", done, &name);
        return err(StatusCode::BAD_REQUEST, l("传输中断", "Transfer interrupted"));
    }
    let dest = unique_path(&dir, &name); // 上传期间可能出现同名文件，最后再定名字
    if let Err(e) = tokio::fs::rename(&tmp, &dest).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        progress("failed", done, &name);
        return err(StatusCode::INTERNAL_SERVER_ERROR, &format!("{}{e}", l("保存失败：", "Save failed: ")));
    }
    let final_name = dest.file_name().unwrap().to_string_lossy().into_owned();
    let msg = st.add_file(&final_name, done, side(&c), &c.device);
    progress("done", done, &final_name);
    st.emit("files-changed", ());
    Json(json!({"name": final_name, "size": done, "seq": msg.seq})).into_response()
}

async fn download(
    State(st): State<St>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Path(name): Path<String>,
    Query(q): Q,
) -> Response {
    if !ctx(&st, addr, &headers, &q).authed {
        return forbidden();
    }
    let Some(path) = resolve_shared(&st.dir(), &name) else {
        return err(StatusCode::NOT_FOUND, l("文件不存在", "File not found"));
    };
    let Ok(mut file) = tokio::fs::File::open(&path).await else {
        return err(StatusCode::NOT_FOUND, l("文件无法读取", "Can't read the file"));
    };
    let size = file.metadata().await.map(|m| m.len()).unwrap_or(0);

    let (mut start, mut end, mut partial) = (0u64, size.saturating_sub(1), false);
    if let Some(range) = header_str(&headers, "range").strip_prefix("bytes=") {
        if size > 0 {
            let first = range.split(',').next().unwrap_or("");
            let (s, e) = first.split_once('-').unwrap_or(("", ""));
            let parsed = match (s.trim().parse::<u64>().ok(), e.trim().parse::<u64>().ok()) {
                (Some(s), Some(e)) => Some((s, e.min(size - 1))),
                (Some(s), None) => Some((s, size - 1)),
                (None, Some(n)) => Some((size.saturating_sub(n), size - 1)),
                _ => None,
            };
            match parsed {
                Some((s, e)) if s <= e => {
                    (start, end, partial) = (s, e, true);
                }
                _ => {
                    return Response::builder()
                        .status(StatusCode::RANGE_NOT_SATISFIABLE)
                        .header(header::CONTENT_RANGE, format!("bytes */{size}"))
                        .body(Body::empty())
                        .unwrap();
                }
            }
        }
    }
    let len = if size == 0 { 0 } else { end - start + 1 };
    if start > 0 && file.seek(SeekFrom::Start(start)).await.is_err() {
        return err(StatusCode::INTERNAL_SERVER_ERROR, l("读取失败", "Read failed"));
    }
    let file_name = path.file_name().unwrap().to_string_lossy().into_owned();
    let mime = mime_guess::from_path(&path).first_or_octet_stream();
    let disposition = if q.contains_key("inline") { "inline" } else { "attachment" };
    let mut res = Response::builder()
        .status(if partial { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK })
        .header(header::CONTENT_TYPE, mime.as_ref())
        .header(header::CONTENT_LENGTH, len)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(
            header::CONTENT_DISPOSITION,
            format!("{disposition}; filename*=UTF-8''{}", utf8_percent_encode(&file_name, NON_ALPHANUMERIC)),
        );
    if partial {
        res = res.header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{size}"));
    }
    let stream = ReaderStream::with_capacity(file.take(len), 256 * 1024);
    res.body(Body::from_stream(stream)).unwrap()
}

// ---------- 远程控制 ----------

async fn remote_info(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    if !ctx(&st, addr, &headers, &q).authed {
        return forbidden();
    }
    let monitors = tauri::async_runtime::spawn_blocking(crate::remote::monitors).await.unwrap_or_default();
    Json(json!({"enabled": st.remote_enabled(), "monitors": monitors})).into_response()
}

async fn remote_stream(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    let c = ctx(&st, addr, &headers, &q);
    if !c.authed {
        return forbidden();
    }
    if !st.remote_enabled() {
        return err(StatusCode::FORBIDDEN, l(
            "电脑上还没有开启远程控制：请在电脑客户端「设置 → 远程控制」里打开",
            "Remote control is off on the PC. Turn it on under Settings → Remote control in the PC app",
        ));
    }
    let index: usize = q.get("monitor").and_then(|m| m.parse().ok()).unwrap_or(usize::MAX);
    let quality = q.get("q").cloned().unwrap_or_default();
    let (tx, rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2);
    let st2 = st.clone();
    let device = c.device.clone();
    std::thread::spawn(move || {
        let _guard = crate::remote::StreamGuard::new(|active| {
            st2.emit("remote-session", json!({"active": active, "device": device}));
        });
        crate::remote::stream(index, &quality, tx);
    });
    let body = futures_util::stream::unfold(rx, |mut rx| async move {
        rx.recv().await.map(|frame| (Ok::<_, std::convert::Infallible>(frame), rx))
    });
    Response::builder()
        .header(header::CONTENT_TYPE, "application/octet-stream")
        .header(header::CACHE_CONTROL, "no-store")
        .body(Body::from_stream(body))
        .unwrap()
}

async fn remote_input(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q, body: axum::body::Bytes) -> Response {
    if !ctx(&st, addr, &headers, &q).authed {
        return forbidden();
    }
    if !st.remote_enabled() {
        return err(StatusCode::FORBIDDEN, l("电脑上已关闭远程控制", "Remote control was turned off on the PC"));
    }
    let raw = String::from_utf8_lossy(&body).into_owned();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let r = crate::remote::handle_json(&raw);
        // 前台是管理员程序、又没有授权助手时，操作会被 Windows 丢掉，告诉两边
        let blocked = !crate::remote::helper_active() && crate::remote::foreground_elevated();
        (r, blocked)
    })
    .await;
    let (r, blocked) = result.unwrap_or((Ok(()), false));
    if let Err(e) = r {
        return err(StatusCode::BAD_REQUEST, &e);
    }
    if blocked {
        st.emit("remote-blocked", ());
    }
    Json(json!({"ok": true, "blocked": blocked})).into_response()
}

// ---------- 手机投屏到电脑 ----------

static MIRROR_SESSION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// 手机推画面：请求体一直写到停止投屏为止
async fn mirror_push(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q, body: Body) -> Response {
    let c = ctx(&st, addr, &headers, &q);
    if !c.authed {
        return forbidden();
    }
    let session = MIRROR_SESSION.fetch_add(1, std::sync::atomic::Ordering::SeqCst) + 1;
    *st.mirror.device.lock().unwrap() = Some(c.device.clone());
    st.emit("mirror", json!({"active": true, "device": c.device}));
    let mut parser = crate::mirror::FrameParser::new();
    let mut stream = body.into_data_stream();
    while let Some(Ok(chunk)) = stream.next().await {
        for frame in parser.push(&chunk) {
            st.mirror.frames.send_replace(Some(frame));
        }
    }
    // 只有最新的一次投屏结束时才清空（手机重连时旧连接晚一点断开不影响新画面）
    if MIRROR_SESSION.load(std::sync::atomic::Ordering::SeqCst) == session {
        st.mirror.frames.send_replace(None);
        st.emit("mirror", json!({"active": false}));
    }
    Json(json!({"ok": true})).into_response()
}

/// 电脑界面看画面：MJPEG，<img> 可以直接播放。只允许本机访问
async fn mirror_view(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>) -> Response {
    if !addr.ip().is_loopback() {
        return forbidden();
    }
    let mut rx = st.mirror.frames.subscribe();
    rx.mark_changed();
    let body = futures_util::stream::unfold(rx, |mut rx| async move {
        loop {
            if rx.changed().await.is_err() {
                return None;
            }
            let frame = rx.borrow_and_update().clone();
            if let Some(f) = frame {
                let mut part = format!("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\n\r\n", f.len()).into_bytes();
                part.extend_from_slice(&f);
                part.extend_from_slice(b"\r\n");
                return Some((Ok::<_, std::convert::Infallible>(part), rx));
            }
        }
    });
    Response::builder()
        .header(header::CONTENT_TYPE, "multipart/x-mixed-replace; boundary=frame")
        .header(header::CACHE_CONTROL, "no-store")
        .body(Body::from_stream(body))
        .unwrap()
}

/// 手机接收电脑的操作：每行一个 JSON；空行是心跳，用来及时发现断线
async fn mirror_control(State(st): State<St>, ConnectInfo(addr): ConnectInfo<SocketAddr>, headers: HeaderMap, Query(q): Q) -> Response {
    if !ctx(&st, addr, &headers, &q).authed {
        return forbidden();
    }
    let rx = st.mirror.attach_control();
    st.emit("mirror-control", json!({"connected": true}));
    let body = futures_util::stream::unfold(rx, |mut rx| async move {
        tokio::select! {
            msg = rx.recv() => msg.map(|m| (Ok::<_, std::convert::Infallible>(format!("{m}\n").into_bytes()), rx)),
            _ = tokio::time::sleep(Duration::from_secs(10)) => Some((Ok(b"\n".to_vec()), rx)),
        }
    });
    Response::builder()
        .header(header::CONTENT_TYPE, "application/x-ndjson")
        .header(header::CACHE_CONTROL, "no-store")
        .body(Body::from_stream(body))
        .unwrap()
}
