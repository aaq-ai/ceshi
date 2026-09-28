/*
 * yann 通知桥接脚本
 * 由 Native 侧在文档开始时注入，把网页的通知调用转成 Android 系统通知。
 *
 * 背景：Android WebView 不实现 Web Notifications API，window.Notification 为 undefined，
 * 因此网页会退化成自绘的顶部横幅。本脚本提供两层能力：
 *   第 1 层：Notification 垫片 —— 网页若调用 new Notification()/requestPermission() 直接走系统通知
 *   第 2 层：DOM 观察兜底 —— 网页只用 HTML 横幅时，捕获浮动元素文本并发系统通知
 * 注入点：assets/notify_bridge.js，幂等（重复注入只生效一次）
 */
(function () {
  'use strict';
  if (window.__yannBridge) return;
  window.__yannBridge = true;

  var DEBUG = false;
  window.__yannDebug = [];

  function post(title, body, tag) {
    try {
      if (window.AndroidNotify && typeof window.AndroidNotify.postMessage === 'function') {
        window.AndroidNotify.postMessage(JSON.stringify({
          title: String(title || 'yann'),
          body: String(body || ''),
          tag: String(tag || '')
        }));
        if (DEBUG) window.__yannDebug.push({ t: Date.now(), title: title, body: body, tag: tag });
        return true;
      }
    } catch (e) {
      if (DEBUG) window.__yannDebug.push({ error: String(e) });
    }
    return false;
  }

  /* ---------- 第 1 层：Web Notifications API 垫片 ---------- */

  var seq = 0;

  function YannNotification(title, options) {
    if (!(this instanceof YannNotification)) {
      return new YannNotification(title, options);
    }
    options = options || {};
    this.title = String(title || '');
    this.body = String(options.body || '');
    this.tag = options.tag || '';
    this.icon = options.icon || '';
    this.onclick = null;
    this.onshow = null;
    this.onclose = null;
    this.onerror = null;
    this._id = ++seq;
    post(this.title, this.body, this.tag || 'web');
  }

  YannNotification.permission = 'granted';
  Object.defineProperty(YannNotification, 'permission', {
    value: 'granted', writable: false, configurable: false
  });

  YannNotification.requestPermission = function (callback) {
    if (typeof callback === 'function') {
      try { callback('granted'); } catch (e) { /* ignore */ }
    }
    return Promise.resolve('granted');
  };

  YannNotification.maxActions = 0;
  YannNotification.prototype.close = function () {};
  YannNotification.prototype.addEventListener = function () {};
  YannNotification.prototype.removeEventListener = function () {};
  YannNotification.prototype.dispatchEvent = function () {};

  try {
    window.Notification = YannNotification;
  } catch (e) { /* ignore */ }

  /* ---------- 第 2 层：DOM 浮动横幅兜底 ---------- */

  var recent = {};

  function duplicated(text) {
    var key = text.slice(0, 120);
    var now = Date.now();
    if (recent[key] && now - recent[key] < 2500) return true;
    recent[key] = now;
    // 防止对象无限增长
    if (Object.keys(recent).length > 60) recent = {};
    return false;
  }

  var ROLE_RE = /(^|\s)(alert|status|log)(\s|$)/i;
  var HINT_RE = /(toast|notification|notice|banner|snack|alert|popup|push|inbox|preview|remind)/i;

  function looksFloaty(el) {
    try {
      var cs = window.getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
      if (cs.display === 'none' || cs.visibility === 'hidden') return false;
      var z = parseInt(cs.zIndex, 10);
      // 浮动通知一般在顶层；z-index 缺省时仍按浮动处理
      return isNaN(z) || z >= 1;
    } catch (e) {
      return false;
    }
  }

  function isBanner(el) {
    if (el.nodeType !== 1) return false;
    if (!looksFloaty(el)) return false;
    try {
      var role = el.getAttribute('role') || '';
      if (role && ROLE_RE.test(' ' + role + ' ')) return true;
      if (el.getAttribute('aria-live')) return true;
      var hint = (el.className && String(el.className)) + ' ' + (el.id || '');
      if (HINT_RE.test(hint)) return true;
    } catch (e) { /* ignore */ }
    return false;
  }

  function cleanText(el) {
    var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
    if (!t || t.length > 200) return '';
    return t;
  }

  // 尽量把「发送者 内容」拆开：优先用第一个子元素当作发送者
  function splitTitleBody(el, text) {
    var title = 'yann';
    var body = text;
    try {
      var kids = el.children;
      if (kids && kids.length >= 2) {
        var first = cleanText(kids[0]);
        if (first && first.length <= 24 && text.indexOf(first) === 0) {
          title = first;
          body = text.slice(first.length).trim() || text;
          return { title: title, body: body };
        }
      }
    } catch (e) { /* ignore */ }
    return { title: title, body: body };
  }

  function handleAdded(node) {
    if (node.nodeType !== 1) return;
    var el = node;
    if (!isBanner(el)) {
      var inner = null;
      try {
        inner = el.querySelector('[role="alert"],[role="status"],[aria-live]');
      } catch (e) { /* ignore */ }
      if (!inner) return;
      el = inner;
    }
    var text = cleanText(el);
    if (!text || duplicated(text)) return;
    var parts = splitTitleBody(el, text);
    post(parts.title, parts.body, 'dom');
  }

  var observer = new MutationObserver(function (mutations) {
    for (var i = 0; i < mutations.length; i++) {
      var added = mutations[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        try { handleAdded(added[j]); } catch (e) { /* ignore */ }
      }
    }
  });

  function observeBody() {
    if (document.body) {
      observer.observe(document.body, { childList: true, subtree: true });
    } else {
      setTimeout(observeBody, 120);
    }
  }
  observeBody();
})();
