package app.pocketagent.android

import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import app.pocketagent.android.ui.AppRoot
import app.pocketagent.android.ui.i18n.L10n
import app.pocketagent.android.ui.i18n.LocalLang
import app.pocketagent.android.ui.theme.PocketAgentTheme
import java.util.Locale

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+ 需要运行时授权才能显示“回复进行中”的前台通知；
        // 即使拒绝，前台服务仍能保活，只是通知不展示。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        val container = (application as PocketAgentApp).container
        val activity = this
        setContent {
            var themeMode by remember { mutableStateOf(container.settings.theme) }
            var language by remember { mutableStateOf(container.settings.language) }
            val resolvedLang = L10n.resolve(language, Locale.getDefault().toLanguageTag())
            L10n.current = resolvedLang
            CompositionLocalProvider(LocalLang provides resolvedLang) {
                PocketAgentTheme(themeMode) {
                    AppRoot(
                        container = container,
                        activity = activity,
                        themeMode = themeMode,
                        onThemeChange = {
                            container.settings.theme = it
                            themeMode = it
                        },
                        language = language,
                        onLanguageChange = {
                            container.settings.language = it
                            language = it
                        },
                    )
                }
            }
        }
    }
}
