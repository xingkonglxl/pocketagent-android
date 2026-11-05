package app.pocketagent.android

import android.app.Application
import java.security.Security

class PocketAgentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // PDFBox 提取 PDF 文本前必须先初始化资源加载器
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        installFullBouncyCastle()
        container = AppContainer(this)
    }

    /**
     * Android 自带一个精简版 "BC" provider，缺 X25519/Ed25519，sshj 连 OpenSSH（默认 curve25519）会报
     * 「no such algorithm: X25519 for provider BC」。这里把随包的完整 BouncyCastle 顶到第一位，
     * 只在它确实带 X25519 时才替换，避免影响系统原有的加密实现。
     */
    private fun installFullBouncyCastle() {
        runCatching {
            val bundled = org.bouncycastle.jce.provider.BouncyCastleProvider()
            val hasX25519 = bundled.getService("KeyAgreement", "X25519") != null ||
                bundled.getService("KeyPairGenerator", "X25519") != null
            if (!hasX25519) return
            Security.removeProvider("BC")
            Security.insertProviderAt(bundled, 1)
            net.schmizz.sshj.common.SecurityUtils.setSecurityProvider("BC")
        }
    }
}
