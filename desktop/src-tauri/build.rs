use std::path::{Path, PathBuf};

fn main() {
    // 把安卓 App 打包进 exe，手机扫电脑上的二维码就能直接安装。
    // 先编译 android 工程的 release 版本；没有的话打一个空文件进去，界面里会隐藏这个功能。
    let apk = Path::new("../../android/app/build/outputs/apk/release/app-release.apk");
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("app.apk");
    if apk.exists() {
        std::fs::copy(apk, &out).expect("复制 APK 失败");
    } else {
        std::fs::write(&out, b"").unwrap();
        println!("cargo:warning=没有找到 {}，exe 里不会内置手机 App", apk.display());
    }
    println!("cargo:rerun-if-changed=../../android/app/build/outputs/apk/release/app-release.apk");
    tauri_build::build()
}
