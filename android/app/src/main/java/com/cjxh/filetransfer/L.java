package com.cjxh.filetransfer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 界面文字中英对照，以中文原文作 key：中文系统原样显示，其他语言显示英文。
 * 跟随系统语言，也支持 Android 13 起的「应用语言」单独设置。
 */
final class L {
    private static final Map<String, String> M = new HashMap<>();

    static {
        M.put("电脑不在线，连上后才能控制", "PC is offline. Connect first to control it");
        M.put("未连接电脑", "No PC connected");
        M.put("点这里查找电脑", "Tap here to find your PC");
        M.put("已连接", "Connected");
        M.put("电脑不在线", "PC offline");
        M.put("已连接 · ", "Connected · ");
        M.put("不在线 · 请确认电脑上打开了「文件互传」，并且在同一个 Wi-Fi", "Offline · Make sure LanDrop is open on the PC and both are on the same Wi-Fi");
        M.put("重新连接", "Reconnect");
        M.put("正在重新连接…", "Reconnecting…");
        M.put("后台消息通知", "Background notifications");
        M.put("App 没打开时，电脑发来消息也会提醒你", "Get notified of messages from your PC even when the app is closed");
        M.put("停止投屏", "Stop casting");
        M.put("投屏到电脑", "Cast to PC");
        M.put("电脑上正在显示这台手机的屏幕", "Your PC is showing this phone's screen");
        M.put("在电脑上查看和操作这台手机", "View and control this phone from your PC");
        M.put("连接其他电脑", "Connect to another PC");
        M.put("断开并忘记这台电脑", "Disconnect and forget this PC");
        M.put("之后需要重新配对", "You'll need to pair again");
        M.put("忘记「%s」？", "Forget \"%s\"?");
        M.put("断开后不再自动连接这台电脑，下次需要在电脑上重新允许。", "The app will stop connecting to this PC automatically. Next time you'll need to allow it on the PC again.");
        M.put("取消", "Cancel");
        M.put("断开", "Disconnect");
        M.put("还没有连接电脑", "No PC connected yet");
        M.put("连接电脑", "Connect to PC");
        M.put("手机和电脑连在同一个 Wi-Fi，电脑上打开「文件互传」", "Put your phone and PC on the same Wi-Fi and open LanDrop on the PC");
        M.put("输入 IP", "Enter IP");
        M.put("重新查找", "Search again");
        M.put("正在查找附近的电脑…", "Looking for nearby PCs…");
        M.put("当前", "Current");
        M.put("连接", "Connect");
        M.put("没有找到电脑", "No PC found");
        M.put("· 电脑上已打开「文件互传」\n· 手机和电脑连的是同一个 Wi-Fi / 路由器\n· 电脑第一次运行时，防火墙弹窗里允许了「专用网络」\n还是不行的话，点「输入 IP」，地址在电脑端「设置 → 本机地址」", "· LanDrop is open on the PC\n· Phone and PC are on the same Wi-Fi / router\n· On the PC's first run, \"Private networks\" was allowed in the firewall prompt\nStill stuck? Tap \"Enter IP\" — the address is under Settings → This PC's address in the PC app");
        M.put("例如 192.168.1.5", "e.g. 192.168.1.5");
        M.put("输入电脑地址", "Enter PC address");
        M.put("在电脑端「设置 → 本机地址」里可以看到", "Find it under Settings → This PC's address in the PC app");
        M.put("连接不上 ", "Can't connect to ");
        M.put("请确认地址正确、电脑上打开了「文件互传」，并且手机和电脑在同一个 Wi-Fi。", "Check the address, make sure LanDrop is open on the PC, and that your phone and PC are on the same Wi-Fi.");
        M.put("等待电脑确认", "Waiting for the PC");
        M.put("请在电脑「%s」弹出的窗口里点「允许连接」。", "Click \"Allow\" in the window that popped up on \"%s\".");
        M.put("已连接「%s」", "Connected to \"%s\"");
        M.put("连接失败", "Connection failed");
        M.put("好", "OK");
        M.put("电脑不在线，连上后才能投屏", "PC is offline. Connect first to cast");
        M.put("让电脑也能操作手机？", "Let the PC control your phone?");
        M.put("投屏后电脑上能看到手机画面。想在电脑上用鼠标点击、滑动、打字，需要在系统「无障碍」里打开「文件互传 · 电脑操作手机」。\n\n小米、华为等手机如果提示「受限设置」，先到 设置 → 应用 → 文件互传 → 右上角菜单 → 允许受限制的设置。", "Once casting, the PC can see your screen. To click, swipe and type from the PC, turn on \"LanDrop · PC controls phone\" under Accessibility.\n\nIf your phone says \"Restricted setting\", first go to Settings → Apps → LanDrop → ⋮ menu → Allow restricted settings.");
        M.put("只投屏", "Just cast");
        M.put("去开启", "Turn on");
        M.put("找到「文件互传 · 电脑操作手机」并打开", "Find \"LanDrop · PC controls phone\" and turn it on");
        M.put("电脑不在线，打开电脑端后会自动连接", "PC is offline. The app reconnects once LanDrop is running on the PC");
        M.put("正在加载聊天记录…", "Loading messages…");
        M.put("连接电脑后，在这里收发文字、图片和文件", "Once connected, send and receive text, photos and files here");
        M.put("还没有消息\n发文字、图片或文件，电脑上会立刻收到", "No messages yet\nSend text, photos or files and they'll show up on your PC right away");
        M.put("已删除或移走", "Deleted or moved");
        M.put("已下载", "Downloaded");
        M.put("点击下载", "Tap to download");
        M.put(" · 已下载", " · Downloaded");
        M.put("没发出去：", "Not sent: ");
        M.put("保存到手机", "Save to phone");
        M.put("已开始保存", "Saving…");
        M.put("电脑接收文件夹是空的", "The PC's receiving folder is empty");
        M.put("电脑接收文件夹 · %d 个文件 · 长按可删除", "PC receiving folder · %d files · long-press to delete");
        M.put("来自电脑「%s」", "From PC \"%s\"");
        M.put("正在下载到 Download/%s", "Downloading to Download/%s");
        M.put("删除电脑上的文件？", "Delete file on PC?");
        M.put("\n文件会移到电脑的回收站，需要时可以还原。", "\nThe file goes to the PC's Recycle Bin, so you can restore it if needed.");
        M.put("删除", "Delete");
        M.put("删除失败：", "Delete failed: ");
        M.put("选择要发送的照片或文件", "Choose photos or files to send");
        M.put("开始投屏，电脑上可以查看和操作手机", "Casting — the PC can now view and control your phone");
        M.put("开始投屏，电脑上点「手机」查看", "Casting — open the Phone tab on your PC to watch");
        M.put("文件互传", "LanDrop");
        M.put("已复制", "Copied");
        M.put("昨天 ", "Yesterday ");
        M.put("正在投屏到电脑「%s」", "Casting to \"%s\"");
        M.put("电脑可以查看和操作这台手机", "The PC can view and control this phone");
        M.put("电脑可以查看这台手机的屏幕", "The PC can view this phone's screen");
        M.put("电脑返回了无法识别的数据", "The PC sent data the app doesn't understand");
        M.put("数据格式错误", "Invalid data format");
        M.put("配对已失效，请重新连接电脑", "Pairing expired. Please connect to the PC again");
        M.put("后台接收", "Background receiving");
        M.put("保持和电脑的连接，用来及时收到消息。可以在系统设置里隐藏这条通知", "Keeps the connection to your PC so messages arrive right away. You can hide this notification in system settings");
        M.put("电脑发来的消息", "Messages from PC");
        M.put("电脑发来文字、图片或文件时提醒", "Notifies you when your PC sends text, photos or files");
        M.put("文件互传正在后台接收", "LanDrop is receiving in the background");
        M.put("电脑发来消息时会提醒你", "You'll be notified when your PC sends something");
        M.put("[图片] ", "[Photo] ");
        M.put("[文件] ", "[File] ");
        M.put("流畅", "Smooth");
        M.put("高清", "HD");
        M.put("原画", "Original");
        M.put("触控板", "Touchpad");
        M.put("跟手", "Direct");
        M.put("适应屏幕", "Fit screen");
        M.put("键盘", "Keyboard");
        M.put("退格", "Backspace");
        M.put("回车", "Enter");
        M.put("屏幕", "Screen");
        M.put("退出", "Exit");
        M.put("工具", "Tools");
        M.put("收起", "Hide");
        M.put("触控板模式：单指滑动移动鼠标 · 轻点 = 左键 · 双指轻点 = 右键 · 点两下按住滑动 = 拖动 · 双指上下滑 = 滚动 · 双指捏合 = 缩放画面，放大后双指拖动画面", "Touchpad mode: slide one finger to move the mouse · tap = left click · two-finger tap = right click · double-tap and hold to drag · two-finger swipe = scroll · pinch to zoom, then drag with two fingers to pan");
        M.put("跟手模式：按到哪鼠标就到哪 · 轻点 = 左键 · 长按后滑动 = 拖动 · 长按松开 / 双指轻点 = 右键 · 双指捏合 = 缩放画面，放大后双指拖动画面", "Direct mode: the mouse goes where you touch · tap = left click · long-press then slide = drag · long-press and release / two-finger tap = right click · pinch to zoom, then drag with two fingers to pan");
        M.put("画面清晰度", "Picture quality");
        M.put("越清晰，每秒画面越少；局域网信号差时选「流畅」", "Sharper means fewer frames per second. Pick \"Smooth\" on a weak Wi-Fi signal");
        M.put("1280 宽 · 帧率最高", "1280 wide · highest frame rate");
        M.put("1920 宽 · 推荐", "1920 wide · recommended");
        M.put("原始分辨率 · 最清楚，帧率较低", "Native resolution · sharpest, lower frame rate");
        M.put("（当前）", " (current)");
        M.put("正在连接电脑画面…", "Connecting to the PC screen…");
        M.put("连接中断", "Connection lost");
        M.put("画面已断开\n%s\n\n点屏幕重新连接", "Disconnected\n%s\n\nTap the screen to reconnect");
        M.put("这个窗口是管理员权限的程序，需要在电脑上「设置 → 远程控制」点「授权」后才能操作", "This window belongs to an admin-level program. Click Authorize under Settings → Remote control on the PC to control it");
        M.put("放大 %d%%", "Zoom %d%%");
        M.put("没有可发送的内容", "Nothing to send");
        M.put("正在连接「%s」…", "Connecting to \"%s\"…");
        M.put("找不到电脑「%s」", "Can't find PC \"%s\"");
        M.put("请确认电脑上的「文件互传」客户端正在运行，并且手机和电脑在同一个 Wi-Fi。", "Make sure LanDrop is running on the PC and that your phone and PC are on the same Wi-Fi.");
        M.put("文字已发送到「%s」", "Text sent to \"%s\"");
        M.put("已发送到「%s」", "Sent to \"%s\"");
        M.put("已发送 %d 个文件到「%s」", "Sent %d files to \"%s\"");
        M.put("发送失败", "Send failed");
        M.put("（%d/%d）", " (%d/%d)");
        M.put("正在发送", "Sending");
        M.put("无法读取文件：", "Can't read file: ");
        M.put("发送到电脑", "Send to PC");
        M.put("发送成功", "Sent");
        M.put("完成", "Done");
        M.put("没有发送成功", "Couldn't send");
        M.put("关闭", "Close");
        M.put("重试", "Retry");
        M.put("正在重试…", "Retrying…");
        M.put("请先打开「文件互传」App，查找并连接电脑，之后就能从任意 App 直接分享过来。", "Open the LanDrop app first and connect to your PC. Then you can share from any app.");
        M.put("打开 App", "Open app");
    }

    private L() {}

    /** 每次现取：切换应用语言后 Activity 重建，默认 Locale 也会跟着变 */
    static boolean en() {
        return !"zh".equals(Locale.getDefault().getLanguage());
    }

    static String t(String zh) {
        if (!en()) return zh;
        String s = M.get(zh);
        return s == null ? zh : s;
    }

    static String f(String zh, Object... args) {
        return String.format(en() ? Locale.US : Locale.CHINA, t(zh), args);
    }

    /** 手机上保存电脑文件的文件夹（在 Download 下） */
    static String downloadFolder() {
        return en() ? "LanDrop" : "手机互传";
    }

    /** 告诉电脑用哪种语言回复错误信息 */
    static String header() {
        return en() ? "en" : "zh";
    }
}
