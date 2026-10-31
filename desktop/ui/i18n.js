// 界面文字中英对照。以中文原文作 key：中文界面原样显示，英文界面查表替换。
// 动态文字用 t('原文 {变量}', { 变量 }) ；HTML 里的静态文字由 translateDom() 统一替换。
const EN = {
  // 通用
  '文件互传': 'LanDrop',
  '聊天': 'Chat',
  '文件': 'Files',
  '手机': 'Phone',
  '设置': 'Settings',
  '共享文件': 'Shared files',
  '查看和操作手机屏幕': 'View and control your phone',
  '打开': 'Open',
  '更改': 'Change',
  '添加': 'Add',
  '固定': 'Pin',
  '清空': 'Clear',
  '重置': 'Reset',
  '授权': 'Authorize',
  '断开': 'Disconnect',
  '拒绝': 'Decline',
  '取消': 'Cancel',
  '发送': 'Send',
  '复制': 'Copy',
  '刷新': 'Refresh',
  '在线': 'Online',
  '离线': 'Offline',
  '已添加': 'Added',
  '已授权': 'Authorized',
  '已复制': 'Copied',

  // 聊天
  '我的手机': 'My phone',
  '打开接收文件夹': 'Open receiving folder',
  '有新消息': 'New messages',
  '发送文件': 'Send files',
  '发送剪贴板里的文字': 'Send clipboard text',
  '输入消息': 'Type a message',
  'Enter 发送，Shift+Enter 换行。可以直接拖入文件，或 Ctrl+V 粘贴截图': 'Enter to send, Shift+Enter for a new line. Drop files here or paste screenshots with Ctrl+V',
  '手机和电脑的聊天记录会显示在这里': 'Messages between your phone and PC show up here',
  '在手机任意 App 里点「分享 → 发送到电脑」，或在下面输入文字、拖入文件发给手机。': 'On your phone, tap Share → Send to PC in any app. Or type below and drop files here to send them to your phone.',
  '手机还没连接？在手机上打开「文件互传」App，它会自动找到「{name}」': 'Phone not connected yet? Open the LanDrop app on your phone and it will find "{name}" automatically',
  '；找不到时手动输入 {ip}': '. If it can\'t, enter {ip} manually',
  '这台电脑': 'this PC',
  '手机还没装 App？在「设置 → 给手机安装 App」里扫码下载。': 'No app on your phone yet? Scan the QR code under Settings → Install app on phone.',
  '图片已删除<br>或已移走': 'Image deleted<br>or moved',
  '传输中断': 'Transfer interrupted',
  '已发送 {n} 个文件': 'Sent {n} file(s)',
  '剪贴板里没有文字': 'No text in the clipboard',
  '截图': 'Screenshot',
  '复制选中文字': 'Copy selection',
  '在文件夹中显示': 'Show in folder',
  '删除这条记录': 'Delete this message',
  '没有可发送的文件': 'Nothing to send',
  '，{n} 个文件夹需要先压缩': ' ({n} folder(s) skipped — zip them first)',
  '昨天': 'Yesterday',

  // 文件页
  '搜索文件名': 'Search files',
  '图标': 'Grid',
  '列表': 'List',
  '在资源管理器中打开': 'Open in Explorer',
  '这里就是接收文件夹。拖到桌面或其他文件夹即可取出，把文件拖进来就会发到手机': 'This is your receiving folder. Drag files out to the desktop or any folder; drag files in to send them to your phone',
  '{n} 个文件': '{n} files',
  '接收文件夹是空的': 'The receiving folder is empty',
  '手机发来的文件会出现在这里，也可以把电脑上的文件拖进来': 'Files from your phone appear here. You can also drag files in from your PC',
  '没有找到「{q}」': 'Nothing matches "{q}"',
  '来自 {from}': 'From {from}',
  '在资源管理器中显示': 'Show in Explorer',
  '移到回收站': 'Move to Recycle Bin',
  '移到回收站（{n} 个）': 'Move {n} to Recycle Bin',
  '拖动失败：': 'Drag failed: ',
  '「{name}」': '"{name}"',
  '这 {n} 个文件': 'these {n} files',
  '把{label}移到回收站？手机上也将看不到它们，需要时可以从回收站还原。': 'Move {label} to the Recycle Bin? They will disappear from your phone too. You can restore them from the Recycle Bin.',
  '已移到回收站': 'Moved to Recycle Bin',

  // 手机屏幕
  '手机屏幕': 'Phone screen',
  '在这里查看和操作手机': 'View and control your phone here',
  '在手机 App 里点左上角的电脑 →「投屏到电脑」，手机画面就会显示在这里。': 'In the phone app, tap the PC at the top left → Cast to PC. Your phone screen will show up here.',
  '想用鼠标键盘操作手机，需要在手机的「无障碍」里开启文件互传，App 会带你去设置。': 'To control the phone with mouse and keyboard, turn on LanDrop under Accessibility on the phone. The app will take you there.',
  '手机画面': 'Phone screen',
  '返回': 'Back',
  '主页': 'Home',
  '多任务': 'Recents',
  '单击 = 点按 · 按住拖动 = 滑动 · 按住不动 = 长按 · 右键 = 返回 · 滚轮 = 上下滑 · 直接打字输入到手机': 'Click = tap · Drag = swipe · Hold = long-press · Right-click = Back · Wheel = scroll · Just type to enter text on the phone',
  '输入到手机': 'Type on phone',
  '{device} 正在投屏 · 可以用鼠标键盘操作': '{device} is casting · mouse and keyboard control on',
  '{device} 正在投屏 · 手机没开无障碍，只能查看': '{device} is casting · view only (accessibility is off on the phone)',
  '{device} 开始投屏': '{device} started casting',
  '手机已停止投屏': 'The phone stopped casting',

  // 设备与配对
  '{n} 台手机在线': '{n} phones online',
  '离线 · 在手机上打开 App 即可连接': 'Offline · open the app on your phone to connect',
  '新手机请求连接': 'New phone wants to connect',
  '{device}（{ip}）想要连接这台电脑。允许后，它可以向电脑发送文件和文字，并下载接收文件夹里的文件。': '{device} ({ip}) wants to connect to this PC. Once allowed, it can send files and text to the PC and download files from the receiving folder.',
  '允许连接': 'Allow',
  '已连接 {device}': 'Connected to {device}',

  // 设置
  '接收文件': 'Receiving',
  '接收文件夹': 'Receiving folder',
  '给手机安装 App': 'Install app on phone',
  '用手机扫码下载安装': 'Scan with your phone to install',
  '手机连上同一个 Wi-Fi，用手机浏览器或相机扫右边的二维码，下载后点开安装。微信里扫码无法下载安装包': 'Connect your phone to the same Wi-Fi, scan the QR code with its browser or camera, then open the download to install. (WeChat\'s scanner can\'t download APKs.)',
  '连接方式': 'Connection',
  '本机地址': 'This PC\'s address',
  'App 找不到电脑时，在 App 里手动输入这个地址': 'Enter this in the app if it can\'t find the PC',
  '没装 App 的手机': 'Phones without the app',
  '连同一个 Wi-Fi 后扫右边的二维码，在浏览器里收发文件': 'On the same Wi-Fi, scan the QR code to send and receive files in the browser',
  '未连接局域网': 'Not on a local network',
  '其他地址：': 'Other addresses: ',
  '还没有手机连接': 'No phone connected yet',
  '在手机上打开「文件互传」App，连同一个 Wi-Fi，它会自动找到这台电脑': 'Open the LanDrop app on a phone on the same Wi-Fi and it will find this PC automatically',
  '远程控制': 'Remote control',
  '允许手机控制这台电脑': 'Allow phones to control this PC',
  '已配对的手机可以在 App 里看到电脑屏幕，并用手指操作鼠标和键盘。被控制时窗口顶部会有提示，可以随时断开': 'Paired phones can see this screen and control the mouse and keyboard. A banner shows while you\'re being controlled, and you can disconnect at any time',
  '控制管理员权限的程序': 'Control admin-level programs',
  '游戏、任务管理器等以管理员身份运行的程序，需要额外授权才能被手机操作。点「授权」后 Windows 会弹出一次确认，本次运行期间有效': 'Games, Task Manager and other programs running as administrator need extra permission. Click Authorize and confirm once; it lasts until LanDrop exits',
  '语言 Language': 'Language 语言',
  '界面语言，修改后立即生效': 'Interface language, applied right away',
  '跟随系统': 'Follow system',
  '收到消息时通知': 'Notify on new messages',
  '窗口不在前台时，手机发来的文字和文件会在屏幕右下角弹出通知，点一下打开聊天': 'When the window isn\'t in front, messages from your phone pop up as notifications. Click to open the chat',
  '开机自动启动': 'Start with Windows',
  '在托盘里待命，不打开窗口': 'Waits in the tray without opening the window',
  '右键菜单「发送到 → 手机互传」': 'Right-click "Send to → LanDrop"',
  '在任意文件上右键就能放进接收文件夹。Windows 11 需先点“显示更多选项”': 'Right-click any file to copy it into the receiving folder. On Windows 11, click "Show more options" first',
  '固定到资源管理器「快速访问」': 'Pin to Explorer Quick Access',
  '在资源管理器左侧一点就到，拖进拖出更方便': 'One click away in Explorer\'s sidebar for easy drag and drop',
  '数据与安全': 'Data & security',
  '清空聊天记录': 'Clear chat history',
  '只删除记录，接收文件夹里的文件不受影响': 'Only removes the history; files in the receiving folder stay',
  '重置配对': 'Reset pairing',
  '所有手机需要重新连接，浏览器扫码链接也会失效': 'All phones need to pair again, and browser QR links stop working',
  '关闭窗口后在系统托盘继续接收文件': 'Keeps receiving in the system tray after you close the window',
  '右键任意文件 → 发送到 → 手机互传': 'Right-click any file → Send to → LanDrop',
  '接收文件夹已更改': 'Receiving folder changed',
  '已开启开机自动启动': 'Will start with Windows',
  '已关闭开机自动启动': 'Won\'t start with Windows',
  '请在 Windows 弹出的确认框里点「是」': 'Click "Yes" in the Windows prompt',
  '已授权，手机现在可以操作管理员权限的程序了': 'Authorized — the phone can now control admin-level programs',
  '手机点到的是管理员权限的程序，需要授权才能操作': 'The phone tapped an admin-level program. Authorize it to allow control',
  '已开启消息通知': 'Notifications on',
  '已关闭消息通知': 'Notifications off',
  '已允许手机控制这台电脑': 'Phones can now control this PC',
  '已关闭远程控制': 'Remote control off',
  '{device} 正在控制这台电脑': '{device} is controlling this PC',
  '手机正在控制这台电脑': 'A phone is controlling this PC',
  '授权控制管理员程序': 'Authorize admin programs',
  '已断开远程控制': 'Remote control disconnected',
  '已固定到资源管理器「快速访问」': 'Pinned to Quick Access',
  '电脑和手机上的聊天记录都会被清空。接收文件夹里的文件会保留。': 'Chat history will be cleared on both the PC and the phone. Files in the receiving folder are kept.',
  '聊天记录已清空': 'Chat history cleared',
  '所有已连接的手机都需要重新配对，浏览器扫码链接也会失效。': 'All connected phones will need to pair again, and browser QR links will stop working.',
  '已重置，手机需要重新连接': 'Reset — phones need to connect again',

  // 拖入
  '松开发送到手机': 'Drop to send to your phone',
  '文件会放进接收文件夹，手机上就能下载': 'Files go into the receiving folder, ready to download on your phone',
};

// 语言以电脑端设置为准（get_info 返回），这里先用上次的结果，避免启动时闪一下中文
let LANG = 'zh';
try { if (localStorage.getItem('lang') === 'en') LANG = 'en'; } catch {}
document.documentElement.lang = LANG === 'en' ? 'en' : 'zh-CN';

/** 设置里的语言和当前界面不一致时，记下来并刷新页面。返回 true 表示即将刷新 */
function syncLang(en) {
  const want = en ? 'en' : 'zh';
  if (want === LANG) return false;
  try {
    localStorage.setItem('lang', want);
    if (localStorage.getItem('lang') === want) { location.reload(); return true; }
  } catch {}
  return false;
}

function t(s, vars) {
  let out = (LANG === 'en' && EN[s] !== undefined) ? EN[s] : s;
  if (vars) out = out.replace(/\{(\w+)\}/g, (_, k) => (vars[k] ?? ''));
  return out;
}

/** 把页面上的静态文字和 title / placeholder / alt / aria-label 换成当前语言 */
function translateDom(root = document.body) {
  if (LANG !== 'en') return;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  for (const n of nodes) {
    const key = n.nodeValue.trim();
    if (key && EN[key] !== undefined) n.nodeValue = n.nodeValue.replace(key, EN[key]);
  }
  for (const el of root.querySelectorAll('[title],[placeholder],[alt],[aria-label]')) {
    for (const attr of ['title', 'placeholder', 'alt', 'aria-label']) {
      const v = el.getAttribute(attr);
      if (v && EN[v] !== undefined) el.setAttribute(attr, EN[v]);
    }
  }
  document.title = t(document.title);
}
