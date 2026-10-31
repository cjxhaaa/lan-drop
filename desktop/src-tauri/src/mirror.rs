//! 电脑看手机（投屏）：手机把屏幕画面推上来，电脑界面里显示，并把电脑上的鼠标键盘操作发回手机。
//!
//! - 手机 POST /api/mirror/push：请求体是连续的帧，每帧 = [u32 JPEG 长度][JPEG]
//! - 电脑界面 GET /api/mirror/view：multipart/x-mixed-replace（MJPEG），直接用 <img> 显示
//! - 手机 GET /api/mirror/control：一直挂着的响应，电脑的操作按行写成 JSON 发过去

use std::sync::Mutex;
use tokio::sync::{mpsc, watch};

pub struct Mirror {
    /// 最新一帧 JPEG（没有在投屏时为 None）
    pub frames: watch::Sender<Option<Vec<u8>>>,
    /// 发往手机的操作；手机的 control 连接接上时才有
    control: Mutex<Option<mpsc::UnboundedSender<String>>>,
    pub device: Mutex<Option<String>>,
}

impl Mirror {
    pub fn new() -> Self {
        Mirror { frames: watch::channel(None).0, control: Mutex::new(None), device: Mutex::new(None) }
    }

    pub fn active(&self) -> bool {
        self.frames.borrow().is_some()
    }

    /// 手机的操作通道接上了，返回接收端（旧的连接自动作废）
    pub fn attach_control(&self) -> mpsc::UnboundedReceiver<String> {
        let (tx, rx) = mpsc::unbounded_channel();
        *self.control.lock().unwrap() = Some(tx);
        rx
    }

    /// 把一行 JSON 操作发给手机，手机没连着时返回 false
    pub fn send(&self, line: String) -> bool {
        let mut guard = self.control.lock().unwrap();
        match guard.as_ref() {
            Some(tx) if tx.send(line).is_ok() => true,
            _ => {
                *guard = None;
                false
            }
        }
    }

    pub fn can_control(&self) -> bool {
        self.control.lock().unwrap().as_ref().is_some_and(|tx| !tx.is_closed())
    }
}

/// 从上传的字节流里切出完整的帧
pub struct FrameParser {
    buf: Vec<u8>,
}

impl FrameParser {
    pub fn new() -> Self {
        FrameParser { buf: Vec::with_capacity(512 * 1024) }
    }

    pub fn push(&mut self, data: &[u8]) -> Vec<Vec<u8>> {
        self.buf.extend_from_slice(data);
        let mut frames = Vec::new();
        loop {
            if self.buf.len() < 4 {
                break;
            }
            let len = u32::from_be_bytes([self.buf[0], self.buf[1], self.buf[2], self.buf[3]]) as usize;
            if len > 16 * 1024 * 1024 {
                self.buf.clear(); // 数据错乱，丢弃
                break;
            }
            if self.buf.len() < 4 + len {
                break;
            }
            let frame = self.buf[4..4 + len].to_vec();
            self.buf.drain(..4 + len);
            if !frame.is_empty() {
                frames.push(frame);
            }
        }
        frames
    }
}
