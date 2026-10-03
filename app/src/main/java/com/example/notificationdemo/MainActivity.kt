package com.example.notificationdemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var notificationHelper: NotificationHelper

    /** 网页通知桥接脚本内容，null 表示加载失败 */
    private var bridgeScript: String? = null

    /** true = 已通过 document-start 注入，无需 onPageFinished 再注 */
    private var bridgeInjectedEarly = false

    /** 等待系统权限授予后再放行的网页媒体请求 */
    private var pendingMediaRequest: PermissionRequest? = null

    /** 网页文件选择回调（实例字段：随 Activity 生命周期，不会指向已销毁的 WebView） */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val TARGET_URL = "https://app-c0d3yeieus5d.appmiaoda.com/"
        private const val BRIDGE_ASSET = "notify_bridge.js"
        private const val BRIDGE_ORIGIN_RULE = "https://app-c0d3yeieus5d.appmiaoda.com"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
        private const val LOCATION_PERMISSION_CODE = 1002
        private const val MEDIA_PERMISSION_CODE = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸式
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        // 初始化通知助手
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannel()

        // 初始化 WebView
        webView = findViewById(R.id.webView)
        setupWebView()
        installNotificationBridge()

        // 检查并申请通知权限
        checkNotificationPermission()

        // 加载目标网页
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(TARGET_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            settings.apply {
                // 基础设置
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true

                // 显示适配
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = false
                displayZoomControls = false

                // 媒体自动播放
                mediaPlaybackRequiresUserGesture = false

                // User-Agent 追加 App 标识
                userAgentString = "$userAgentString NotificationDemo/1.0"

                // 缓存策略
                cacheMode = WebSettings.LOAD_DEFAULT

                // 允许混合内容 (HTTPS 页面加载 HTTP 资源)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // 多窗口支持
                setSupportMultipleWindows(false)

                // Geolocation
                setGeolocationEnabled(true)
            }

            // WebViewClient: 拦截页面跳转，在 WebView 内部打开
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // 外部链接（非目标域名）用系统浏览器打开
                    if (!url.contains("appmiaoda.com") && !url.startsWith("javascript:")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) { }
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 兜底注入：不支持 document-start 的旧 WebView 内核走这里
                    if (!bridgeInjectedEarly) {
                        bridgeScript?.let { view?.evaluateJavascript(it, null) }
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    // 生产环境应移除或改为弹窗确认
                    handler?.proceed()
                }
            }

            // WebChromeClient: 处理弹窗、进度、文件选择
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    findViewById<View>(R.id.progressBar)?.apply {
                        visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    }
                    findViewById<android.widget.ProgressBar>(R.id.progressBar)?.progress = newProgress
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // 先释放上一次未完成的回调，避免网页卡住
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback

                    val intent = try {
                        fileChooserParams?.createIntent()
                    } catch (e: Exception) {
                        null
                    } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    return try {
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (e: Exception) {
                        // 无可用选择器：必须回调 null，否则文件选择器永久失效
                        fileChooserCallback = null
                        filePathCallback?.onReceiveValue(null)
                        Toast.makeText(this@MainActivity, "没有找到可用的文件选择器", Toast.LENGTH_SHORT).show()
                        false
                    }
                }

                // 处理 JS alert
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("提示")
                        .setMessage(message)
                        .setPositiveButton("确定") { _, _ -> result?.confirm() }
                        .setCancelable(false)
                        .show()
                    return true
                }

                // 处理 JS confirm
                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("确认")
                        .setMessage(message)
                        .setPositiveButton("确定") { _, _ -> result?.confirm() }
                        .setNegativeButton("取消") { _, _ -> result?.cancel() }
                        .setCancelable(false)
                        .show()
                    return true
                }

                // Geolocation 权限回调
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        callback?.invoke(origin, true, false)
                    } else {
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ),
                            LOCATION_PERMISSION_CODE
                        )
                        callback?.invoke(origin, true, false)
                    }
                }

                /**
                 * getUserMedia 授权回调（麦克风/摄像头）。
                 *
                 * WebView 默认拒绝页面的媒体采集请求，且必须在这里显式 grant；
                 * 只在系统设置里给 App 开麦克风权限是不够的。
                 */
                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    runOnUiThread {
                        val resources = request.resources
                        val wantsAudio =
                            resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        val wantsVideo =
                            resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

                        val missing = mutableListOf<String>()
                        if (wantsAudio && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                            missing.add(Manifest.permission.RECORD_AUDIO)
                        }
                        if (wantsVideo && !hasPermission(Manifest.permission.CAMERA)) {
                            missing.add(Manifest.permission.CAMERA)
                        }

                        if (missing.isEmpty()) {
                            // 系统权限已具备，放行网页的采集请求
                            request.grant(resources)
                        } else {
                            // 先申请系统运行时权限，授权成功后再放行
                            pendingMediaRequest = request
                            ActivityCompat.requestPermissions(
                                this@MainActivity, missing.toTypedArray(),
                                MEDIA_PERMISSION_CODE
                            )
                        }
                    }
                }
            }

            // 启用调试（发布时移除）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
    }

    // ==================== 网页通知桥接 ====================

    /**
     * 注入 notify_bridge.js 并挂上 AndroidNotify 接口。
     *
     * 为什么需要：Android WebView 未实现 Web Notifications API，
     * window.Notification 为 undefined，网页只能退化成自绘的 DOM 横幅。
     * 这里把 Notification 调用（以及 DOM 浮动横幅的文本）转成系统通知。
     */
    private fun installNotificationBridge() {
        bridgeScript = loadBridgeScript()
        if (bridgeScript == null) return

        webView.addJavascriptInterface(NotifyBridge(), "AndroidNotify")

        // 首选 document-start：在网页脚本执行前注入，
        // 这样页面启动时做的 'Notification' in window 特性探测也能命中垫片
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView, bridgeScript!!, setOf(BRIDGE_ORIGIN_RULE)
                )
                bridgeInjectedEarly = true
            } catch (e: Exception) {
                bridgeInjectedEarly = false
            }
        }
    }

    private fun loadBridgeScript(): String? = try {
        assets.open(BRIDGE_ASSET).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /** 网页侧调用入口，注意 @JavascriptInterface 必需 */
    inner class NotifyBridge {
        @JavascriptInterface
        fun postMessage(payload: String) {
            try {
                val json = org.json.JSONObject(payload)
                val title = json.optString("title").ifBlank { "yann" }
                val body = json.optString("body")
                if (body.isBlank() && title == "yann") return
                runOnUiThread {
                    notificationHelper.sendWebNotification(
                        title = title,
                        content = body,
                        targetActivity = MainActivity::class.java
                    )
                }
            } catch (e: Exception) {
                // 载荷异常直接忽略，不影响网页
            }
        }
    }

    // ==================== 权限管理 ====================

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                ) {
                    // 已拒绝过一次，弹窗解释
                    AlertDialog.Builder(this)
                        .setTitle("通知权限")
                        .setMessage("应用需要通知权限来推送重要消息，请在接下来的弹窗中允许通知。")
                        .setPositiveButton("好的") { _, _ ->
                            requestNotificationPermission()
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    requestNotificationPermission()
                }
            } else {
                // 权限已授予，检查是否真的开启了通知
                if (!notificationHelper.areNotificationsEnabled()) {
                    showNotificationDisabledDialog()
                }
            }
        } else {
            // Android 12 及以下，检查通知是否开启
            if (!notificationHelper.areNotificationsEnabled()) {
                showNotificationDisabledDialog()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_CODE
            )
        }
    }

    private fun showNotificationDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle("通知未开启")
            .setMessage("通知权限未开启，将无法收到重要消息提醒。\n是否前往设置开启？")
            .setPositiveButton("去设置") { _, _ ->
                // 小米/MIUI 系统优先尝试跳转 MIUI 专属页面
                if (isXiaomiDevice()) {
                    notificationHelper.openMiuiAppDetails()
                } else {
                    notificationHelper.openNotificationSettings()
                }
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }

    /**
     * 检测是否为小米设备 (HyperOS / MIUI)
     */
    private fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        return manufacturer == "xiaomi" || manufacturer == "redmi" ||
                brand == "xiaomi" || brand == "redmi" || brand == "poco"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "通知权限被拒绝，可在系统设置中手动开启", Toast.LENGTH_LONG).show()
                }
            }
            LOCATION_PERMISSION_CODE -> {
                // 定位权限处理
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "定位权限被拒绝，部分网页功能可能受限", Toast.LENGTH_SHORT).show()
                }
            }
            MEDIA_PERMISSION_CODE -> {
                val granted = grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    // 系统权限到手，放行网页等待中的 getUserMedia
                    pendingMediaRequest?.let { req ->
                        try {
                            req.grant(req.resources)
                        } catch (e: Exception) { /* 请求可能已失效 */ }
                    }
                } else {
                    try {
                        pendingMediaRequest?.deny()
                    } catch (e: Exception) { /* ignore */ }
                    Toast.makeText(this, "麦克风/相机权限被拒绝，语音功能不可用", Toast.LENGTH_LONG).show()
                }
                pendingMediaRequest = null
            }
        }
    }

    // ==================== 文件选择（表情/图片导入） ====================

    /**
     * 选择结果落地。
     *
     * 上一版导入失败的根因组合：
     * 1. parseResult 对部分国产 ROM 相册返回的结果会解析出 null，回调拿到 null 就静默失败
     * 2. 回调存在 companion object（静态）里，Activity 重建后指向已销毁的 WebView
     * 3. 没有为返回的 content:// URI 申请读权限
     */
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult

            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                grantReadPermission(data)
            }

            val uris = WebChromeClient.FileChooserParams
                .parseResult(result.resultCode, data)
                ?: extractUrisManually(result.resultCode, data)

            callback.onReceiveValue(uris)
        }

    /** 给返回的 URI 申请（可持久化的）读权限，保证 WebView 稍后仍能读取 */
    private fun grantReadPermission(data: Intent) {
        val candidates = mutableListOf<Uri>()
        data.data?.let { candidates.add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) candidates.add(clip.getItemAt(i).uri)
        }
        candidates.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 非持久化授权来源，忽略；一次性授权仍随 Intent 传递
            }
        }
    }

    /** parseResult 解析不出来时的手工兜底：覆盖 data / clipData / extras 三种返回形态 */
    private fun extractUrisManually(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val uris = LinkedHashSet<Uri>()
        try {
            data.data?.let { uris.add(it) }
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
            // 部分相册把结果放在 extras 里
            val extras = data.extras ?: Bundle.EMPTY
            for (key in extras.keySet()) {
                when (val v = extras.get(key)) {
                    is Uri -> uris.add(v)
                    is ArrayList<*> -> v.forEach { if (it is Uri) uris.add(it) }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return if (uris.isEmpty()) null else uris.toTypedArray()
    }

    // ==================== 生命周期 ====================

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // App 回到前台，不再需要保活服务
        stopService(Intent(this, KeepAliveService::class.java))
    }

    override fun onPause() {
        super.onPause()
        // 故意不调用 webView.onPause()：页面 JS 保持运行，消息才能继续到达
        // 启动前台服务提高进程优先级，避免被系统杀掉
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        // 释放未完成的选择器回调与媒体授权请求，避免 WebView 状态卡死
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        try {
            pendingMediaRequest?.deny()
        } catch (e: Exception) { /* ignore */ }
        pendingMediaRequest = null
        webView.destroy()
        super.onDestroy()
    }

    // ==================== 返回键处理 ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
