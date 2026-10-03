/*
 * yann 通知桥接脚本 v4
 *
 * 只保留三件事，删掉了上一版的 DOM 抓取兜底：
 *
 *   1. Notification 垫片
 *      站点自己就会在收到新消息时调用 new Notification(title, {body, icon, tag})，
 *      它内部已经有「是不是对方消息 / 通知开关是否打开」的过滤逻辑。
 *      上一版额外加的 DOM toast 抓取，把站点所有 sonner 操作提示
 *      （字卡已添加、头像已更新、聊天设置…）也全变成了系统通知 → 刷屏。
 *      因此这里只接住站点主动发出的 Notification，不再猜 DOM。
 *
 *   2. Blob 导出截获
 *      站点导出备份的做法是 a.href=URL.createObjectURL(blob); a.download=x.json; a.click()
 *      WebView 的 DownloadListener 收到的是 blob: URL，系统下载器无法解析 → 导出静默失败。
 *      这里在 JS 侧把 Blob 读成 data URL 交给 Native 落盘。
 *
 *   3. 麦克风/摄像头权限探测兜底
 *      WebView 未实现 Permissions API，站点若用 navigator.permissions.query 预判
 *      麦克风状态会拿不到结果，这里补一个返回 granted 的实现。
 *
 * 注入点：assets/notify_bridge.js（document-start），幂等。
 */
(function () {
  'use strict';
  if (window.__yannBridge) return;
  window.__yannBridge = true;

  var DEBUG = false;
  window.__yannDebug = [];

  function native() {
    return (window.AndroidNotify && typeof window.AndroidNotify.postMessage === 'function')
      ? window.AndroidNotify : null;
  }

  function post(title, body, icon, tag) {
    title = String(title || '').trim();
    body = String(body || '').trim();
    if (!title && !body) return false;
    var n = native();
    if (!n) return false;
    try {
      n.postMessage(JSON.stringify({
        title: title || 'yann',
        body: body,
        icon: String(icon || ''),
        tag: String(tag || '')
      }));
      if (DEBUG) window.__yannDebug.push({ t: Date.now(), title: title, body: body });
      return true;
    } catch (e) {
      if (DEBUG) window.__yannDebug.push({ error: String(e) });
      return false;
    }
  }

  /* ============ 1. Notification 垫片 ============ */

  function YannNotification(title, options) {
    if (!(this instanceof YannNotification)) return new YannNotification(title, options);
    options = options || {};
    this.title = String(title || '');
    this.body = String(options.body || '');
    this.tag = options.tag || '';
    this.icon = options.icon || '';
    this.onclick = null;
    this.onshow = null;
    this.onclose = null;
    this.onerror = null;
    post(this.title, this.body, this.icon, this.tag);
  }
  Object.defineProperty(YannNotification, 'permission', {
    value: 'granted', writable: false, configurable: false
  });
  YannNotification.requestPermission = function (cb) {
    if (typeof cb === 'function') { try { cb('granted'); } catch (e) {} }
    return Promise.resolve('granted');
  };
  YannNotification.maxActions = 0;
  YannNotification.prototype.close = function () {};
  YannNotification.prototype.addEventListener = function () {};
  YannNotification.prototype.removeEventListener = function () {};
  YannNotification.prototype.dispatchEvent = function () {};
  try { window.Notification = YannNotification; } catch (e) {}

  // 站点里形如 `'Notification' in window && Notification.permission === 'granted'`
  // 的判断现在恒成立。
  // 用 defineProperty 强制覆盖：若 WebView 内核其实暴露了只读的 Notification，
  // 普通赋值会静默失败，站点就会走到真实的 Notification 构造函数并抛 Illegal constructor
  // → 被站点的 catch 吃掉 → 界面提示「通知发送失败」。
  try {
    Object.defineProperty(window, 'Notification', {
      value: YannNotification, writable: true, configurable: true
    });
  } catch (e) {
    try { window.Notification = YannNotification; } catch (e2) {}
  }

  /* ============ 1b. 中和 ServiceWorker 通知分支 ============
   * 站点两条通知路径都优先尝试 ServiceWorker：
   *   消息到达：'serviceWorker' in navigator && navigator.serviceWorker.ready
   *               ? ready.then(r => r.showNotification(...)) : new Notification(...)
   *   测试推送：'serviceWorker' in navigator && navigator.serviceWorker.controller
   *               ? await(await ready).showNotification(...) : new Notification(...)
   * WebView 不实现 ServiceWorker，ready 这条 Promise 永远 pending →
   * 消息路径既不成功也不报错，通知静默丢失；测试推送路径 await 失败被 catch
   * → 弹出「通知发送失败」。把这两个属性置空，强制两条路径都走 new Notification()。
   */
  try {
    var sw = navigator.serviceWorker;
    if (sw) {
      try { Object.defineProperty(sw, 'ready', { value: undefined, configurable: true }); } catch (e) {}
      try { Object.defineProperty(sw, 'controller', { value: null, configurable: true }); } catch (e) {}
    }
  } catch (e) {}

  /* ============ 2. Blob 导出截获 ============ */

  var urlToBlob = {};
  try {
    var origCreate = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function (obj) {
      var u = origCreate(obj);
      try {
        if (obj && typeof Blob !== 'undefined' && obj instanceof Blob) urlToBlob[u] = obj;
      } catch (e) {}
      return u;
    };
    var origRevoke = URL.revokeObjectURL.bind(URL);
    URL.revokeObjectURL = function (u) {
      // 站点 click() 后立刻 revoke；先保留 Blob 引用一会儿，
      // Blob 对象本身不受 revoke 影响，仍可读取
      setTimeout(function () { delete urlToBlob[u]; }, 5000);
      return origRevoke(u);
    };
  } catch (e) {}

  function saveBlobAs(href, filename, mime) {
    var blob = urlToBlob[href];
    if (!blob) return false;
    var n = native();
    if (!n || typeof n.saveFile !== 'function') return false;
    try {
      var reader = new FileReader();
      reader.onload = function () {
        try {
          n.saveFile(String(filename || 'yann_export'), String(mime || blob.type || ''), String(reader.result));
        } catch (e) {}
      };
      reader.onerror = function () {};
      reader.readAsDataURL(blob);   // -> data:<mime>;base64,...
      return true;
    } catch (e) {
      return false;
    }
  }

  function anchorHref(a) {
    try { return String(a.href || a.getAttribute('href') || ''); } catch (e) { return ''; }
  }

  function trySaveAnchor(a) {
    var href = anchorHref(a);
    if (href.indexOf('blob:') !== 0) return false;
    var name = '';
    try { name = a.download || a.getAttribute('download') || ''; } catch (e) {}
    return saveBlobAs(href, name, '');
  }

  // 主拦截点：站点是 createElement('a') 后直接 click()，节点从未 append 进 DOM。
  // 游离元素派发的事件不会冒泡/捕获到 document，所以 document 上的监听器根本收不到，
  // 必须直接 patch 原型方法本身。
  try {
    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      try { if (trySaveAnchor(this)) return undefined; } catch (e) {}
      return origAnchorClick.apply(this, arguments);
    };
  } catch (e) {}

  // 次级拦截：真实用户点击（元素已在 DOM 中）
  document.addEventListener('click', function (ev) {
    try {
      var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
      if (!a) return;
      if (trySaveAnchor(a)) {
        ev.preventDefault();
        ev.stopPropagation();
      }
    } catch (e) {}
  }, true);

  // 兜底：有些实现直接 window.open(blobUrl)
  try {
    var origOpen = window.open;
    window.open = function (u, t, f) {
      try {
        if (typeof u === 'string' && u.indexOf('blob:') === 0) {
          if (saveBlobAs(u, 'yann_export', '')) return null;
        }
      } catch (e) {}
      return origOpen.call(window, u, t, f);
    };
  } catch (e) {}

  /* ============ 3. Permissions API 兜底 ============ */

  try {
    if (!navigator.permissions || typeof navigator.permissions.query !== 'function') {
      var perms = { query: function (d) {
        var name = (d && d.name) || '';
        var state = (name === 'microphone' || name === 'camera') ? 'granted' : 'prompt';
        return Promise.resolve({ name: name, state: state, onchange: null });
      }};
      Object.defineProperty(navigator, 'permissions', {
        value: perms, configurable: true, writable: false
      });
    }
  } catch (e) {}
})();
