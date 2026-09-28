package com.example.notificationdemo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * 通知工具类
 *
 * 功能：
 * 1. 创建通知渠道 (NotificationChannel)，IMPORTANCE_HIGH 实现弹窗
 * 2. 发送通知弹窗
 * 3. 检查通知权限
 * 4. 跳转系统通知设置
 * 5. 小米 HyperOS/MIUI 专属设置跳转
 *
 * ── 小米 (HyperOS/MIUI) 通知 & 悬浮窗/锁屏通知 权限开启指南 ──
 *
 * 【方法一：通过应用内跳转（本工具类已实现）】
 *   调用 openMiuiAppDetails() 可直接跳转到 MIUI「应用管理 → 本应用」页面，
 *   在该页面中用户可以开启：
 *     - 通知管理 → 允许通知
 *     - 通知管理 → 悬浮通知 (banner 样式弹窗)
 *     - 通知管理 → 锁屏通知
 *     - 权限管理 → 后台弹出界面 (悬浮窗权限)
 *
 * 【方法二：用户手动操作路径】
 *   1. 打开「设置」→「应用设置」→「应用管理」
 *   2. 找到并点击本应用
 *   3. 点击「通知管理」：
 *      - 开启「允许通知」总开关
 *      - 点击对应通知渠道 → 开启「悬浮通知」和「锁屏通知」
 *      - 将「重要程度」设为「紧急」以确保弹窗
 *   4. 点击「权限管理」：
 *      - 开启「后台弹出界面」(即悬浮窗权限，部分弹窗通知需要)
 *      - 开启「锁屏显示」(锁屏通知需要)
 *   5. 点击「自启动管理」(HyperOS 3.0)：
 *      - 允许应用自启动，确保后台通知不被杀
 *
 * 【方法三：通过安全中心】
 *   部分 MIUI/HyperOS 版本可通过：
 *   「安全中心」→「应用管理」→「权限」→「后台弹出界面」→ 找到本应用 → 允许
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "notification_popup_channel"
        const val CHANNEL_NAME = "yann 消息通知"
        const val CHANNEL_DESC = "高优先级通知，将以弹窗形式展示"
        const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE = 1001
        private const val WEB_NOTIFICATION_ID_BASE = 2000
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 网页消息通知自增 id，保证多条消息各自独立、不互相覆盖 */
    private var webNotifyId = WEB_NOTIFICATION_ID_BASE

    // ──────────────────────────────────────────
    // 1. 创建通知渠道 (Android 8.0+ 必需)
    // ──────────────────────────────────────────
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH  // 高重要性 → 弹窗 + 声音
            ).apply {
                description = CHANNEL_DESC
                enableLights(true)
                lightColor = 0xFF4CAF50.toInt()
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setShowBadge(true)
                // 锁屏通知显示完整内容
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ──────────────────────────────────────────
    // 2. 发送通知弹窗
    // ──────────────────────────────────────────
    fun sendNotification(title: String, content: String, targetActivity: Class<*>) {
        val intent = Intent(context, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)       // 兼容 Android 7.x
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏可见
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)         // 声音 + 振动 + 灯光
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ──────────────────────────────────────────
    // 2b. 发送网页消息通知（每条独立 id，可堆叠）
    // ──────────────────────────────────────────
    fun sendWebNotification(title: String, content: String, targetActivity: Class<*>) {
        val intent = Intent(context, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            webNotifyId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(webNotifyId, notification)
        if (webNotifyId < Int.MAX_VALUE - 1) webNotifyId++
    }

    // ──────────────────────────────────────────
    // 3. 检查通知权限
    // ──────────────────────────────────────────
    fun areNotificationsEnabled(): Boolean {
        return notificationManager.areNotificationsEnabled()
    }

    // ──────────────────────────────────────────
    // 4. 跳转到系统通知设置页面
    // ──────────────────────────────────────────
    fun openNotificationSettings() {
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    // Android 8.0+：直接跳转到应用通知设置
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
                    // Android 5.0-7.x：跳转到应用详情页
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.fromParts("package", context.packageName, null)
                }
                else -> {
                    action = Settings.ACTION_APPLICATION_SETTINGS
                }
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 兜底：通用设置页
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
    }

    // ──────────────────────────────────────────
    // 5. 小米 HyperOS / MIUI 专属设置跳转
    // ──────────────────────────────────────────

    /**
     * 跳转到小米「应用管理 → 本应用」页面
     * 用户可在此开启：通知、悬浮窗、锁屏通知、自启动等权限
     */
    fun openMiuiAppDetails() {
        // 尝试 MIUI 应用管理页面
        val miuiIntents = listOf(
            // HyperOS 3.0 / MIUI 14+
            Intent().apply {
                action = "miui.intent.action.APP_PERM_EDITOR"
                putExtra("extra_pkgname", context.packageName)
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            // MIUI 12/13
            Intent().apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
            },
            // 通用 MIUI 应用详情
            Intent().apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.appdetail.AppDetailActivity"
                )
                putExtra("package_name", context.packageName)
            }
        )

        for (intent in miuiIntents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                continue
            }
        }

        // 全部失败，回退到标准 Android 通知设置
        openNotificationSettings()
    }

    /**
     * 直接跳转到小米「悬浮窗/后台弹出界面」权限页面
     * (部分 HyperOS/MIUI 版本支持)
     */
    fun openMiuiOverlayPermission() {
        val intent = Intent().apply {
            action = "miui.intent.action.APP_PERM_EDITOR"
            putExtra("extra_pkgname", context.packageName)
            putExtra("extra_perm_type", "overlay")
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 回退到通用悬浮窗设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
            } else {
                openNotificationSettings()
            }
        }
    }

    /**
     * 跳转到小米「自启动管理」页面 (HyperOS 3.0)
     */
    fun openMiuiAutoStart() {
        val intent = Intent().apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.autostart.AutoStartManagementActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openNotificationSettings()
        }
    }
}
