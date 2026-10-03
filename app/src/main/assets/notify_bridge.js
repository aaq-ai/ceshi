/*
 * yann 通知桥接脚本 v3
 *
 * 三层能力：
 *   L1 Web Notifications API 垫片  —— 网页调用 new Notification() 时转系统通知
 *   L2 浮动提示捕获（fixed/absolute/sticky）—— 抓取 toast 与页面自带横幅
 *   L3 聊天气泡捕获 —— 新增：抓取消息列表里新出现的「左侧/对方」气泡
 *
 * 关键背景（上一版为什么漏掉消息）：
 *   聊天气泡在文档流里是 position: static，不属于 L2 的浮动元素；
 *   页面自带的顶部消息条常用 sticky，也被上一版过滤器排除。
 */
(function () {
  'use strict';
  if (window.__yannBridge) return;
  window.__yannBridge = true;

  var DEBUG = false;
  window.__yannDebug = [];

  /* ---------- 发往 Native ---------- */
  function post(title, body, tag) {
    body = String(body || '').trim();
    title = String(title || '').trim();
    if (!body && !title) return false;
    try {
      if (window.AndroidNotify && typeof window.AndroidNotify.postMessage === 'function') {
        window.AndroidNotify.postMessage(JSON.stringify({
          title: title || 'yann',
          body: body,
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

  /* ---------- 去重 ---------- */
  var recent = {};
  var DEDUP_MS = 6000;

  function duplicated(text) {
    var key = text.replace(/\s+/g, '').slice(0, 100);
    if (!key) return true;
    var now = Date.now();
    if (recent[key] && now - recent[key] < DEDUP_MS) return true;
    recent[key] = now;
    if (Object.keys(recent).length > 200) {
      // 清掉过期项，避免内存增长
      var fresh = {};
      Object.keys(recent).forEach(function (k) {
        if (now - recent[k] < DEDUP_MS) fresh[k] = recent[k];
      });
      recent = fresh;
    }
    return false;
  }

  function cleanText(el, max) {
    var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
    max = max || 400;
    if (!t || t.length > max) return '';
    return t;
  }

  // 过滤掉不像消息的内容：时间戳、状态提示等
  var NOISE_RE = /^(\d{1,2}:\d{2}(:\d{2})?|通话时长.*|对方正在输入.*|已读|未读|发送失败|重新发送|撤回了一条消息|加载更多|\+|-|×|⋯|\.{2,}|…)$/i;

  function isNoise(t) {
    if (!t) return true;
    if (NOISE_RE.test(t)) return true;
    if (t.length === 1 && !/[\u4e00-\u9fa5a-zA-Z0-9]/.test(t)) return true;
    return false;
  }

  /* ---------- 会话名称（作为通知标题） ---------- */
  var NAME_HINT = /(title|header|name|nick|contact|peer)/i;
  var cachedName = '';
  var nameAt = 0;

  function conversationName() {
    var now = Date.now();
    if (cachedName && now - nameAt < 15000) return cachedName;
    var found = '';
    try {
      var cands = document.querySelectorAll('[class],[id]');
      for (var i = 0; i < cands.length && i < 3000; i++) {
        var el = cands[i];
        var hint = (el.className && String(el.className)) + ' ' + (el.id || '');
        if (!NAME_HINT.test(hint)) continue;
        var cs = window.getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;
        var t = cleanText(el, 30);
        if (!t || isNoise(t) || /\s/.test(t)) continue;   // 名字一般不含空格
        var r = el.getBoundingClientRect();
        if (r.top > 200 || r.width <= 0) continue;        // 只看顶部区域
        found = t;
        break;
      }
    } catch (e) { /* ignore */ }
    if (!found) {
      try {
        var dt = (document.title || '').trim();
        if (dt && dt.length <= 30 && !/yann/i.test(dt)) found = dt;
      } catch (e) { /* ignore */ }
    }
    cachedName = found;
    nameAt = now;
    return found;
  }

  /* ---------- 左右对齐判定（区分对方/自己） ---------- */
  var SELF_RE = /(self|mine|-me|own|right|out-?going|out_msg|sent|sender-me|by-me)/i;
  var OTHER_RE = /(other|friend|recv|receive|in-?coming|left|peer|bot|ai|assistant)/i;

  function alignOf(el) {
    try {
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return 'none';
      var w = window.innerWidth || document.documentElement.clientWidth || 1;
      var center = (r.left + r.width / 2) / w;
      var isNarrow = r.width <= w * 0.8;
      if (!isNarrow) return 'full';
      return center < 0.56 ? 'left' : 'right';
    } catch (e) {
      return 'none';
    }
  }

  function classHint(el) {
    var s = '';
    try {
      var n = el, depth = 0;
      while (n && n.nodeType === 1 && depth < 4) {
        s += ' ' + (n.className ? String(n.className) : '') + ' ' + (n.id || '');
        n = n.parentElement;
        depth++;
      }
    } catch (e) { /* ignore */ }
    return s;
  }

  /* ================= L1：Notification 垫片 ================= */
  function YannNotification(title, options) {
    if (!(this instanceof YannNotification)) return new YannNotification(title, options);
    options = options || {};
    this.title = String(title || '');
    this.body = String(options.body || '');
    this.tag = options.tag || '';
    this.onclick = null;
    this.onshow = null;
    this.onclose = null;
    this.onerror = null;
    post(this.title || conversationName() || 'yann', this.body, this.tag || 'web');
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

  /* ================= L2：浮动提示 / 页面自带横幅 ================= */
  var HINT_RE = /(toast|notification|notice|banner|snack|alert|popup|push|inbox|preview|remind|message|msg|tip)/i;

  function floatingKind(el) {
    try {
      var cs = window.getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') return '';
      if (parseFloat(cs.opacity) < 0.05) return '';
      var pos = cs.position;
      if (pos === 'fixed' || pos === 'absolute' || pos === 'sticky') return pos;
      return '';
    } catch (e) { return ''; }
  }

  function fromFloating(node) {
    var el = node;
    var pos = floatingKind(el);
    if (!pos) {
      var inner = null;
      try { inner = el.querySelector('[role="alert"],[role="status"],[aria-live],[class]'); } catch (e) {}
      if (inner) {
        pos = floatingKind(inner);
        if (!pos) return null;
        el = inner;
      } else return null;
    }
    var text = cleanText(el, 300);
    if (isNoise(text)) return null;
    return text;
  }

  /* ================= L3：聊天气泡 ================= */
  function fromBubble(node) {
    if (node.nodeType !== 1) return null;
    var el = node;
    // 若新增的是外层容器，往里找真正的消息行
    var text = cleanText(el, 400);
    if (isNoise(text)) {
      var kids = el.children;
      if (kids && kids.length) {
        // 只处理「新行只有一个」的情况，避免整列表重建时刷屏
        if (kids.length !== 1) return null;
        el = kids[0];
        text = cleanText(el, 400);
        if (isNoise(text)) return null;
      } else return null;
    }

    var hint = classHint(el);
    if (SELF_RE.test(hint)) return null;          // 自己发的不通知

    var a = alignOf(el);
    var looksIncoming = (a === 'left') || OTHER_RE.test(hint);
    if (!looksIncoming) return null;               // 右侧/自己 → 跳过

    // 整行文本里可能混着头像字母/时间，尽力切出正文
    var body = text;
    try {
      var bubble = el.querySelector('[class*="bubble"],[class*="content"],[class*="text"],[class*="msg"]');
      var bt = bubble ? cleanText(bubble, 400) : '';
      if (bt && bt.length <= text.length && text.indexOf(bt) >= 0) body = bt;
    } catch (e) {}
    if (isNoise(body)) body = text;

    return { body: body, name: conversationName() };
  }

  /* ================= 观察器 ================= */
  var warmupUntil = Date.now() + 3000;   // 冷启动/刷新时历史消息不刷屏
  var observer = new MutationObserver(function (muts) {
    if (Date.now() < warmupUntil) {
      // 预热期：只登记文本，不发通知
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          if (added[j].nodeType === 1) {
            var t = cleanText(added[j], 400);
            if (t) duplicated(t);
          }
        }
      }
      return;
    }
    for (var m = 0; m < muts.length; m++) {
      var nodes = muts[m].addedNodes;
      for (var k = 0; k < nodes.length; k++) {
        var n = nodes[k];
        if (n.nodeType !== 1) continue;
        try {
          // L3 优先：聊天气泡带发送者信息
          var b = fromBubble(n);
          if (b && !duplicated(b.body)) {
            post(b.name || 'yann', b.body, 'bubble');
            continue;
          }
          // L2：浮动提示
          var f = fromFloating(n);
          if (f && !duplicated(f)) {
            post(conversationName() || 'yann', f, 'float');
          }
        } catch (e) { /* 单个节点异常不影响其他 */ }
      }
    }
  });

  function seedSeen() {
    // 把已有消息登记为「已见」，历史消息不会在预热期后被重复推送
    try {
      var all = document.querySelectorAll('[class*="bubble"],[class*="msg"],[class*="message"],li,p');
      for (var i = 0; i < all.length && i < 800; i++) {
        var t = cleanText(all[i], 400);
        if (t) duplicated(t);
      }
    } catch (e) {}
  }

  function observeBody() {
    if (document.body) {
      seedSeen();
      observer.observe(document.body, { childList: true, subtree: true });
      // 页面内导航/重新加载后重新预热
      window.addEventListener('load', function () {
        warmupUntil = Date.now() + 2500;
        seedSeen();
      }, false);
    } else {
      setTimeout(observeBody, 120);
    }
  }
  observeBody();
})();
