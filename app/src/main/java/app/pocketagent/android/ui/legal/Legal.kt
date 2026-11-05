package app.pocketagent.android.ui.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.pocketagent.android.BuildConfig
import app.pocketagent.android.ui.i18n.tr

/**
 * 上架必需的文本：隐私政策、用户协议、开源许可。
 *
 * 主体名称 / 联系邮箱 / 备案号在打包时用 -PoperatorName=... 注入，未填时显示占位并提示
 * 运营方在发布前必须补齐——商店审核会逐项核对。
 */
object LegalDocs {
    private const val PLACEHOLDER = "（发布前需填写）"
    private val operator: String
        get() = BuildConfig.OPERATOR_NAME.ifBlank { PLACEHOLDER }
    private val email: String
        get() = BuildConfig.CONTACT_EMAIL.ifBlank { PLACEHOLDER }
    private val icp: String
        get() = BuildConfig.ICP_NUMBER.ifBlank { PLACEHOLDER }
    private val beian: String
        get() = BuildConfig.APP_BEIAN.ifBlank { PLACEHOLDER }

    fun privacy(): String = """
        PocketAgent 隐私政策

        生效日期：2026 年 1 月 1 日
        运营主体：$operator
        联系方式：$email
        ICP 备案号：$icp
        App 备案号：$beian

        一、我们处理哪些信息
        1. 你自行填写的模型 API Key、接口地址、SSH 主机与私钥：仅保存在本机，用 Android
           Keystore 中的 AES-GCM 主密钥加密（主密钥不可导出），应用已关闭系统备份，
           卸载应用即随应用数据一并删除。
        2. 对话内容与工作区文件：默认保存在本应用私有目录，仅在手机本地处理。
        3. 你主动选择同步时，会话记录会在本机与「你自己指定的电脑」之间加密传输
           （SSH 通道，首次连接需你确认主机指纹）；我们不运营中转服务器，内容不经过我们。
        4. 崩溃与运行日志：仅写在本机日志缓冲区，不自动上传；日志不记录对话正文与 Key。

        二、我们不收集什么
        我们不收集通讯录、短信、通话记录、位置、设备标识符（IMEI/IDFA）、剪贴板，
        不集成任何广告或第三方统计 SDK。应用仅申请「联网」与「网络状态」两项运行时权限；
        另外为可选的「应用锁」功能声明了指纹/面容权限，生物识别结果由系统在本机校验，
        我们不会读取、存储或上传任何生物特征数据。

        三、信息如何使用与共享
        你输入的对话会由本应用直接发送到你配置的模型服务商（如 DeepSeek、OpenAI、
        Anthropic 或自建服务），按其隐私政策处理。我们不接触、不转存这些内容。
        除法律法规要求外，我们不向任何第三方提供你的信息。

        四、数据出境
        若你配置的模型服务或远程主机位于中国境外，你的对话内容将被传输至境外，
        请自行确认符合所在地法律及你所签约服务商的条款。默认预置服务均支持境内直连。

        五、你的权利
        你可以在应用内随时查看、修改、导出、删除全部会话与配置；删除后进入本机回收站，
        清空回收站或卸载应用即不可恢复。你可以在设置中关闭同步、清除工作区。

        六、未成年人
        本应用面向开发者与成年用户，不面向 14 周岁以下儿童提供。

        七、AI 生成内容
        应用内模型回复均由人工智能生成，可能存在错误，请在重要场景自行核实；
        界面中对 AI 生成内容做了标识。

        八、安全措施
        Key 与私钥使用系统级密钥库加密；接口强制 HTTPS；远程命令与文件删除操作
        每次均需你人工确认；可选应用锁与防截屏。

        九、政策更新
        政策变更时会在应用内提示，重大变更需你重新确认后继续使用。

        十、联系我们
        如对本政策有疑问，请联系：$email
    """.trimIndent()

    fun terms(): String = """
        PocketAgent 用户协议

        1. 服务性质
        本应用是一款本地优先的移动端 AI 客户端与开发工具。模型能力由你自行配置的
        第三方服务提供，费用与额度由你与该服务商结算，与本应用无关。

        2. 账号与凭据
        你应妥善保管自己的 API Key、SSH 私钥与设备。因凭据泄露造成的损失由你自行承担；
        建议定期轮换 Key，并为远程主机使用独立账号与最小权限。

        3. 使用规范
        你不得利用本应用：侵犯他人合法权益；未经授权访问他人系统或数据；
        生成、传播违法信息；规避服务商的使用限制；从事任何违反所在地法律的活动。
        远程执行功能仅可用于你拥有合法授权的设备。

        4. AI 输出
        模型输出不保证真实、准确、完整，不构成专业建议。你应自行判断并承担使用后果，
        尤其在代码执行、数据处理与生产环境操作前进行复核。

        5. 免责与责任限制
        本应用按「现状」提供。在法律允许的范围内，我们不对因使用本应用导致的
        数据丢失、业务中断或第三方索赔承担责任。

        6. 知识产权
        应用本身及其界面归运营方所有；应用使用的开源组件遵循各自许可证（见「开源许可」）。
        你输入的內容与生成结果的权利归属，按你与模型服务商的约定处理。

        7. 协议变更与终止
        我们可能更新本协议并在应用内提示。你可在任何时候停止使用并卸载应用。

        8. 适用法律
        本协议适用中华人民共和国法律。争议由运营方所在地有管辖权的人民法院管辖。

        运营主体：$operator
        联系方式：$email
    """.trimIndent()

    fun oss(): String = """
        开源许可

        本应用基于以下开源项目构建，感谢作者与社区：

        • Kotlin / kotlinx.coroutines / kotlinx.serialization — Apache License 2.0
        • AndroidX（Core、AppCompat、Activity、Fragment、Lifecycle、Biometric）— Apache License 2.0
        • Jetpack Compose（UI、Material3、Material Icons）— Apache License 2.0
        • OkHttp / MockWebServer — Apache License 2.0
        • sshj — Apache License 2.0
        • JSch（com.github.mwiede）— BSD 3-Clause
        • Bouncy Castle（bcprov-jdk18on）— MIT License

        上述许可证全文可在各项目仓库获取。按 Apache License 2.0 第 4 条，本应用保留
        原始版权声明与许可声明；如你分发本应用的二进制，应一并提供本页内容。

        本应用自身代码的许可证由运营方另行声明。
    """.trimIndent()

    fun about(): String = """
        PocketAgent ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）

        包名：${BuildConfig.APPLICATION_ID}

        运营主体：$operator
        联系方式：$email
        ICP 备案号：$icp
        App 备案号：$beian

        本地优先的移动端 agent：模型走你自己的 Key，数据留在手机上，
        不经过我们运营的中间服务器。
    """.trimIndent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(
    title: String,
    body: String,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 首次启动的隐私政策确认。按《App 违法违规收集使用个人信息行为认定方法》，
 * 必须在用户同意前不进行任何信息收集，且提供「不同意」的退出路径。
 */
@Composable
fun ConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    // 用整屏页面而不是 AlertDialog：弹窗在部分机型/大字号下会被挤到屏幕下方甚至超出，
    // 首次启动的同意按钮必须固定可见。
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text("PocketAgent", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("本地优先的 AI 助手 · 你的数据留在手机"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))
                ConsentPoint(
                    Icons.Outlined.Lock,
                    tr("Key 只存本机"),
                    tr("用 Android Keystore 加密，不上传服务器"),
                )
                ConsentPoint(
                    Icons.Outlined.CloudOff,
                    tr("对话不经过中转"),
                    tr("直接发给你自己配置的模型服务商"),
                )
                ConsentPoint(
                    Icons.Outlined.VerifiedUser,
                    tr("动手前先确认"),
                    tr("读取和搜索默认放行，写入、删除、执行命令先问你"),
                )

                Spacer(Modifier.height(24.dp))
                Text(
                    tr("点击「同意并继续」即表示你已阅读并同意《隐私政策》与《用户协议》，可随时在「设置 → 关于」中查看。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onOpenPrivacy,
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("隐私政策")) }
                    OutlinedButton(
                        onClick = onOpenTerms,
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("用户协议")) }
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(tr("同意并继续"), style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text(
                    tr("不同意，退出"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConsentPoint(icon: ImageVector, title: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
