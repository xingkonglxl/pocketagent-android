package app.pocketagent.android.data

import android.content.Context

enum class ThemeMode { SYSTEM, DARK, LIGHT }

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", "DARK")!!) }
            .getOrDefault(ThemeMode.DARK)
        set(value) = prefs.edit().putString("theme", value.name).apply()

    /** 界面语言：system（跟随系统）/ zh / en */
    var language: String
        get() = prefs.getString("language", "system") ?: "system"
        set(value) = prefs.edit().putString("language", value).apply()

    /** 打开 App 需要指纹/面容解锁 */
    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(value) = prefs.edit().putBoolean("app_lock", value).apply()

    /** 禁止截屏 / 最近任务里隐藏预览 */
    var secureScreen: Boolean
        get() = prefs.getBoolean("secure_screen", false)
        set(value) = prefs.edit().putBoolean("secure_screen", value).apply()

    /**
     * 已同意的隐私政策/用户协议版本号。0 表示还没同意过——首次启动必须确认后才能使用；
     * 协议有实质修改时把 Legal.VERSION 加一，用户会重新看到确认弹窗。
     */
    var legalVersion: Int
        get() = prefs.getInt("legal_version", 0)
        set(value) = prefs.edit().putInt("legal_version", value).apply()

    var lastProviderId: String?
        get() = prefs.getString("last_provider", null)
        set(value) = prefs.edit().putString("last_provider", value).apply()

    /**
     * 上一次同步/冲突处理的结果。提示条几秒就消失，用户在设置里翻回来还能看到原因，
     * 排查「同步失败」时可以直接复制发给别人。
     */
    var syncLastResult: String
        get() = prefs.getString("sync_last_result", "") ?: ""
        set(value) = prefs.edit().putString("sync_last_result", value).apply()

    var syncLastResultAt: Long
        get() = prefs.getLong("sync_last_result_at", 0L)
        set(value) = prefs.edit().putLong("sync_last_result_at", value).apply()

    /** 工作区放哪：null = 手机本地；否则是远程主机 id（remote-host / remote-host-b / Mac） */
    var remoteWorkspaceHostId: String?
        get() = prefs.getString("remote_workspace_host", null)
        set(value) = prefs.edit().putString("remote_workspace_host", value).apply()

    /** 远端工作区目录，默认 ~/pocketagent */
    var remoteWorkspaceDir: String
        get() = prefs.getString("remote_workspace_dir", "~/pocketagent") ?: "~/pocketagent"
        set(value) = prefs.edit().putString("remote_workspace_dir", value).apply()

    /** 远端 Codex harness 是否把 reasoning 文本传回并显示；默认关闭以节省手机流量。 */
    var showRemoteThinking: Boolean
        get() = prefs.getBoolean("show_remote_thinking", false)
        set(value) = prefs.edit().putBoolean("show_remote_thinking", value).apply()

    /** 历史上下文同步目标：必须是用户明确选择的远程主机 id；null 表示还没选 */
    var syncHostId: String?
        get() = prefs.getString("sync_host_id", null)
        set(value) = prefs.edit().putString("sync_host_id", value).apply()

    /**
     * 每轮结束后把当前会话镜像到远端工作区（sessions/<id>.md），
     * 方便在远程机器上直接看到手机产生的最新上下文。
     * 默认关闭：会话里可能有主机密码等敏感内容，必须由用户显式开启。
     */
    var mirrorSessionsToRemote: Boolean
        get() = prefs.getBoolean("mirror_sessions_remote", false)
        set(value) = prefs.edit().putBoolean("mirror_sessions_remote", value).apply()
}
