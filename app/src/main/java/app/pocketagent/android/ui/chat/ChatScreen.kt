package app.pocketagent.android.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.ModelCatalog
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import app.pocketagent.android.tools.PdfTextExtractor
import app.pocketagent.android.agent.SlashCommands
import app.pocketagent.android.agent.ApprovalRecord
import app.pocketagent.android.agent.LOCAL_NOTICE_MODEL
import app.pocketagent.android.agent.decodeReplyText
import app.pocketagent.android.agent.isAutomaticLimitReply
import app.pocketagent.android.agent.isLegacyAutomaticLimitReply
import app.pocketagent.android.ui.components.ApprovalRecordCard
import app.pocketagent.android.ui.components.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import app.pocketagent.android.ui.i18n.L10n
import app.pocketagent.android.ui.i18n.tr

private const val MAX_PENDING_IMAGES = 8
private const val MAX_PENDING_FILES = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClearQueue: () -> Unit = {},
    onSelectProvider: (String) -> Unit,
    onClear: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit = {},
    onDismissModelPicker: () -> Unit = {},
    onPickProvider: (String) -> Unit = onSelectProvider,
    onPickModel: (String) -> Unit = {},
    onPickReasoning: (String) -> Unit = {},
    onPickLevel: (String?) -> Unit = {},
    onBackToModelList: () -> Unit = {},
    onSendImages: (String, List<String>) -> Unit = { t, _ -> onSend(t) },
    onDismissWorkspace: () -> Unit = {},
    onPickWorkspace: (String?, String) -> Unit = { _, _ -> },
    onDismissReasoning: () -> Unit = {},
    onAddCustomModel: (String) -> Unit = {},
    onDismissApprovals: () -> Unit = {},
    onPickApprovals: (Boolean) -> Unit = {},
    onDismissPermissions: () -> Unit = {},
    onTogglePermission: (String, Boolean) -> Unit = { _, _ -> },
    onDismissGoal: () -> Unit = {},
    onSaveGoal: (String) -> Unit = {},
    onFinishGoal: () -> Unit = {},
    onContinueGoal: () -> Unit = {},
    onPickReasoningLevel: (String?) -> Unit = {},
    onTestModels: () -> Unit = {},
) {
    // 从一个会话切到另一组会话时，复用上一组的 LazyListState 会保留错误的滚动位置。
    val conversationKey = state.messages.firstOrNull()?.id ?: state.title
    val listState = remember(conversationKey) { LazyListState() }
    // 底部面板弹出时收起键盘：否则键盘会把面板下半截挡住，后面的开关点不到。
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val anySheetOpen = state.modelPicker || state.reasoningPicker || state.approvalsPicker ||
        state.permissionsSheet || state.workspaceSheet || state.goalEditor
    LaunchedEffect(anySheetOpen) {
        if (anySheetOpen) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var commandSheet by remember { mutableStateOf(false) }
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    // 非图片附件：文件名 -> 文本内容（贴进消息里发给模型）
    var pendingFiles by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var fileHint by remember { mutableStateOf<String?>(null) }
    var customModelInput by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val (images, files, rejected) = withContext(Dispatchers.IO) {
                val imgs = mutableListOf<String>()
                val texts = mutableListOf<Pair<String, String>>()
                val bad = mutableListOf<String>()
                uris.forEach { uri ->
                    val mime = ctx.contentResolver.getType(uri).orEmpty()
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val isPdf = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
                    if (isPdf) {
                        // 无论文本层是否可读，都留一份原文件，避免只把前 60k 字/前 4 页发出去后无法再取。
                        val archived = saveToWorkspace(ctx, uri, name)
                        val archiveNote = archived?.let { "\n\n〔完整 PDF 已保存到工作区 uploads/${it.name}〕" }.orEmpty()
                        // 用已归档的本地文件解析：一次读取、可 seek、不会把整份 PDF 读进内存两遍。
                        val parsed = archived?.let { runCatching { PdfTextExtractor.inspect(it) } }
                        val extraction = parsed?.getOrNull()
                        val failure = parsed?.exceptionOrNull()
                        when {
                            extraction != null && extraction.text.isNotBlank() -> {
                                texts += name to "〔PDF 文本，共 ${extraction.pageCount} 页 / ${extraction.text.length} 字〕\n" +
                                    extraction.text + archiveNote
                                // 混合 PDF（有页是扫描件）：把缺文本的页也渲染成图片，别整份丢掉
                                if (extraction.hasPagesWithoutText && archived != null) {
                                    val rendered = fileToPdfImages(archived)
                                    if (rendered.images.isNotEmpty()) {
                                        imgs += rendered.images
                                        texts += name to "〔该 PDF 有 ${extraction.pageCount - extraction.pagesWithText} 页没有文本层，" +
                                            "已把前 ${rendered.images.size} 页渲染成图片〕"
                                    }
                                }
                            }
                            archived != null && failure == null -> {
                                // 纯扫描版：没有文本层，渲染前几页给多模态模型
                                val rendered = fileToPdfImages(archived)
                                if (rendered.images.isNotEmpty()) {
                                    imgs += rendered.images
                                    val remaining = (rendered.totalPages - rendered.images.size).coerceAtLeast(0)
                                    val rest = if (remaining > 0) "，剩余 $remaining 页未随消息发送" else ""
                                    texts += name to "〔扫描版 PDF，共 ${rendered.totalPages} 页；已把前 ${rendered.images.size} 页渲染成图片$rest；" +
                                        "原文件在工作区 uploads/${archived.name}〕"
                                } else {
                                    texts += name to "〔PDF 没有可提取文本，也无法渲染；原文件在 uploads/${archived.name}〕"
                                }
                            }
                            failure != null -> {
                                // 加密/损坏要说出真实原因，别一律说“可能是扫描版”
                                val where = archived?.let { "原文件在 uploads/${it.name}" } ?: "原文件未能保存"
                                texts += name to "〔PDF 解析失败：${failure.message ?: failure.javaClass.simpleName}；$where〕"
                            }
                            else -> bad += name
                        }
                    } else if (mime.startsWith("image/")) {
                        uriToDataUrl(ctx, uri)?.let { imgs += it }
                    } else {
                        val text = uriToText(ctx, uri)
                        if (text != null) {
                            texts += name to text
                        } else {
                            // 二进制：存进应用工作区 uploads/，让模型用工具去处理
                            val saved = saveToWorkspace(ctx, uri, name)
                            if (saved != null) {
                                val size = saved.length()
                                texts += name to "〔二进制文件已保存到工作区 uploads/${saved.name}（$size 字节），" +
                                    L10n.t("可用 fs_read / fs_list 或脚本处理〕")
                            } else {
                                bad += name
                            }
                        }
                    }
                }
                Triple(imgs, texts, bad)
            }
            val allImages = pendingImages + images
            val allFiles = pendingFiles + files
            pendingImages = allImages.take(MAX_PENDING_IMAGES)
            pendingFiles = allFiles.take(MAX_PENDING_FILES)
            val overflowImages = (allImages.size - MAX_PENDING_IMAGES).coerceAtLeast(0)
            val overflowFiles = (allFiles.size - MAX_PENDING_FILES).coerceAtLeast(0)
            fileHint = buildList {
                if (rejected.isNotEmpty()) add(L10n.t("这些文件读不了：") + rejected.joinToString("、"))
                if (overflowImages > 0) add("单条消息最多附 $MAX_PENDING_IMAGES 张图片，另有 $overflowImages 张未加入。")
                if (overflowFiles > 0) add("单条消息最多附 $MAX_PENDING_FILES 个文本附件，另有 $overflowFiles 个未加入。")
            }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    // 是否跟随到底部：用户往上拖就暂停跟随，回到底部（或点“到底”）再恢复
    var autoFollow by remember(conversationKey) { mutableStateOf(true) }
    // 打开会话时是否已经落过底：消息是异步读出来的，必须等列表真正测量出条目再滚
    var landedAtBottom by remember(conversationKey) { mutableStateOf(false) }
    val atTop by remember(conversationKey) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                ?: return@derivedStateOf true
            first.index == 0 && first.offset >= 0
        }
    }
    val atBottom by remember(conversationKey) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            info.totalItemsCount > 0 &&
                last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset
        }
    }
    // 只有“用户自己往下拖”才停止跟随；滚回底部自动恢复
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                autoFollow = false
            }
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) autoFollow = true }

    // 首次进来（尤其是同步回来的上千条长会话）直接落在最底部；
    // 等列表测量完成后滚一次，再在文字排版稳定后补滚一次，避免停在中间。
    LaunchedEffect(conversationKey, state.messages.size, listState.layoutInfo.totalItemsCount) {
        if (landedAtBottom) return@LaunchedEffect
        val last = state.messages.lastIndex
        if (last < 0 || listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect
        listState.scrollToBottom(last)
        kotlinx.coroutines.delay(120)
        listState.scrollToBottom(state.messages.lastIndex.coerceAtLeast(0))
        landedAtBottom = true
        autoFollow = true
    }

    // 新消息、以及流式回复逐字增长时，跟随中就一直贴着底部
    val lastMessage = state.messages.lastOrNull()
    val lastAssistantIndex = state.messages.indexOfLast { it.role == Role.ASSISTANT }
    val lastAssistant = state.messages.getOrNull(lastAssistantIndex)
    val lastAssistantIsTail = lastAssistantIndex >= 0 && lastAssistantIndex == state.messages.lastIndex
    val lastReplyIsAutoLimit = remember(lastAssistant?.text) {
        lastAssistant?.text?.let(::isAutomaticLimitReply) == true
    }
    val lastReplyIsLegacyAutoLimit = remember(lastAssistant?.text) {
        lastAssistant?.text?.let(::isLegacyAutomaticLimitReply) == true
    }
    val lastMessageRevision = lastMessage?.let {
        "${it.text.length}:${it.reasoning.length}:${it.toolCalls.sumOf { call -> call.result?.length ?: 0 }}"
    }
    LaunchedEffect(conversationKey, state.messages.size, lastMessageRevision) {
        val last = state.messages.lastIndex
        if (last >= 0 && landedAtBottom && autoFollow) {
            listState.scrollToBottom(last)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.provider?.let { "${it.name} · ${it.model}" } ?: tr("未选择来源"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = tr("更多"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("清空这个对话")) },
                                onClick = { onClear(); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("管理模型来源")) },
                                onClick = { menuOpen = false; onOpenProviders() },
                            )
                        }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // 目标条：设了 /goal 就一直挂在对话上方，暂停时显示 ⏸
            state.goal?.takeIf { it.isNotBlank() }?.let { goal ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (state.goalPaused) "⏸" else "🎯")
                        Text(
                            goal,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        TextButton(onClick = onContinueGoal) {
                            Text(tr("继续"), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = onFinishGoal) {
                            Text(tr("完成"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (state.messages.isEmpty()) {
                EmptyChatHint(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 16.dp,
                                top = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // 加 index 后缀：老数据/异常导入即使出现重复 id，也不会触发 LazyColumn key 冲突。
                            itemsIndexed(
                                state.messages,
                                key = { index, message -> "${message.id}:$index" },
                            ) { index, message ->
                                MessageItem(
                                    message = message,
                                    isLive = state.running && index == lastAssistantIndex,
                                    showRemoteThinking = state.showRemoteThinking,
                                    showStopped = lastAssistantIsTail &&
                                        index == lastAssistantIndex &&
                                        message.stopped &&
                                        !lastReplyIsLegacyAutoLimit,
                                )
                            }
                        }
                    }
                    // 胶囊放到列表外的独立一行：之前叠在右下角，会遮住长回复的最后一行字。
                    if (!atTop || !atBottom) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 2.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            if (!atTop) {
                                JumpChip(label = tr("到顶"), up = true) {
                                    scope.launch {
                                        listState.scrollToItem(0)
                                        // 正在流式输出时也不要把用户拽回底部
                                        autoFollow = false
                                    }
                                }
                            }
                            if (!atBottom) {
                                JumpChip(label = tr("到底"), up = false) {
                                    scope.launch {
                                        listState.scrollToBottom(state.messages.lastIndex.coerceAtLeast(0))
                                        autoFollow = true
                                    }
                                }
                            }
                        }
                    }
                }
            }

            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row {
                            if (state.needsKey) {
                                TextButton(onClick = onOpenProviders) { Text(tr("去配置")) }
                            }
                            TextButton(onClick = onDismissError) { Text(tr("知道了")) }
                        }
                    }
                }
            }

            val retryableAtTail = lastAssistantIsTail &&
                (lastAssistant?.stopped == true || lastReplyIsAutoLimit)
            val canRetry = !state.running && (state.error != null || retryableAtTail)
            if (canRetry) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) {
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("retry")) {
                        Text(tr("重试这一轮"))
                    }
                }
            }

            val slashSuggestions = remember(draft) { SlashCommands.match(draft) }
            if (slashSuggestions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    slashSuggestions.forEach { spec ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable { draft = spec.name + " " },
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    spec.usage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    spec.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (pendingImages.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingImages.forEachIndexed { index, url ->
                        Box {
                            MessageImage(url, Modifier.size(64.dp))
                            IconButton(
                                onClick = {
                                    pendingImages = pendingImages.filterIndexed { i, _ -> i != index }
                                },
                                modifier = Modifier.size(20.dp).align(Alignment.TopEnd).background(
                                    MaterialTheme.colorScheme.surface,
                                    CircleShape,
                                ),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = tr("移除图片"), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            if (pendingFiles.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingFiles.forEachIndexed { index, (name, content) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(
                                Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "📎 $name（${content.length} 字）",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                                IconButton(
                                    onClick = { pendingFiles = pendingFiles.filterIndexed { i, _ -> i != index } },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = tr("移除文件"), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }

            fileHint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 国产 ROM 默认冻结后台，回复会中断；这里给一个显眼入口，比藏在设置里好找
            val batteryContext = LocalContext.current
            var batteryHintDismissed by remember { mutableStateOf(false) }
            if (!batteryHintDismissed &&
                !app.pocketagent.android.util.BatteryOptimization.isIgnoring(batteryContext)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("后台可能被系统冻结，回复会中断"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            app.pocketagent.android.util.BatteryOptimization.openSettings(batteryContext)
                        },
                    ) { Text(tr("去设置")) }
                    TextButton(onClick = { batteryHintDismissed = true }) { Text(tr("知道了")) }
                }
            }

            QueueStatusBar(state, onStop, onClearQueue)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable {
                            filePicker.launch(arrayOf("*/*"))
                        }
                        .testTag("attach_image"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = tr("添加图片或文件"),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { commandSheet = true }
                        .testTag("slash_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "/",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.width(4.dp))
                fun submitDraft() {
                    onSendImages(composeWithAttachments(draft, pendingFiles), pendingImages)
                    pendingFiles = emptyList()
                    draft = ""
                    pendingImages = emptyList()
                }
                var composerFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f))
                        .border(
                            width = 1.dp,
                            color = if (composerFocused) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                            },
                            shape = RoundedCornerShape(24.dp),
                        )
                        .padding(horizontal = 16.dp)
                        .testTag("composer"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { composerFocused = it.isFocused }
                            .testTag("composer_input"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitDraft() }),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (draft.isEmpty()) {
                                    Text(
                                        tr("问点什么…"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(6.dp))
                val hasComposerPayload = draft.isNotBlank() ||
                    pendingFiles.isNotEmpty() || pendingImages.isNotEmpty()
                val primaryActionIsStop = state.running && !hasComposerPayload
                FilledIconButton(
                    onClick = {
                        if (primaryActionIsStop) {
                            onStop()
                        } else {
                            submitDraft()
                        }
                    },
                    modifier = Modifier.size(36.dp).testTag("send"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    ),
                ) {
                    if (primaryActionIsStop) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = tr("停止"),
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = tr("发送"),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    if (commandSheet) {
        ModalBottomSheet(onDismissRequest = { commandSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                Text(
                    tr("斜杠命令（点一条执行，或自动填进输入框）"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(SlashCommands.specs) { spec ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    commandSheet = false
                                    if (spec.name == "/tools") {
                                        draft = spec.name + " "
                                    } else {
                                        onSend(spec.name)
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Text(
                                spec.usage,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                spec.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.approvalsPicker) {
        val auto = state.provider?.autoApprove == true
        ModalBottomSheet(onDismissRequest = onDismissApprovals) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                Text(
                    tr("工具审批"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    tr("ssh_exec 和 fs_delete 永远需要确认，不受这里影响。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LevelRow(tr("每次询问（推荐）"), tr("每次调用工具都弹确认"), !auto) { onPickApprovals(false) }
                LevelRow(tr("自动批准"), tr("读写文件/抓网页不再逐条问"), auto) { onPickApprovals(true) }
            }
        }
    }

    if (state.reasoningPicker) {
        val levels = state.catalog.firstOrNull { it.id == state.provider?.model }?.reasoningLevels
            ?: app.pocketagent.android.data.ModelCatalog.defaultLevels
        val current = state.provider?.reasoningEffort
        ModalBottomSheet(onDismissRequest = onDismissReasoning) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                Text(
                    tr("思考强度"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    (state.provider?.model?.takeIf { it.isNotBlank() } ?: tr("当前模型")) +
                        " · " + tr("当前：") +
                        (current?.let { app.pocketagent.android.data.ModelCatalog.label(it) } ?: tr("服务端默认")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    item {
                        LevelRow(tr("服务端默认"), tr("不指定档位，由服务端决定"), current == null) { onPickReasoningLevel(null) }
                    }
                    items(levels) { lv ->
                        LevelRow(
                            app.pocketagent.android.data.ModelCatalog.label(lv),
                            lv,
                            current == lv,
                        ) { onPickReasoningLevel(lv) }
                    }
                }
            }
        }
    }

    if (state.permissionsSheet) {
        val granted = state.provider?.permissions
            ?: app.pocketagent.android.agent.ToolPermissions.defaults
        ModalBottomSheet(onDismissRequest = onDismissPermissions) {
            // 七类开关在小屏上放不下，必须能滚；否则最后几类（命令/远端/MCP）根本点不到。
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    tr("工具权限"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    tr("放开后该类别不再逐个确认；写文件、删文件、执行命令建议保留确认。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                app.pocketagent.android.agent.ToolPermissions.all.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTogglePermission(category, category !in granted) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr(app.pocketagent.android.agent.ToolPermissions.label(category)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                app.pocketagent.android.agent.ToolPermissions.describe(category),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = category in granted,
                            onCheckedChange = { onTogglePermission(category, it) },
                        )
                    }
                }
            }
        }
    }

    if (state.workspaceSheet) {
        ModalBottomSheet(onDismissRequest = onDismissWorkspace) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                Text(
                    tr("工作区"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    "当前：${state.workspaceLabel.ifBlank { "手机本地" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                Text(
                    tr("选远端后，代码、文档、笔记、导出的对话都直接写在那台机器上，手机不存文件、也不跑代码。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (state.workspaceHostId != null) {
                    val boundHere = state.remoteThreadId != null &&
                        (state.remoteHostId.isNullOrBlank() || state.remoteHostId == state.workspaceHostId)
                    Text(
                        if (boundHere) {
                            tr("当前对话绑定远端会话") + " ${state.remoteThreadId!!.take(8)}… · " +
                                tr("后续自动续接原生上下文")
                        } else if (state.remoteThreadId != null) {
                            tr("这组会话绑定在另一台主机；不会在这里误续接")
                        } else if (state.hosts.firstOrNull { it.id == state.workspaceHostId }?.codexHarnessAvailable == true) {
                            tr("首次发送会先把现有上下文写成该机原生会话，再继续")
                        } else {
                            tr("发送前自动检测远端 Codex；未安装时使用手机 harness 调用远端工具")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    item {
                        WorkspaceRow(
                            title = tr("手机本地"),
                            subtitle = tr("存在 App 私有目录，占手机空间"),
                            selected = state.workspaceHostId == null,
                            onClick = { onPickWorkspace(null, "") },
                        )
                    }
                    items(state.hosts.filter { it.enabled }) { host ->
                        WorkspaceRow(
                            title = host.label(),
                            subtitle = "${host.target} · ${host.workspaceDir.ifBlank { "~/pocketagent" }}\n" + tr(
                                when (host.codexHarnessAvailable) {
                                    true -> "远端 harness：Codex（在该主机执行）"
                                    false -> "远端 harness：未检测到 Codex"
                                    null -> "远端 harness：选择工作区时自动检测"
                                },
                            ),
                            selected = state.workspaceHostId == host.id,
                            onClick = {
                                onPickWorkspace(
                                    host.id,
                                    host.workspaceDir.ifBlank { "~/pocketagent" },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (state.modelPicker) {
        val currentModel = state.provider?.model.orEmpty()
        val pending = state.pendingModel
        ModalBottomSheet(onDismissRequest = onDismissModelPicker) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                if (pending == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("① 选择模型"),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onTestModels) { Text(tr("实测全部模型")) }
                    }
                    Text(
                        "当前：${currentModel.ifBlank { "未设置" }}　来源：${state.provider?.name ?: "无"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onBackToModelList) { Text(tr("← 换模型")) }
                        Text(
                            pending,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    Text(
                        tr("② 选择思考强度"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                if (pending == null) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(top = 8.dp)) {
                        items(state.catalog, key = { it.id }) { m ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickModel(m.id) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = m.id == currentModel, onClick = { onPickModel(m.id) })
                                Column(Modifier.padding(start = 4.dp)) {
                                    val probe = state.probes[m.id]
                                    Text(
                                        m.displayName + when {
                                            probe == null -> ""
                                            probe.ok -> "　✅"
                                            else -> "　❌"
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (probe != null && !probe.ok) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        m.id +
                                            if (m.reasoningLevels.isEmpty()) tr("　（不可调档位）")
                                            else tr("　思考：") + m.reasoningLevels.joinToString("/") { ModelCatalog.label(it) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val levels = state.catalog.firstOrNull { it.id == pending }?.reasoningLevels.orEmpty()
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        item {
                            LevelRow(
                                label = tr("服务端默认"),
                                detail = tr("不指定档位，由服务端决定"),
                                selected = state.provider?.reasoningEffort == null,
                                onClick = { onPickLevel(null) },
                            )
                        }
                        items(levels) { level ->
                            LevelRow(
                                label = ModelCatalog.label(level),
                                detail = level,
                                selected = state.provider?.reasoningEffort == level,
                                onClick = { onPickLevel(level) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 把 data URL 解成图片显示；解码失败就不显示，不让界面崩。 */
@Composable
private fun MessageImage(url: String, modifier: Modifier = Modifier) {
    val bitmap = remember(url) {
        runCatching {
            val b64 = url.substringAfter("base64,", "")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = tr("图片"),
            modifier = modifier.clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 选中的图片压到最长边 1280、JPEG 85，再转 data URL。
 * 原图动辄几 MB，直接 base64 会让请求体爆掉。
 */
private fun uriToDataUrl(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val maxDim = 1280
    val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled: Bitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()

@Composable
private fun EmptyChatHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr("从一句话开始"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            tr("模型可以读写应用内工作区、抓网页；读文件、搜索、抓网页直接执行，写文件、删文件、远端命令会先问你。"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            tr("输入 /permissions 可按类别放行"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(18.dp))
        listOf(
            tr("把这段报错解释一下"),
            tr("写个脚本把日志按小时统计"),
            tr("帮我看看这个 API 该怎么接"),
        ).forEach {
            Text(
                "· $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun QueueStatusBar(
    state: ChatUiState,
    onStop: () -> Unit,
    onClearQueue: () -> Unit,
) {
    if (!state.running && state.queuedSends.isEmpty() && !state.unconfirmedFollowUps) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("queue_status"),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.unconfirmedFollowUps) {
                        tr("远端接收待确认，请先同步核对后继续")
                    } else if (state.running) {
                        if (state.appliedFollowUps > 0) tr("已读取补充要求") + " · ${state.appliedFollowUps}"
                        else tr("执行中，可发送补充要求调整方向")
                    } else {
                        tr("等待读取")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.running) {
                    TextButton(onClick = onStop) { Text(tr("停止")) }
                }
            }
            state.queuedSends.firstOrNull()?.let { first ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${tr(if (state.unconfirmedFollowUps) "接收待确认" else "等待读取")} ${state.queuedSends.size} · ${first.preview}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onClearQueue,
                        modifier = Modifier.testTag("clear_queue"),
                    ) {
                        Text(tr("清空待处理"))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    isLive: Boolean,
    showRemoteThinking: Boolean,
    showStopped: Boolean,
) {
    val reply = remember(message.text) { decodeReplyText(message.text) }
    val reasoningVisible = message.reasoning.isNotBlank() &&
        (message.model !in setOf("mac-codex", "remote-codex") || showRemoteThinking)
    when (message.role) {
        Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    message.images.forEach { url ->
                        MessageImage(
                            url,
                            Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(bottom = 6.dp),
                        )
                    }
                    if (message.text.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            CopyMessageButton(
                                message.text,
                                "copy_message_${message.id}",
                                tr("复制消息"),
                            )
                        }
                    }
                }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message.toolCalls.forEachIndexed { index, call ->
                val plan = if (call.name == "update_plan" && call.status == ToolStatus.OK) {
                    parsePlanSteps(call.result.orEmpty())
                } else {
                    emptyList()
                }
                if (plan.isEmpty()) {
                    ToolCard(call, index + 1)
                } else {
                    PlanCard(plan)
                }
            }
            if (reasoningVisible) {
                ReasoningSection(message.id, message.reasoning)
            }
            if (reply.body.isNotBlank()) {
                // 桌面 Codex 的审批复核结果是 JSON；铺成正文会像模型坏了，改成卡片
                val approval = remember(reply.body) { ApprovalRecord.parse(reply.body) }
                if (approval != null) {
                    ApprovalRecordCard(approval)
                } else {
                    MarkdownText(reply.body)
                }
            } else if (isLive && reply.notices.isEmpty() && message.toolCalls.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(tr("思考中…"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (reply.notices.isNotEmpty()) {
                ReplyNotice(reply.notices)
            }
            val isLocal = message.model == LOCAL_NOTICE_MODEL
            if (!isLocal && message.tokensIn + message.tokensOut > 0) {
                Text(
                    tr("AI 生成") + " · in ${message.tokensIn} · out ${message.tokensOut} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!isLocal && reply.body.isNotBlank()) {
                // 《人工智能生成合成内容标识办法》：AI 生成的文本要有显式标识
                Text(
                    tr("AI 生成"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showStopped) {
                Text(
                    tr("（已停止）"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("stopped"),
                )
            }
            val copyText = buildString {
                if (reasoningVisible) {
                    append("【思考过程】\n")
                    append(message.reasoning)
                }
                if (reply.body.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("【回答】\n")
                    append(reply.body)
                }
                if (reply.notices.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(reply.notices.joinToString("\n"))
                }
            }
            if (copyText.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    CopyMessageButton(
                        copyText,
                        "copy_message_${message.id}",
                        tr("复制整条消息"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyMessageButton(text: String, tag: String, description: String) {
    val context = LocalContext.current
    val clipboardLabel = tr("PocketAgent 消息")
    val copiedLabel = tr("已复制")
    IconButton(
        onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(clipboardLabel, text))
            Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.size(30.dp).testTag(tag),
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = description,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** harness 的运行状态不是回答正文，单独成块，且不进入模型历史。 */
@Composable
private fun ReplyNotice(notices: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().testTag("reply_notice"),
    ) {
        SelectionContainer {
            Text(
                text = notices.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun ReasoningSection(messageId: String, reasoning: String) {
    var open by remember(messageId) { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { open = !open },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (open) tr("收起思考过程") else "查看思考过程（${reasoning.length} 字）",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            CopyMessageButton(reasoning, "copy_reasoning", tr("复制思考过程"))
        }
        if (open) {
            SelectionContainer {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal data class PlanStep(val mark: Char, val text: String)

/** 从 `update_plan` 的工具结果里解析 `1. [x] 步骤` 这样的行。 */
internal fun parsePlanSteps(result: String): List<PlanStep> =
    result.lineSequence().mapNotNull { line ->
        val match = Regex("""^\s*\d+\.\s*\[([ x>])\]\s*(.+)$""").find(line) ?: return@mapNotNull null
        PlanStep(match.groupValues[1][0], match.groupValues[2].trim())
    }.toList()

/**
 * 计划卡片：桌面 Codex 会把 update_plan 的进度画成一棵计划树，
 * 手机上原来只是一段工具输出；这里渲染成同样的可扫读列表。
 */
@Composable
private fun PlanCard(steps: List<PlanStep>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                tr("计划"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = when (step.mark) {
                            'x' -> "✓"
                            '>' -> "▶"
                            else -> "○"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (step.mark) {
                            'x' -> MaterialTheme.colorScheme.secondary
                            '>' -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(18.dp),
                    )
                    Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.mark == 'x') {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(record: ToolCallRecord, step: Int) {
    var expanded by remember(record.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = when (record.status) {
                        ToolStatus.OK -> Icons.Outlined.CheckCircle
                        ToolStatus.ERROR, ToolStatus.DENIED -> Icons.Outlined.ErrorOutline
                        else -> Icons.Outlined.Build
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = when (record.status) {
                        ToolStatus.OK -> MaterialTheme.colorScheme.secondary
                        ToolStatus.ERROR, ToolStatus.DENIED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${tr("步骤")} $step",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when (record.status) {
                        ToolStatus.PENDING -> tr("等待确认")
                        ToolStatus.APPROVED -> tr("已允许")
                        ToolStatus.RUNNING -> tr("执行中")
                        ToolStatus.OK -> "${record.durationMs}ms"
                        ToolStatus.DENIED -> tr("已拒绝")
                        ToolStatus.ERROR -> tr("失败")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) tr("收起") else tr("展开"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Text(tr("参数"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(record.arguments, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                record.result?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("结果") + "（${it.length} 字）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 展开后显示完整结果。旧实现固定 take(4000)，用户看到的工具输出永远缺后半截。
                    SelectionContainer {
                        Text(
                            text = it,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp)
                                .verticalScroll(rememberScrollState())
                                .testTag("tool_result"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 把文本附件完整拼到消息后面；截断必须由附件读取层显式标注，不能在这里静默砍掉。 */
internal fun composeWithAttachments(text: String, files: List<Pair<String, String>>): String {
    if (files.isEmpty()) return text
    return buildString {
        append(text)
        files.forEach { (name, content) ->
            append("\n\n【附件：").append(name).append("】\n```\n")
            append(content)
            append("\n```")
        }
    }
}

/** 到底部要滚到“最后一条的末尾”，而不是把最后一条的顶部对齐到视口顶部。 */
private suspend fun LazyListState.scrollToBottom(index: Int) {
    if (index < 0) return
    scrollToItem(index, scrollOffset = Int.MAX_VALUE)
}

/** 长会话里的一键到顶 / 到底小胶囊 */
@Composable
private fun JumpChip(label: String, up: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 读文本附件（超过 64KB 截断；二进制返回 null） */
private fun uriToText(context: android.content.Context, uri: android.net.Uri, maxBytes: Int = 64 * 1024): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total <= maxBytes) {
            val n = input.read(buf)
            if (n < 0) break
            val room = maxBytes + 1 - total
            if (room <= 0) break
            val take = minOf(n, room)
            out.write(buf, 0, take)
            total += take
        }
        out.toByteArray()
    } ?: return null
    val truncated = bytes.size > maxBytes
    val head = if (truncated) bytes.copyOf(maxBytes) else bytes
    // 有 NUL 或 UTF-8 替换符的基本都是二进制（图片/音频/压缩包/APK…）
    if (head.any { it.toInt() == 0 }) return null
    // 截断点可能正好落在多字节 UTF-8 字符中间；不能因此把整个文本文件误判成二进制。
    var end = head.size
    if (truncated) {
        var leadIndex = end - 1
        while (leadIndex >= 0 && (head[leadIndex].toInt() and 0xC0) == 0x80) leadIndex--
        if (leadIndex >= 0) {
            val lead = head[leadIndex].toInt() and 0xFF
            val expected = when {
                lead < 0x80 -> 1
                lead in 0xC2..0xDF -> 2
                lead in 0xE0..0xEF -> 3
                lead in 0xF0..0xF4 -> 4
                else -> 1
            }
            if (head.size - leadIndex < expected) end = leadIndex
        }
    }
    val text = String(head, 0, end, Charsets.UTF_8)
    if (text.isEmpty() || text.contains('\uFFFD')) return null
    val printable = text.count { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
    if (printable.toDouble() / text.length < 0.95) return null
    if (truncated) {
        val original = contentSize(context, uri)?.let { "，原文件 $it 字节" }.orEmpty()
        text + "\n…（已截断：只读取前 $maxBytes 字节$original）"
    } else {
        text
    }
}.getOrNull()

private fun contentSize(context: android.content.Context, uri: android.net.Uri): Long? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val column = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column).takeIf { it >= 0 }
    }
}.getOrNull()

private fun uriToPdfText(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    PdfTextExtractor.extract(bytes)
}.getOrNull()?.takeIf { it.isNotBlank() }

private data class RenderedPdf(
    val images: List<String>,
    val totalPages: Int,
)

/**
 * 扫描版 PDF：渲染前几页为 JPEG data URL。
 * 从已归档的本地文件读（可 seek），避免部分 ContentProvider 不提供可 seek 的 fd；
 * 同时限制总字节数，避免 4 张大图把请求体撑爆。
 */
private fun fileToPdfImages(
    file: java.io.File,
    maxPages: Int = 8,
    maxTotalBytes: Int = 3 * 1024 * 1024,
): RenderedPdf = runCatching {
    val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    pfd.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val total = renderer.pageCount
            val images = mutableListOf<String>()
            var bytes = 0
            var index = 0
            while (index < minOf(total, maxPages) && bytes < maxTotalBytes) {
                renderer.openPage(index).use { page ->
                    val scale = minOf(1f, 1024f / maxOf(page.width, page.height).toFloat())
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        val data = out.toByteArray()
                        if (bytes + data.size > maxTotalBytes && images.isNotEmpty()) {
                            index = maxPages
                        } else {
                            bytes += data.size
                            images += "data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                index++
            }
            RenderedPdf(images, total)
        }
    }
}.getOrDefault(RenderedPdf(emptyList(), 0))

/** 把二进制文件复制进应用工作区 uploads/，返回落盘文件 */
private fun saveToWorkspace(context: android.content.Context, uri: android.net.Uri, name: String): java.io.File? = runCatching {
    val dir = java.io.File(context.filesDir, "workspace/uploads").apply { mkdirs() }
    val safe = name.filter { it.isLetterOrDigit() || it in "-._" }.ifBlank { "file" }
    val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
    val stem = safe.substring(0, dot)
    val ext = safe.substring(dot)
    var target = java.io.File(dir, safe)
    var suffix = 2
    while (target.exists()) {
        target = java.io.File(dir, "$stem-$suffix$ext")
        suffix++
    }
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
    } ?: return null
    target
}.getOrNull()
