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
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var notificationHelper: NotificationHelper

    companion object {
        const val TARGET_URL = "https://app-c0d3yeieus5d.appmiaoda.com/"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
        private const val LOCATION_PERMISSION_CODE = 1002
        private const val CAMERA_PERMISSION_CODE = 1003

        // WebView 文件选择器回调
        private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
        private const val FILE_CHOOSER_REQUEST = 1004
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

        // 检查并申请通知权限
        checkNotificationPermission()

        // 发送一条测试通知（可移除）
        findViewById<Button>(R.id.btnTestNotification)?.setOnClickListener {
            notificationHelper.sendNotification(
                title = "通知测试",
                content = "通知弹窗功能已就绪！点击可返回应用。",
                targetActivity = MainActivity::class.java
            )
            Toast.makeText(this, "通知已发送", Toast.LENGTH_SHORT).show()
        }

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
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    val intent = fileChooserParams?.createIntent() ?: run {
                        fileChooserCallback = null
                        return false
                    }
                    try {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        return false
                    }
                    return true
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
            }

            // 启用调试（发布时移除）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
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
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "相机权限被拒绝", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            fileChooserCallback?.onReceiveValue(
                if (resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            )
            fileChooserCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ==================== 生命周期 ====================

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
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
