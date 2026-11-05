package app.pocketagent.android.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 国产 ROM（荣耀/小米等）默认限制后台，前台服务和唤醒锁也可能被冻结。
 * 这里只做两件事：查是否已在电池优化白名单里，以及跳到系统设置。
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }.getOrDefault(false)

    /** 打不开白名单列表时退回应用详情页（部分 ROM 入口位置不一样）。 */
    fun openSettings(context: Context) {
        val opened = runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
    }
}
