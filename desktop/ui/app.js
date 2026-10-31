const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const $ = s => document.querySelector(s);
translateDom();

const state = {
  info: null,
  view: 'chat',
  messages: [],
  lastSeq: 0,
  unread: 0,
  files: [],
  selected: new Set(),
  anchor: null,
  fileView: localStorage.getItem('fileView') || 'grid',
  query: '',
  draggingOut: false,
};

// ---------- 工具 ----------
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.remove('show'), 2400);
}
function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function fmtSize(n) {
  const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return i ? `${n.toFixed(n < 10 ? 2 : 1)} ${u[i]}` : `${n} B`;
}
function hm(d) { return d.toTimeString().slice(0, 5); }
function fmtDay(sec) {
  const d = new Date(sec * 1000), now = new Date();
  const days = Math.round((new Date(now.toDateString()) - new Date(d.toDateString())) / 864e5);
  if (days === 0) return hm(d);
  if (days === 1) return `${t('昨天')} ${hm(d)}`;
  if (LANG === 'en') {
    if (days < 7) return `${d.toLocaleDateString('en-US', { weekday: 'long' })} ${hm(d)}`;
    const opt = d.getFullYear() === now.getFullYear() ? { month: 'short', day: 'numeric' } : { year: 'numeric', month: 'short', day: 'numeric' };
    return `${d.toLocaleDateString('en-US', opt)} ${hm(d)}`;
  }
  if (days < 7) return `星期${'日一二三四五六'[d.getDay()]} ${hm(d)}`;
  return d.getFullYear() === now.getFullYear() ? `${d.getMonth() + 1}月${d.getDate()}日 ${hm(d)}` : `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${hm(d)}`;
}
async function call(cmd, args) {
  try { return await invoke(cmd, args); }
  catch (e) { toast(String(e)); throw e; }
}
function icon(id, size = 20) { return `<svg width="${size}" height="${size}"><use href="#${id}"/></svg>`; }

const TYPES = [
  [/^(heic|heif|raw|dng|psd)$/, '#0ea5e9'],
  [/^(mp4|mov|mkv|avi|wmv|flv|webm|m4v|3gp)$/, '#8b5cf6'],
  [/^(mp3|wav|flac|aac|m4a|ogg|amr|wma)$/, '#db2777'],
  [/^(pdf)$/, '#dc2626'],
  [/^(docx?|rtf|odt|wps)$/, '#2563eb'],
  [/^(xlsx?|csv|ods|et)$/, '#16a34a'],
  [/^(pptx?|odp|dps|key)$/, '#ea580c'],
  [/^(zip|rar|7z|tar|gz|bz2|xz)$/, '#b45309'],
  [/^(apk|apks|xapk)$/, '#15803d'],
  [/^(exe|msi|bat|cmd)$/, '#475569'],
];
const IMAGE = /\.(jpe?g|png|gif|webp|bmp|avif|ico)$/i;
function ext(name) { return (name.includes('.') ? name.split('.').pop() : '').toLowerCase(); }
function ftype(name) {
  const e = ext(name);
  const color = (TYPES.find(([re]) => re.test(e)) || [0, '#64748b'])[1];
  return `<div class="ftype" style="--c:${color}">${esc(e.slice(0, 4).toUpperCase() || 'FILE')}</div>`;
}
function fileUrl(name, mtime) {
  return `http://127.0.0.1:${state.info?.port || 8765}/dl/${encodeURIComponent(name)}?inline=1${mtime ? '&t=' + mtime : ''}`;
}

function confirmDialog(title, text, okLabel) {
  return new Promise(resolve => {
    $('#confirmTitle').textContent = title;
    $('#confirmText').textContent = text;
    $('#confirmOk').textContent = okLabel;
    const modal = $('#confirmModal');
    const done = v => { modal.classList.remove('show'); document.removeEventListener('keydown', onKey, true); resolve(v); };
    const onKey = e => {
      if (e.key === 'Escape') { e.stopPropagation(); done(false); }
      if (e.key === 'Enter') { e.stopPropagation(); e.preventDefault(); done(true); }
    };
    $('#confirmOk').onclick = () => done(true);
    $('#confirmCancel').onclick = () => done(false);
    document.addEventListener('keydown', onKey, true);
    modal.classList.add('show');
    $('#confirmCancel').focus();
  });
}

// ---------- 视图切换 ----------
function showView(v) {
  state.view = v;
  for (const b of document.querySelectorAll('.nav')) b.classList.toggle('on', b.dataset.view === v);
  for (const s of document.querySelectorAll('.view')) s.hidden = s.id !== 'view-' + v;
  if (v === 'chat') { state.unread = 0; renderUnread(); scrollToBottom(); $('#input').focus(); }
  if (v === 'files') loadFiles(true);
  if (v === 'settings') loadSettings();
  if (v === 'phone') refreshMirror();
  hideCtx();
}
for (const b of document.querySelectorAll('.nav')) b.onclick = () => showView(b.dataset.view);
function renderUnread() {
  const b = $('#unread');
  b.hidden = !state.unread;
  b.textContent = state.unread > 99 ? '99+' : state.unread;
}

// ---------- 聊天 ----------
const chatScroll = $('#chatScroll'), chatList = $('#chatList');
function nearBottom() { return chatScroll.scrollHeight - chatScroll.scrollTop - chatScroll.clientHeight < 80; }
function scrollToBottom() { chatScroll.scrollTop = chatScroll.scrollHeight; $('#newPill').hidden = true; }
// 记住用户是否停在最新消息处，窗口尺寸变化后保持贴底
let stuckToBottom = true;
chatScroll.addEventListener('scroll', () => {
  stuckToBottom = nearBottom();
  if (stuckToBottom) $('#newPill').hidden = true;
});
new ResizeObserver(() => { if (stuckToBottom) chatScroll.scrollTop = chatScroll.scrollHeight; }).observe(chatScroll);
$('#newPill').onclick = scrollToBottom;

function renderEmptyChat() {
  const ip = state.info?.ips?.[0];
  chatList.innerHTML = `
    <div class="chat-empty">
      <div class="art">${icon('i-chat', 34)}</div>
      <h2>${t('手机和电脑的聊天记录会显示在这里')}</h2>
      <p>${t('在手机任意 App 里点「分享 → 发送到电脑」，或在下面输入文字、拖入文件发给手机。')}</p>
      <p>${t('手机还没连接？在手机上打开「文件互传」App，它会自动找到「{name}」', { name: esc(state.info?.name || t('这台电脑')) })}${ip ? t('；找不到时手动输入 {ip}', { ip: `<code>${esc(ip)}</code>` }) : ''}${LANG === 'en' ? '.' : '。'}</p>
      <p>${t('手机还没装 App？在「设置 → 给手机安装 App」里扫码下载。')}</p>
    </div>`;
}

function messageHtml(m, prev) {
  let html = '';
  if (!prev || m.time - prev.time > 300) html += `<div class="time-sep">${fmtDay(m.time)}</div>`;
  const grouped = prev && prev.side === m.side && prev.from === m.from && m.time - prev.time < 120 && !html;
  const side = m.side === 'pc' ? 'pc' : 'phone';
  const avatar = side === 'pc'
    ? `<div class="avatar pc">${icon('i-pc', 19)}</div>`
    : `<div class="avatar">${icon('i-phone', 19)}</div>`;
  let body;
  if (m.kind === 'text') {
    const text = esc(m.text).replace(/https?:\/\/[^\s<]+/g, u => `<a data-url="${u}">${u}</a>`);
    body = `<div class="bubble text">${text}</div>`;
  } else if (IMAGE.test(m.name) && m.exists) {
    body = `<div class="bubble image" data-name="${esc(m.name)}" title="${esc(m.name)}"><img draggable="false" loading="lazy" src="${fileUrl(m.name)}" alt=""></div>`;
  } else if (IMAGE.test(m.name)) {
    body = `<div class="bubble image gone"><div class="image-gone">${t('图片已删除<br>或已移走')}</div></div>`;
  } else {
    body = `<div class="bubble file${m.exists ? '' : ' gone'}" data-name="${esc(m.name)}" title="${esc(m.name)}">
      <div class="meta"><div class="name">${esc(m.name)}</div><div class="size">${fmtSize(m.size || 0)}</div></div>${ftype(m.name)}</div>`;
  }
  const sender = side === 'phone' && !grouped ? `<div class="sender">${esc(m.from)}</div>` : '';
  return html + `<div class="msg ${side}${grouped ? ' grouped' : ''}" data-seq="${m.seq}">${avatar}<div class="col">${sender}${body}</div></div>`;
}

function renderChat() {
  const list = state.messages;
  if (!list.length) { renderEmptyChat(); return; }
  chatList.innerHTML = list.map((m, i) => messageHtml(m, list[i - 1])).join('');
  watchImages(chatList);
  scrollToBottom();
}

function appendMessages(newOnes) {
  newOnes = newOnes.filter(m => m.seq > state.lastSeq);
  if (!newOnes.length) return;
  if (newOnes.some(m => m.kind === 'cleared')) { loadMessages(true); return; }
  const stick = nearBottom();
  const wasEmpty = !state.messages.length;
  for (const m of newOnes) {
    state.lastSeq = Math.max(state.lastSeq, m.seq);
    if (m.kind === 'deleted') continue;
    const prev = state.messages[state.messages.length - 1];
    state.messages.push(m);
    if (wasEmpty && state.messages.length === 1) chatList.innerHTML = '';
    chatList.insertAdjacentHTML('beforeend', messageHtml(m, prev));
    if (m.side === 'phone' && (state.view !== 'chat' || document.hidden)) state.unread++;
  }
  watchImages(chatList);
  renderUnread();
  const mine = newOnes.some(m => m.side === 'pc');
  if (stick || mine) scrollToBottom();
  else $('#newPill').hidden = false;
}

// 图片加载完会撑高列表，保持贴底
function watchImages(root) {
  for (const img of root.querySelectorAll('img:not([data-w])')) {
    img.dataset.w = '1';
    img.addEventListener('load', () => { if (nearBottom() || img._stick) scrollToBottom(); }, { once: true });
    img._stick = nearBottom();
  }
}

async function loadMessages(full) {
  if (full) { state.messages = []; state.lastSeq = 0; }
  const list = await invoke('get_messages', { after: state.lastSeq });
  const real = list.filter(m => m.kind !== 'cleared' && m.kind !== 'deleted');
  if (full) {
    state.messages = real;
    state.lastSeq = list.reduce((a, m) => Math.max(a, m.seq), 0);
    renderChat();
  } else {
    appendMessages(list);
  }
}
listen('message', ({ payload }) => appendMessages([payload]));
listen('history-cleared', () => loadMessages(true));

// 手机传文件时，先显示一个带进度条的气泡
const transfers = new Map();
listen('transfer', ({ payload: t }) => {
  let el = transfers.get(t.id);
  if (!el) {
    if (t.state === 'done') return;
    el = document.createElement('div');
    el.className = 'msg phone';
    el.innerHTML = `<div class="avatar">${icon('i-phone', 19)}</div><div class="col"><div class="sender"></div>
      <div class="bubble file"><div class="meta"><div class="name"></div><div class="size"></div><div class="bar"><i></i></div></div>${ftype(t.name)}</div></div>`;
    el.querySelector('.sender').textContent = t.from;
    el.querySelector('.name').textContent = t.name;
    el._start = Date.now();
    if (!state.messages.length) chatList.innerHTML = '';
    const stick = nearBottom();
    chatList.appendChild(el);
    transfers.set(t.id, el);
    if (stick) scrollToBottom();
  }
  const size = el.querySelector('.size');
  if (t.state === 'done') {
    el.remove();
    transfers.delete(t.id);
  } else if (t.state === 'failed') {
    el.querySelector('.bubble').classList.add('failed');
    size.textContent = t('传输中断');
    setTimeout(() => { el.remove(); transfers.delete(t.id); }, 5000);
  } else {
    const pct = t.total ? t.done / t.total * 100 : 0;
    const speed = t.done / Math.max(0.2, (Date.now() - el._start) / 1000);
    el.querySelector('.bar i').style.width = pct + '%';
    size.textContent = `${t.total ? fmtSize(t.done) + ' / ' + fmtSize(t.total) : fmtSize(t.done)} · ${fmtSize(speed)}/s`;
  }
});

// 发送
const input = $('#input');
async function sendText(text) {
  text = (text ?? input.value).trim();
  if (!text) return;
  await call('send_text', { text });
  if (text === input.value.trim()) input.value = '';
}
$('#btnSend').onclick = () => sendText();
input.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendText(); }
});
$('#btnAttach').onclick = async () => {
  const r = await call('pick_files');
  if (r.copied) toast(t('已发送 {n} 个文件', { n: r.copied }));
};
$('#btnClipboard').onclick = async () => {
  const text = await call('clipboard_read');
  if (text) sendText(text); else toast(t('剪贴板里没有文字'));
};
// Ctrl+V 粘贴截图或文件
input.addEventListener('paste', async e => {
  const files = [...(e.clipboardData?.files || [])];
  if (!files.length) return;
  e.preventDefault();
  for (const f of files) {
    const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14);
    const name = f.name && f.name !== 'image.png' ? f.name : `${t('截图')}_${stamp}.${(f.type.split('/')[1] || 'png').replace('jpeg', 'jpg')}`;
    const buf = new Uint8Array(await f.arrayBuffer());
    await call('paste_file', buf, { headers: { 'x-name': encodeURIComponent(name) } });
  }
});

// 聊天里的图片、文件：单击打开，拖出去复制，右键菜单
let pressedMsg = null, justDragged = false;
chatList.addEventListener('mousedown', e => {
  const b = e.target.closest('.bubble[data-name]');
  if (e.button === 0 && b && !b.classList.contains('gone')) pressedMsg = { name: b.dataset.name, x: e.clientX, y: e.clientY };
});
chatList.addEventListener('click', e => {
  const a = e.target.closest('a[data-url]');
  if (a) { invoke('open_url', { url: a.dataset.url }); return; }
  const b = e.target.closest('.bubble[data-name]');
  if (b && !b.classList.contains('gone') && !justDragged) call('open_item', { name: b.dataset.name });
});
chatList.addEventListener('contextmenu', e => {
  const msgEl = e.target.closest('.msg[data-seq]');
  if (!msgEl) return;
  e.preventDefault();
  const m = state.messages.find(x => String(x.seq) === msgEl.dataset.seq);
  if (!m) return;
  const items = [];
  if (m.kind === 'text') {
    const sel = String(window.getSelection());
    items.push([t(sel ? '复制选中文字' : '复制'), () => copy(sel || m.text)]);
  } else if (m.exists) {
    items.push([t('打开'), () => call('open_item', { name: m.name })]);
    items.push([t('在文件夹中显示'), () => call('reveal_item', { name: m.name })]);
  }
  items.push('-', [t('删除这条记录'), async () => {
    await call('delete_message', { seq: m.seq });
    state.messages = state.messages.filter(x => x.seq !== m.seq);
    renderChat();
  }, 'danger']);
  showCtx(e.clientX, e.clientY, items);
});

async function copy(text) {
  await call('clipboard_write', { text });
  toast(t('已复制'));
}

// ---------- 文件 ----------
let lastSig = '';
async function loadFiles(force) {
  const files = await invoke('list_files');
  const sig = JSON.stringify(files);
  if (!force && sig === lastSig) return;
  lastSig = sig;
  state.files = files;
  const names = new Set(files.map(f => f.name));
  for (const n of [...state.selected]) if (!names.has(n)) state.selected.delete(n);
  renderFiles();
}
function visibleFiles() {
  const q = state.query.trim().toLowerCase();
  return q ? state.files.filter(f => f.name.toLowerCase().includes(q)) : state.files;
}
function renderFiles() {
  const box = $('#files');
  box.className = state.fileView;
  $('#count').textContent = state.files.length ? t('{n} 个文件', { n: state.files.length }) : '';
  const files = visibleFiles();
  if (!state.files.length) {
    box.innerHTML = `<div class="empty">${icon('i-files', 40)}<h2>${t('接收文件夹是空的')}</h2><div>${t('手机发来的文件会出现在这里，也可以把电脑上的文件拖进来')}</div></div>`;
    return;
  }
  if (!files.length) {
    box.innerHTML = `<div class="empty"><h2>${t('没有找到「{q}」', { q: esc(state.query) })}</h2></div>`;
    return;
  }
  box.innerHTML = files.map(f => {
    const thumb = IMAGE.test(f.name) ? `<img loading="lazy" draggable="false" src="${fileUrl(f.name, f.mtime)}" alt="">` : ftype(f.name);
    return `<div class="tile${state.selected.has(f.name) ? ' sel' : ''}" data-name="${esc(f.name)}" title="${esc(f.name)}&#10;${fmtSize(f.size)} · ${fmtDay(f.mtime)}">
      <div class="thumb">${thumb}</div>
      <div class="fname">${esc(f.name)}</div>
      ${f.from ? `<div class="from">${t('来自 {from}', { from: esc(f.from) })}</div>` : '<div class="from"></div>'}
      <div class="info">${fmtSize(f.size)} · ${fmtDay(f.mtime)}</div>
    </div>`;
  }).join('');
}
function updateSelection() {
  for (const el of $('#files').querySelectorAll('.tile')) el.classList.toggle('sel', state.selected.has(el.dataset.name));
}
function selectClick(name, e) {
  const names = visibleFiles().map(f => f.name);
  if (e.shiftKey && state.anchor && names.includes(state.anchor)) {
    const [a, b] = [names.indexOf(state.anchor), names.indexOf(name)].sort((x, y) => x - y);
    if (!e.ctrlKey) state.selected.clear();
    names.slice(a, b + 1).forEach(n => state.selected.add(n));
  } else if (e.ctrlKey) {
    state.selected.has(name) ? state.selected.delete(name) : state.selected.add(name);
    state.anchor = name;
  } else {
    state.selected = new Set([name]);
    state.anchor = name;
  }
  updateSelection();
}

let pressed = null;
$('#files').addEventListener('mousedown', e => {
  hideCtx();
  if (e.button !== 0) return;
  const tile = e.target.closest('.tile');
  if (!tile) { state.selected.clear(); updateSelection(); return; }
  const name = tile.dataset.name;
  // 按在已选中的文件上：先保留多选（可能要一起拖出去），松开时再决定
  const keep = state.selected.has(name) && !e.ctrlKey && !e.shiftKey;
  if (!keep) selectClick(name, e);
  pressed = { name, x: e.clientX, y: e.clientY, pending: keep };
});
$('#files').addEventListener('dblclick', e => {
  const tile = e.target.closest('.tile');
  if (tile) call('open_item', { name: tile.dataset.name });
});
$('#files').addEventListener('contextmenu', e => {
  e.preventDefault();
  const tile = e.target.closest('.tile');
  if (tile && !state.selected.has(tile.dataset.name)) {
    state.selected = new Set([tile.dataset.name]);
    state.anchor = tile.dataset.name;
    updateSelection();
  }
  const names = [...state.selected];
  const items = names.length
    ? [
        names.length === 1 && [t('打开'), () => call('open_item', { name: names[0] })],
        [t('在资源管理器中显示'), () => call('reveal_item', { name: names[0] })],
        '-',
        [names.length > 1 ? t('移到回收站（{n} 个）', { n: names.length }) : t('移到回收站'), deleteSelected, 'danger'],
      ]
    : [[t('在资源管理器中打开'), () => call('open_folder')], [t('刷新'), () => loadFiles(true)]];
  showCtx(e.clientX, e.clientY, items.filter(Boolean));
});

// 拖出（文件视图和聊天视图共用）：按住移动一小段距离后交给系统拖放
document.addEventListener('mousemove', async e => {
  if (e.buttons !== 1) return;
  let names;
  if (pressed && Math.hypot(e.clientX - pressed.x, e.clientY - pressed.y) >= 6) {
    names = state.selected.has(pressed.name) ? [...state.selected] : [pressed.name];
    pressed = null;
  } else if (pressedMsg && Math.hypot(e.clientX - pressedMsg.x, e.clientY - pressedMsg.y) >= 6) {
    names = [pressedMsg.name];
    pressedMsg = null;
    justDragged = true;
  } else return;
  state.draggingOut = true;
  try { await invoke('start_drag', { names }); }
  catch (err) { toast(t('拖动失败：') + err); }
  finally {
    state.draggingOut = false;
    $('#dropOverlay').classList.remove('show');
    setTimeout(() => { justDragged = false; }, 50);
  }
});
document.addEventListener('mouseup', e => {
  if (pressed?.pending && !e.ctrlKey && !e.shiftKey) {
    state.selected = new Set([pressed.name]);
    state.anchor = pressed.name;
    updateSelection();
  }
  pressed = null;
  pressedMsg = null;
});

async function deleteSelected() {
  const names = [...state.selected];
  if (!names.length) return;
  const label = names.length === 1 ? t('「{name}」', { name: names[0] }) : t('这 {n} 个文件', { n: names.length });
  if (!await confirmDialog(t('移到回收站'), t('把{label}移到回收站？手机上也将看不到它们，需要时可以从回收站还原。', { label }), t('移到回收站'))) return;
  await call('delete_items', { names });
  state.selected.clear();
  toast(t('已移到回收站'));
  loadFiles(true);
}

$('#search').addEventListener('input', e => { state.query = e.target.value; renderFiles(); });
function setFileView(v) {
  state.fileView = v;
  try { localStorage.setItem('fileView', v); } catch {}
  $('#viewGrid').classList.toggle('on', v === 'grid');
  $('#viewList').classList.toggle('on', v === 'list');
  renderFiles();
}
$('#viewGrid').onclick = () => setFileView('grid');
$('#viewList').onclick = () => setFileView('list');
$('#filesFolder').onclick = () => call('open_folder');
$('#chatFolder').onclick = () => call('open_folder');
listen('files-changed', () => { if (state.view === 'files') loadFiles(true); });

// ---------- 右键菜单 ----------
function showCtx(x, y, items) {
  const ctx = $('#ctx');
  ctx.innerHTML = '';
  for (const it of items) {
    if (it === '-') { ctx.appendChild(document.createElement('hr')); continue; }
    const b = document.createElement('button');
    b.textContent = it[0];
    if (it[2]) b.className = it[2];
    b.onclick = () => { hideCtx(); it[1](); };
    ctx.appendChild(b);
  }
  ctx.classList.add('show');
  const r = ctx.getBoundingClientRect();
  ctx.style.left = Math.min(x, innerWidth - r.width - 8) + 'px';
  ctx.style.top = Math.min(y, innerHeight - r.height - 8) + 'px';
}
function hideCtx() { $('#ctx').classList.remove('show'); }
document.addEventListener('click', e => { if (!e.target.closest('#ctx')) hideCtx(); });
window.addEventListener('blur', hideCtx);
document.addEventListener('contextmenu', e => {
  if (!e.target.closest('#files, #chatList, textarea, input')) e.preventDefault();
});

// 键盘
document.addEventListener('keydown', e => {
  if (document.querySelector('.modal-back.show')) return;
  if (e.key === 'Escape') { hideCtx(); state.selected.clear(); updateSelection(); return; }
  if (state.view !== 'files' || e.target.closest('textarea, input')) return;
  if (e.key === 'Delete') deleteSelected();
  else if (e.key === 'Enter' && state.selected.size === 1) call('open_item', { name: [...state.selected][0] });
  else if (e.key === 'a' && e.ctrlKey) { e.preventDefault(); state.selected = new Set(visibleFiles().map(f => f.name)); updateSelection(); }
  else if (e.key === 'F5') { e.preventDefault(); loadFiles(true); }
});

// ---------- 拖入：任何视图都可以，等于发给手机 ----------
window.__TAURI__.webview.getCurrentWebview().onDragDropEvent(async e => {
  const p = e.payload, overlay = $('#dropOverlay');
  if (state.draggingOut) return;
  if (p.type === 'enter' || p.type === 'over') overlay.classList.add('show');
  else if (p.type === 'leave') overlay.classList.remove('show');
  else if (p.type === 'drop') {
    overlay.classList.remove('show');
    if (!p.paths?.length) return;
    const r = await call('import_paths', { paths: p.paths });
    let msg = r.copied ? t('已发送 {n} 个文件', { n: r.copied }) : t('没有可发送的文件');
    if (r.skipped_dirs) msg += t('，{n} 个文件夹需要先压缩', { n: r.skipped_dirs });
    toast(msg);
    if (state.view === 'files') loadFiles(true);
  }
});

// ---------- 手机屏幕（投屏） ----------
const mirrorImg = $('#mirrorImg');
async function refreshMirror() {
  const s = await invoke('mirror_state');
  $('#mirrorEmpty').hidden = s.active;
  $('#mirrorWrap').hidden = !s.active;
  $('#liveDot').hidden = !s.active;
  $('#mirrorStatus').textContent = s.active
    ? t(s.control ? '{device} 正在投屏 · 可以用鼠标键盘操作' : '{device} 正在投屏 · 手机没开无障碍，只能查看', { device: s.device || t('手机') })
    : '';
  if (s.active && !mirrorImg.src.includes('/api/mirror/view')) {
    mirrorImg.src = `http://127.0.0.1:${state.info?.port || 8765}/api/mirror/view?t=${Date.now()}`;
  } else if (!s.active) {
    mirrorImg.removeAttribute('src');
  }
  if (s.active && state.view === 'phone') $('#mirrorType').focus();
}
listen('mirror', ({ payload }) => {
  if (payload.active) { toast(t('{device} 开始投屏', { device: payload.device })); showView('phone'); }
  else toast(t('手机已停止投屏'));
  refreshMirror();
});
listen('mirror-control', refreshMirror);

function mirrorSend(event) { invoke('mirror_input', { event }); }

/** 鼠标位置 → 手机画面上的相对坐标（0~1），考虑 object-fit: contain 留下的黑边 */
function mirrorPoint(e) {
  const r = mirrorImg.getBoundingClientRect();
  const nw = mirrorImg.naturalWidth, nh = mirrorImg.naturalHeight;
  if (!nw || !nh) return null;
  const s = Math.min(r.width / nw, r.height / nh);
  const w = nw * s, h = nh * s;
  const x = (e.clientX - (r.left + (r.width - w) / 2)) / w;
  const y = (e.clientY - (r.top + (r.height - h) / 2)) / h;
  return x < 0 || x > 1 || y < 0 || y > 1 ? null : { x, y };
}

let mirrorDown = null;
mirrorImg.addEventListener('mousedown', e => {
  if (e.button !== 0) return;
  const p = mirrorPoint(e);
  if (p) mirrorDown = { ...p, time: Date.now() };
  e.preventDefault();
});
document.addEventListener('mouseup', e => {
  if (!mirrorDown || e.button !== 0) return;
  const start = mirrorDown;
  mirrorDown = null;
  const end = mirrorPoint(e) || start;
  const ms = Date.now() - start.time;
  const moved = Math.hypot(end.x - start.x, end.y - start.y) > 0.015;
  if (!moved) mirrorSend(ms > 500 ? { t: 'long', x: start.x, y: start.y } : { t: 'tap', x: start.x, y: start.y });
  else mirrorSend({ t: 'swipe', x1: start.x, y1: start.y, x2: end.x, y2: end.y, ms: Math.min(3000, Math.max(80, ms)) });
  $('#mirrorType').focus();
});
mirrorImg.addEventListener('contextmenu', e => { e.preventDefault(); mirrorSend({ t: 'global', a: 'back' }); });
let wheelAt = 0;
mirrorImg.addEventListener('wheel', e => {
  e.preventDefault();
  if (Date.now() - wheelAt < 250) return;
  wheelAt = Date.now();
  const p = mirrorPoint(e) || { x: 0.5, y: 0.5 };
  mirrorSend({ t: 'scroll', x: p.x, y: p.y, dy: e.deltaY > 0 ? 1 : -1 });
}, { passive: false });
for (const b of document.querySelectorAll('[data-global]')) {
  b.onclick = () => { mirrorSend({ t: 'global', a: b.dataset.global }); $('#mirrorType').focus(); };
}
// 打字：用一个隐藏输入框接住电脑输入法的结果（中文也行），发给手机当前的输入框
const mirrorType = $('#mirrorType');
mirrorType.addEventListener('input', e => {
  if (e.isComposing) return;
  if (mirrorType.value) mirrorSend({ t: 'text', s: mirrorType.value });
  mirrorType.value = '';
});
mirrorType.addEventListener('compositionend', () => {
  if (mirrorType.value) mirrorSend({ t: 'text', s: mirrorType.value });
  mirrorType.value = '';
});
mirrorType.addEventListener('keydown', e => {
  if (e.isComposing) return;
  if (e.key === 'Backspace') { e.preventDefault(); mirrorSend({ t: 'key', k: 'backspace' }); }
  else if (e.key === 'Enter') { e.preventDefault(); mirrorSend({ t: 'key', k: 'enter' }); }
  else if (e.key === 'Escape') { e.preventDefault(); mirrorSend({ t: 'global', a: 'back' }); }
});

// ---------- 设备与配对 ----------
async function loadDevices() {
  const devices = await invoke('get_devices');
  const online = devices.filter(d => d.online);
  $('#peerName').textContent = online[0]?.name || devices[0]?.name || t('我的手机');
  $('#peerDot').classList.toggle('on', online.length > 0);
  $('#peerStatus').textContent = online.length
    ? (online.length > 1 ? t('{n} 台手机在线', { n: online.length }) : t('在线'))
    : t('离线 · 在手机上打开 App 即可连接');
  return devices;
}
listen('pair-request', ({ payload }) => {
  $('#pairText').textContent = t('{device}（{ip}）想要连接这台电脑。允许后，它可以向电脑发送文件和文字，并下载接收文件夹里的文件。', { device: t('「{name}」', { name: payload.device }), ip: payload.ip });
  $('#pairModal').classList.add('show');
  $('#pairAllow').focus();
});
listen('pair-closed', () => $('#pairModal').classList.remove('show'));
listen('paired', ({ payload }) => { toast(t('已连接 {device}', { device: payload.device })); loadDevices(); });
$('#pairAllow').onclick = () => { invoke('pair_respond', { allow: true }); $('#pairModal').classList.remove('show'); };
$('#pairDeny').onclick = () => { invoke('pair_respond', { allow: false }); $('#pairModal').classList.remove('show'); };

// ---------- 设置 ----------
async function loadSettings() {
  state.info = await invoke('get_info');
  const i = state.info;
  $('#setDir').textContent = i.dir;
  $('#setAutostart').checked = i.autostart;
  $('#setRemote').checked = i.remote;
  $('#setNotify').checked = i.notify;
  renderLang(i.lang);
  renderHelper(i.helper);
  invoke('install_qr').then(q => {
    $('#installGroup').hidden = !q.available;
    if (q.available) { $('#installQr').innerHTML = q.svg; $('#installUrl').textContent = q.url; }
  });
  renderSendto(i.sendto);
  // 只显示首选地址，其余（多为虚拟网卡）放在悬停提示里
  $('#setAddr').textContent = i.ips.length ? `${i.ips[0]}:${i.port}` : t('未连接局域网');
  $('#setAddr').title = i.ips.length > 1 ? t('其他地址：') + i.ips.slice(1).map(ip => `${ip}:${i.port}`).join(LANG === 'en' ? ', ' : '、') : '';
  const devices = await loadDevices();
  $('#setDevices').innerHTML = devices.length
    ? devices.map(d => `<div class="row">
        <div class="ico${d.online ? '' : ' neutral'}">${icon('i-phone', 19)}</div>
        <div class="txt"><div class="title">${esc(d.name)}</div><div class="desc">${esc(d.ip)}</div></div>
        <div class="state" style="${d.online ? '' : 'color:var(--ink-3)'}"><span class="dot${d.online ? ' on' : ''}"></span>${t(d.online ? '在线' : '离线')}</div>
      </div>`).join('')
    : `<div class="row"><div class="ico neutral">${icon('i-phone', 19)}</div>
        <div class="txt"><div class="title">${t('还没有手机连接')}</div><div class="desc">${t('在手机上打开「文件互传」App，连同一个 Wi-Fi，它会自动找到这台电脑')}</div></div></div>`;
  const qr = await invoke('browser_qr');
  $('#setQr').innerHTML = qr.svg;
  $('#setUrl').textContent = qr.url;
}
function renderSendto(added) {
  $('#setSendtoBox').innerHTML = added
    ? `<span class="state">${icon('i-check', 16)}${t('已添加')}</span>`
    : `<button class="btn" id="setSendto">${t('添加')}</button>`;
  if (!added) $('#setSendto').onclick = async () => {
    await call('add_sendto');
    renderSendto(true);
    toast(t('右键任意文件 → 发送到 → 手机互传'));
  };
}
$('#setOpenDir').onclick = () => call('open_folder');
$('#setChooseDir').onclick = async () => {
  const dir = await call('choose_folder');
  if (dir) { $('#setDir').textContent = dir; toast(t('接收文件夹已更改')); lastSig = ''; }
};
$('#setAutostart').onchange = async e => {
  try { await call('set_autostart', { enable: e.target.checked }); toast(t(e.target.checked ? '已开启开机自动启动' : '已关闭开机自动启动')); }
  catch { e.target.checked = !e.target.checked; }
};
function renderHelper(active) {
  $('#helperBox').innerHTML = active
    ? `<span class="state">${icon('i-check', 16)}${t('已授权')}</span>`
    : `<button class="btn" id="setHelper">${t('授权')}</button>`;
  if (!active) $('#setHelper').onclick = startHelper;
  $('#remoteHelper').hidden = active || $('#remoteHelper').hidden;
}
async function startHelper() {
  toast(t('请在 Windows 弹出的确认框里点「是」'));
  await call('start_input_helper');
  renderHelper(true);
  $('#remoteHelper').hidden = true;
  toast(t('已授权，手机现在可以操作管理员权限的程序了'));
}
$('#remoteHelper').onclick = startHelper;
let blockedToastAt = 0;
listen('remote-blocked', () => {
  $('#remoteHelper').hidden = false;
  if (Date.now() - blockedToastAt > 10000) {
    blockedToastAt = Date.now();
    toast(t('手机点到的是管理员权限的程序，需要授权才能操作'));
  }
});
listen('open-chat', () => showView('chat'));
function renderLang(lang) {
  for (const b of document.querySelectorAll('#setLang button')) b.classList.toggle('on', b.dataset.lang === lang);
}
for (const b of document.querySelectorAll('#setLang button')) {
  b.onclick = async () => {
    const en = await call('set_lang', { lang: b.dataset.lang });
    location.hash = 'settings'; // 刷新后回到设置页
    if (!syncLang(en)) renderLang(b.dataset.lang);
  };
}
$('#setNotify').onchange = async e => {
  await call('set_notify', { enable: e.target.checked });
  toast(t(e.target.checked ? '已开启消息通知' : '已关闭消息通知'));
};
$('#setRemote').onchange = async e => {
  await call('set_remote', { enable: e.target.checked });
  toast(t(e.target.checked ? '已允许手机控制这台电脑' : '已关闭远程控制'));
};
listen('remote-session', ({ payload }) => {
  $('#remoteBar').hidden = !payload.active;
  if (payload.active) $('#remoteText').textContent = t('{device} 正在控制这台电脑', { device: payload.device });
});
$('#remoteStop').onclick = async () => {
  await call('remote_disconnect');
  $('#remoteBar').hidden = true;
  toast(t('已断开远程控制'));
};
$('#setPin').onclick = async () => { await call('pin_quick_access'); toast(t('已固定到资源管理器「快速访问」')); };
$('#setClear').onclick = async () => {
  if (!await confirmDialog(t('清空聊天记录'), t('电脑和手机上的聊天记录都会被清空。接收文件夹里的文件会保留。'), t('清空'))) return;
  await call('clear_history');
  toast(t('聊天记录已清空'));
};
$('#setReset').onclick = async () => {
  if (!await confirmDialog(t('重置配对'), t('所有已连接的手机都需要重新配对，浏览器扫码链接也会失效。'), t('重置'))) return;
  await call('reset_key');
  toast(t('已重置，手机需要重新连接'));
  loadSettings();
};

// ---------- 启动 ----------
(async () => {
  state.info = await invoke('get_info');
  if (syncLang(state.info.en)) return;
  setFileView(state.fileView);
  await Promise.all([loadMessages(true), loadDevices(), refreshMirror()]);
  input.focus();
  if (location.hash === '#settings') { history.replaceState(null, '', location.pathname); showView('settings'); }
  setInterval(() => loadDevices(), 3000);
  setInterval(() => loadMessages(false), 5000);                 // 兜底：事件丢失时补拉
  setInterval(() => { if (state.view === 'files' && !document.hidden) loadFiles(); }, 2000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden && state.view === 'chat') { state.unread = 0; renderUnread(); } });
})();
