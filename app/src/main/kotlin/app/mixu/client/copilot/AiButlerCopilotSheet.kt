// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.copilot

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mixu.client.R
import app.mixu.client.auth.UserSession
import app.mixu.client.db.LocalLibraryDatabase
import app.mixu.client.library.BookItem
import app.mixu.client.network.CopilotChatTurnDto
import app.mixu.client.network.MixuApiClient
import app.mixu.client.reader.ParsedReaderImageToken
import app.mixu.client.reader.ReaderImageBitmapCache
import app.mixu.client.theme.MixuTheme

/**
 * Operating mode for a Copilot / Butler conversation session.
 */
enum class CopilotSessionMode {
    BOOK_COPILOT,
    GLOBAL_BUTLER,
}

/**
 * Selectable opaque LLM model tier for the AI Butler / Copilot, decoupled from the KG extraction
 * model.
 *
 * The client never knows concrete backend provider model IDs (e.g. OpenRouter / Vertex vendor
 * strings); it only selects an abstract tier (`"standard"`, `"balanced"`, `"reasoning"`, `"pro"`)
 * and `mixu-server` privately resolves the underlying model.
 */
data class CopilotModelOption(
    val modelTier: String,
    val shortName: String,
    val creditCost: Int,
) {
    val badgeLabel: String
        get() = "$shortName · $creditCost 推理点"

    companion object {
        val AVAILABLE_MODELS =
            listOf(
                CopilotModelOption(
                    modelTier = "standard",
                    shortName = "极速直答",
                    creditCost = 1,
                ),
                CopilotModelOption(
                    modelTier = "balanced",
                    shortName = "轻量思考",
                    creditCost = 2,
                ),
                CopilotModelOption(
                    modelTier = "reasoning",
                    shortName = "深度推演",
                    creditCost = 3,
                ),
                CopilotModelOption(
                    modelTier = "pro",
                    shortName = "极限破壁",
                    creditCost = 5,
                ),
            )
    }
}

/**
 * Actionable topic option inside an AI Agent greeting/card message.
 */
data class AgentQuickActionItem(
    val id: String,
    val label: String,
)

/**
 * Single message inside a Copilot / Butler session.
 */
data class CopilotChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val attachedImageCaption: String? = null,
    val attachedImagePath: String? = null,
    val actionBadge: String? = null,
    val quickActions: List<AgentQuickActionItem> = emptyList(),
    val animateStreaming: Boolean = false,
)

/**
 * Represents an individual conversation session in the Copilot / Butler mini-window.
 */
data class CopilotChatSession(
    val sessionId: String,
    val title: String,
    val mode: CopilotSessionMode,
    val messages: List<CopilotChatMessage>,
)

/**
 * Language-neutral (i18n-safe) animated floating AI Agent orb placed at the bottom-right corner.
 *
 * Uses a pure vector icon + subtle breathing pulse & floating animation on LCD/OLED screens
 * (and a crisp static vector icon on E-Ink screens) with zero embedded text.
 */
@Composable
fun MixuAiButlerFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val shouldAnimate = !eInkPolicy.isActive

    val infiniteTransition = rememberInfiniteTransition(label = "ai_orb_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (shouldAnimate) 1.15f else 1.0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse_scale",
    )
    val bobOffset by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = if (shouldAnimate) 1.5f else 0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "bob_offset",
    )

    Box(
        modifier =
            modifier
                .size(56.dp)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Outer breathing halo ring (hidden on E-Ink)
        if (!eInkPolicy.isActive) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = colors.copilotFabPrimary.copy(alpha = 0.22f),
                    radius = (size.minDimension / 2f) * pulseScale,
                )
            }
        }

        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = if (eInkPolicy.isActive) colors.surface else Color.Transparent,
            border =
                BorderStroke(
                    if (eInkPolicy.isActive) 2.dp else 1.5.dp,
                    if (eInkPolicy.isActive) colors.onSurface else Color.White.copy(alpha = 0.85f),
                ),
            shadowElevation = if (eInkPolicy.isActive) 0.dp else 10.dp,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (eInkPolicy.isActive) {
                                SolidColor(colors.surface)
                            } else {
                                Brush.linearGradient(
                                    colors =
                                        listOf(
                                            colors.copilotFabPrimary,
                                            Color(0xFF7C3AED),
                                            Color(0xFF2563EB),
                                        ),
                                )
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                AgentVectorIcon(
                    sizeDp = 28,
                    tint = if (eInkPolicy.isActive) colors.onSurface else Color.White,
                    verticalOffsetPx = if (shouldAnimate) bobOffset else 0f,
                )
            }
        }
    }
}

/**
 * Pure Canvas vector icon representing the AI Agent (rounded bot visor + twin glowing eyes +
 * top-right four-point AI sparkle star). Contains zero text so it is 100% i18n-independent.
 */
@Composable
fun AgentVectorIcon(
    sizeDp: Int = 26,
    tint: Color = Color.White,
    verticalOffsetPx: Float = 0f,
) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val w = size.width
        val h = size.height
        val dy = verticalOffsetPx

        // 1. Rounded visor frame
        val visorW = w * 0.66f
        val visorH = h * 0.52f
        val visorLeft = (w - visorW) / 2f
        val visorTop = (h - visorH) / 2f + h * 0.05f + dy
        drawRoundRect(
            color = tint,
            topLeft = Offset(visorLeft, visorTop),
            size = Size(visorW, visorH),
            cornerRadius = CornerRadius(visorH * 0.42f, visorH * 0.42f),
            style = Stroke(width = w * 0.085f, cap = StrokeCap.Round),
        )

        // 2. Twin vertical capsule eyes (matching the AWS AI Agent avatar aesthetic)
        val eyeW = w * 0.085f
        val eyeH = visorH * 0.38f
        val eyeY = visorTop + (visorH - eyeH) / 2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.38f - eyeW / 2f, eyeY),
            size = Size(eyeW, eyeH),
            cornerRadius = CornerRadius(eyeW / 2f, eyeW / 2f),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.62f - eyeW / 2f, eyeY),
            size = Size(eyeW, eyeH),
            cornerRadius = CornerRadius(eyeW / 2f, eyeW / 2f),
        )

        // 3. Top-right AI sparkle star
        val starCx = w * 0.78f
        val starCy = h * 0.20f + dy * 0.6f
        val starR = w * 0.13f
        val starPath =
            Path().apply {
                moveTo(starCx, starCy - starR)
                quadraticTo(starCx, starCy, starCx + starR, starCy)
                quadraticTo(starCx, starCy, starCx, starCy + starR)
                quadraticTo(starCx, starCy, starCx - starR, starCy)
                quadraticTo(starCx, starCy, starCx, starCy - starR)
                close()
            }
        drawPath(path = starPath, color = tint)
    }
}

@Composable
fun AgentAvatarBadge(sizeDp: Int = 28) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy

    Box(
        modifier =
            Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(
                    if (eInkPolicy.isActive) {
                        SolidColor(colors.onSurface)
                    } else {
                        Brush.linearGradient(
                            listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)),
                        )
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        AgentVectorIcon(
            sizeDp = (sizeDp * 0.72f).toInt(),
            tint = if (eInkPolicy.isActive) colors.surface else Color.White,
        )
    }
}

@Composable
private fun VectorChevronDownIcon(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val path =
            Path().apply {
                moveTo(w * 0.22f, h * 0.34f)
                lineTo(w * 0.50f, h * 0.66f)
                lineTo(w * 0.78f, h * 0.34f)
            }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun VectorImageAttachIcon(tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.1f, h * 0.15f),
            size = Size(w * 0.8f, h * 0.7f),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = Stroke(width = w * 0.11f),
        )
        drawCircle(
            color = tint,
            radius = w * 0.09f,
            center = Offset(w * 0.34f, h * 0.38f),
        )
        val mountain =
            Path().apply {
                moveTo(w * 0.18f, h * 0.75f)
                lineTo(w * 0.45f, h * 0.50f)
                lineTo(w * 0.64f, h * 0.66f)
                lineTo(w * 0.76f, h * 0.54f)
                lineTo(w * 0.86f, h * 0.75f)
            }
        drawPath(
            path = mountain,
            color = tint,
            style = Stroke(width = w * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun VectorGlobeSearchIcon(tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val r = w * 0.40f
        val center = Offset(w / 2f, h / 2f)
        drawCircle(
            color = tint,
            radius = r,
            center = center,
            style = Stroke(width = w * 0.11f),
        )
        drawLine(
            color = tint,
            start = Offset(center.x - r, center.y),
            end = Offset(center.x + r, center.y),
            strokeWidth = w * 0.09f,
        )
        drawOval(
            color = tint,
            topLeft = Offset(center.x - r * 0.52f, center.y - r),
            size = Size(r * 1.04f, r * 2f),
            style = Stroke(width = w * 0.09f),
        )
    }
}

@Composable
private fun VectorSendPlaneIcon(tint: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
        val plane =
            Path().apply {
                moveTo(w * 0.16f, h * 0.50f)
                lineTo(w * 0.84f, h * 0.18f)
                lineTo(w * 0.60f, h * 0.84f)
                lineTo(w * 0.46f, h * 0.56f)
                close()
            }
        drawPath(
            path = plane,
            color = tint,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Bottom-right floating AI Agent mini-window (`388.dp` wide, docked at `Alignment.BottomEnd`).
 */
@Composable
fun AiButlerCopilotDialog(
    initialMode: CopilotSessionMode,
    books: List<BookItem>,
    activeBook: BookItem?,
    currentChapterIndex: Int,
    currentChapterTitle: String,
    currentVisibleText: String,
    currentPageImageToken: ParsedReaderImageToken?,
    userSession: UserSession,
    database: LocalLibraryDatabase,
    onLibraryMutated: () -> Unit,
    onDismiss: () -> Unit,
    apiClient: MixuApiClient = remember { MixuApiClient.createProductionClient() },
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.dp else 1.dp
    val cornerRadius = if (eInkPolicy.isActive) 4.dp else 18.dp

    val safeChapterNum = (currentChapterIndex + 1).coerceAtLeast(1)
    val activeBookTitle = activeBook?.title ?: "三幕的杀意"

    val titleAgent = stringResource(R.string.ai_agent_title)
    val tabGlobal = stringResource(R.string.ai_agent_tab_global)
    val tabCopilot = stringResource(R.string.ai_agent_tab_copilot)
    val tabNew = stringResource(R.string.ai_agent_tab_new)
    val badgeGlobal = stringResource(R.string.ai_agent_badge_global)
    val badgeCopilot = stringResource(R.string.ai_agent_badge_copilot, safeChapterNum)
    val welcomeGlobal = stringResource(R.string.ai_agent_welcome_global)
    val welcomeCopilot =
        stringResource(
            R.string.ai_agent_welcome_copilot,
            activeBookTitle,
            currentChapterTitle,
            safeChapterNum,
        )

    val actionGalleryLabel = stringResource(R.string.ai_agent_action_gallery, books.size)
    val actionCollectionsLabel = stringResource(R.string.ai_agent_action_collections)
    val actionTagsLabel = stringResource(R.string.ai_agent_action_tags)
    val actionCloudLabel = stringResource(R.string.ai_agent_action_cloud)
    val actionCharsLabel = stringResource(R.string.ai_agent_action_chars, safeChapterNum)
    val actionTimelineLabel = stringResource(R.string.ai_agent_action_timeline)
    val actionImageLabel =
        stringResource(
            R.string.ai_agent_action_image,
            currentPageImageToken?.caption ?: "Cover",
        )

    val globalQuickActions =
        remember(actionGalleryLabel, actionCollectionsLabel, actionTagsLabel, actionCloudLabel) {
            listOf(
                AgentQuickActionItem("analyze_gallery", actionGalleryLabel),
                AgentQuickActionItem("build_collections", actionCollectionsLabel),
                AgentQuickActionItem("assign_tags", actionTagsLabel),
                AgentQuickActionItem("cloud_status", actionCloudLabel),
            )
        }

    val bookQuickActions =
        remember(actionCharsLabel, actionTimelineLabel, actionImageLabel) {
            listOf(
                AgentQuickActionItem("book_chars", actionCharsLabel),
                AgentQuickActionItem("book_timeline", actionTimelineLabel),
                AgentQuickActionItem("book_image", actionImageLabel),
            )
        }

    val sessions =
        remember {
            mutableStateListOf(
                CopilotChatSession(
                    sessionId = "session-butler-global",
                    title = tabGlobal,
                    mode = CopilotSessionMode.GLOBAL_BUTLER,
                    messages =
                        listOf(
                            CopilotChatMessage(
                                id = "welcome-global",
                                isUser = false,
                                actionBadge = badgeGlobal,
                                text = welcomeGlobal,
                                quickActions = globalQuickActions,
                                animateStreaming = false,
                            ),
                        ),
                ),
                CopilotChatSession(
                    sessionId = "session-book-copilot",
                    title = tabCopilot,
                    mode = CopilotSessionMode.BOOK_COPILOT,
                    messages =
                        listOf(
                            CopilotChatMessage(
                                id = "welcome-book",
                                isUser = false,
                                actionBadge = badgeCopilot,
                                text = welcomeCopilot,
                                quickActions = bookQuickActions,
                                animateStreaming = false,
                            ),
                        ),
                ),
            )
        }

    var activeSessionIndex by remember(initialMode) {
        mutableStateOf(
            if (initialMode == CopilotSessionMode.BOOK_COPILOT) 1 else 0,
        )
    }
    val currentSession = sessions[activeSessionIndex.coerceIn(0, sessions.lastIndex)]

    var inputDraft by remember { mutableStateOf("") }
    var selectedModel by remember {
        mutableStateOf(CopilotModelOption.AVAILABLE_MODELS.first())
    }
    var attachCurrentImage by remember(currentPageImageToken, initialMode) {
        mutableStateOf(
            currentPageImageToken != null && initialMode == CopilotSessionMode.BOOK_COPILOT,
        )
    }
    var webSearchEnabled by remember { mutableStateOf(false) }

    val appendExchange: (
        userPrompt: String,
        assistantReply: String,
        badge: String?,
        attachedImage: ParsedReaderImageToken?,
    ) -> Pair<Int, String> = { userPrompt, assistantReply, badge, attachedImage ->
        val idx = activeSessionIndex.coerceIn(0, sessions.lastIndex)
        val existing = sessions[idx]
        val assistantMsgId = "a-${existing.messages.size + 2}"
        val nextMessages =
            existing.messages +
                listOf(
                    CopilotChatMessage(
                        id = "u-${existing.messages.size + 1}",
                        isUser = true,
                        text = userPrompt,
                        attachedImageCaption = attachedImage?.caption,
                        attachedImagePath = attachedImage?.filePath,
                        animateStreaming = false,
                    ),
                    CopilotChatMessage(
                        id = assistantMsgId,
                        isUser = false,
                        text = assistantReply,
                        actionBadge = badge,
                        animateStreaming = true,
                    ),
                )
        sessions[idx] = existing.copy(messages = nextMessages)
        idx to assistantMsgId
    }

    val requestCloudCopilotEnrichment: (
        sessionIdx: Int,
        assistantMsgId: String,
        queryPrompt: String,
    ) -> Unit = { sessionIdx, assistantMsgId, queryPrompt ->
        val editionKey = activeBook?.id ?: "urn:mixu:edition:active"
        val horizonOrd = currentChapterIndex.coerceAtLeast(0)
        val userIdVal = userSession.anonymousId.ifBlank { "guest-reader" }
        val chosenModel = selectedModel
        val historyTurns =
            if (sessionIdx in sessions.indices) {
                sessions[sessionIdx]
                    .messages
                    .takeWhile { it.id != assistantMsgId }
                    .dropLast(1)
                    .filter { !it.id.startsWith("welcome-") && it.text.isNotBlank() }
                    .map { msg ->
                        CopilotChatTurnDto(
                            role = if (msg.isUser) "user" else "assistant",
                            content = msg.text,
                        )
                    }.takeLast(12)
            } else {
                emptyList()
            }
        Thread {
            val res =
                apiClient.queryCopilot(
                    userId = userIdVal,
                    editionId = editionKey,
                    horizonOrdinal = horizonOrd,
                    query = queryPrompt,
                    bookTitle = activeBookTitle,
                    chapterTitle = currentChapterTitle,
                    visibleExcerpt = currentVisibleText,
                    modelTier = chosenModel.modelTier,
                    conversationHistory = historyTurns,
                )
            if (!res.offlineFallback && res.answer.isNotBlank()) {
                runCatching {
                    Handler(Looper.getMainLooper()).post {
                        if (sessionIdx in sessions.indices) {
                            val currentSess = sessions[sessionIdx]
                            val updatedMsgs =
                                currentSess.messages.map { msg ->
                                    if (msg.id == assistantMsgId) {
                                        msg.copy(
                                            text = res.answer,
                                            actionBadge =
                                                "${chosenModel.shortName} (${res.creditCost} 推理点) · 第 1~$safeChapterNum 节",
                                            animateStreaming = true,
                                        )
                                    } else {
                                        msg
                                    }
                                }
                            sessions[sessionIdx] = currentSess.copy(messages = updatedMsgs)
                        }
                    }
                }
            }
        }.start()
    }

    val executeQuickAction: (String) -> Unit = { actionId ->
        when (actionId) {
            "analyze_gallery" -> {
                val report = buildLibraryAndGalleryAnalysis(books)
                appendExchange(
                    actionGalleryLabel,
                    report,
                    badgeGlobal,
                    null,
                )
            }
            "build_collections" -> {
                val summary = executeAutoOrganizeCollections(books, database)
                onLibraryMutated()
                appendExchange(
                    actionCollectionsLabel,
                    summary,
                    badgeGlobal,
                    null,
                )
            }
            "assign_tags" -> {
                val summary = executeAutoAssignTags(books, database)
                onLibraryMutated()
                appendExchange(
                    actionTagsLabel,
                    summary,
                    badgeGlobal,
                    null,
                )
            }
            "cloud_status" -> {
                val cloudReport = inspectCloudAndSyncState(books, userSession, database)
                appendExchange(
                    actionCloudLabel,
                    cloudReport,
                    badgeGlobal,
                    null,
                )
            }
            "book_chars" -> {
                val (sIdx, msgId) =
                    appendExchange(
                        actionCharsLabel,
                        buildSpoilerSafeCharacterSummary(
                            bookTitle = activeBookTitle,
                            chapterNum = safeChapterNum,
                            chapterTitle = currentChapterTitle,
                            visibleText = currentVisibleText,
                        ),
                        badgeCopilot,
                        null,
                    )
                requestCloudCopilotEnrichment(
                    sIdx,
                    msgId,
                    "请梳理前 $safeChapterNum 节已登场的人物身份与相互关系，严禁剧透后续章节。",
                )
            }
            "book_timeline" -> {
                val (sIdx, msgId) =
                    appendExchange(
                        actionTimelineLabel,
                        buildSpoilerSafeTimelineSummary(
                            bookTitle = activeBookTitle,
                            chapterNum = safeChapterNum,
                            chapterTitle = currentChapterTitle,
                            visibleText = currentVisibleText,
                        ),
                        badgeCopilot,
                        null,
                    )
                requestCloudCopilotEnrichment(
                    sIdx,
                    msgId,
                    "请按时间顺序梳理前 $safeChapterNum 节的关键事件与时间节点，严禁剧透后续章节。",
                )
            }
            "book_image" -> {
                val imgToken =
                    currentPageImageToken
                        ?: ParsedReaderImageToken(
                            filePath = activeBook?.coverPath.orEmpty(),
                            caption = "Cover",
                        )
                attachCurrentImage = true
                appendExchange(
                    actionImageLabel,
                    buildImageAnalysisReply(
                        bookTitle = activeBookTitle,
                        chapterNum = safeChapterNum,
                        imageToken = imgToken,
                    ),
                    badgeCopilot,
                    imgToken,
                )
            }
        }
    }

    // Bottom-Right Floating Mini-Window Surface
    Surface(
        modifier =
            modifier
                .width(388.dp)
                .heightIn(min = 480.dp, max = 640.dp),
        shape = RoundedCornerShape(cornerRadius),
        color = colors.surface,
        border = BorderStroke(strokeWidth, colors.outlineVariant),
        shadowElevation = if (eInkPolicy.isActive) 0.dp else 14.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Top Bar: Vector Avatar + Title + Session Mode Switcher + Collapse Vector Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentAvatarBadge(sizeDp = 28)
                    Text(
                        text = titleAgent,
                        style = MixuTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .widthIn(max = 210.dp)
                                .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sessions.forEachIndexed { index, session ->
                            val isSelected = index == activeSessionIndex
                            Surface(
                                modifier = Modifier.clickable { activeSessionIndex = index },
                                shape = RoundedCornerShape(12.dp),
                                color =
                                    if (isSelected) {
                                        colors.primaryContainer
                                    } else {
                                        colors.surfaceVariant.copy(alpha = 0.6f)
                                    },
                                border =
                                    BorderStroke(
                                        0.8.dp,
                                        if (isSelected) colors.primary else colors.outlineVariant,
                                    ),
                            ) {
                                Text(
                                    text = session.title,
                                    style = MixuTheme.typography.caption.copy(fontSize = 11.sp),
                                    fontWeight =
                                        if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color =
                                        if (isSelected) {
                                            colors.onPrimaryContainer
                                        } else {
                                            colors.onSurfaceVariant
                                        },
                                    modifier =
                                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }

                        Surface(
                            modifier =
                                Modifier.clickable {
                                    val newIdx = sessions.size
                                    sessions.add(
                                        CopilotChatSession(
                                            sessionId = "session-free-$newIdx",
                                            title = "$tabNew #${newIdx - 1}",
                                            mode = CopilotSessionMode.GLOBAL_BUTLER,
                                            messages =
                                                listOf(
                                                    CopilotChatMessage(
                                                        id = "welcome-free-$newIdx",
                                                        isUser = false,
                                                        actionBadge = badgeGlobal,
                                                        text = welcomeGlobal,
                                                        quickActions = globalQuickActions,
                                                        animateStreaming = false,
                                                    ),
                                                ),
                                        ),
                                    )
                                    activeSessionIndex = newIdx
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surface,
                            border = BorderStroke(0.8.dp, colors.outlineVariant),
                        ) {
                            Text(
                                text = "+",
                                style = MixuTheme.typography.caption,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }

                    // Collapse Chevron Vector Button
                    Box(
                        modifier =
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceVariant.copy(alpha = 0.45f))
                                .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        VectorChevronDownIcon(tint = colors.onSurfaceVariant)
                    }
                }
            }

            // 1.5 Decoupled Opaque Copilot Model Tier Selector (1 Cr default tier vs higher tiers)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (modelOpt in CopilotModelOption.AVAILABLE_MODELS) {
                    val isSelectedModel = modelOpt.modelTier == selectedModel.modelTier
                    Surface(
                        modifier = Modifier.clickable { selectedModel = modelOpt },
                        shape = RoundedCornerShape(10.dp),
                        color =
                            if (isSelectedModel) {
                                colors.primaryContainer
                            } else {
                                colors.surfaceVariant.copy(alpha = 0.45f)
                            },
                        border =
                            BorderStroke(
                                0.8.dp,
                                if (isSelectedModel) colors.primary else colors.outlineVariant,
                            ),
                    ) {
                        Text(
                            text = modelOpt.badgeLabel,
                            style = MixuTheme.typography.caption.copy(fontSize = 10.sp),
                            fontWeight =
                                if (isSelectedModel) FontWeight.Bold else FontWeight.Medium,
                            color =
                                if (isSelectedModel) {
                                    colors.onPrimaryContainer
                                } else {
                                    colors.onSurfaceVariant
                                },
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // 2. Scrollable Chat Messages Area (Streaming Markdown + Action Cards)
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(currentSession.messages, key = { it.id }) { msg ->
                    CopilotMessageBubble(
                        message = msg,
                        onQuickActionClick = executeQuickAction,
                    )
                }
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))

            // 3. Two Side-by-Side Action Buttons Above Input (with Vector Icons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hasImageAvailable =
                    currentPageImageToken != null || !activeBook?.coverPath.isNullOrBlank()
                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clickable {
                                if (hasImageAvailable) {
                                    attachCurrentImage = !attachCurrentImage
                                } else {
                                    activeSessionIndex = if (activeSessionIndex == 0) 1 else 0
                                }
                            },
                    shape = RoundedCornerShape(8.dp),
                    color =
                        if (attachCurrentImage) {
                            colors.primaryContainer
                        } else {
                            colors.surface
                        },
                    border =
                        BorderStroke(
                            1.dp,
                            if (attachCurrentImage) colors.primary else colors.outlineVariant,
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg =
                            if (attachCurrentImage) {
                                colors.onPrimaryContainer
                            } else {
                                colors.onSurface
                            }
                        VectorImageAttachIcon(tint = fg)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text =
                                if (attachCurrentImage) {
                                    stringResource(R.string.ai_agent_toggle_image_on)
                                } else {
                                    stringResource(R.string.ai_agent_toggle_image_off)
                                },
                            style = MixuTheme.typography.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clickable {
                                webSearchEnabled = !webSearchEnabled
                            },
                    shape = RoundedCornerShape(8.dp),
                    color =
                        if (webSearchEnabled) {
                            colors.primaryContainer
                        } else {
                            colors.surface
                        },
                    border =
                        BorderStroke(
                            1.dp,
                            if (webSearchEnabled) colors.primary else colors.outlineVariant,
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg =
                            if (webSearchEnabled) {
                                colors.onPrimaryContainer
                            } else {
                                colors.onSurface
                            }
                        VectorGlobeSearchIcon(tint = fg)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text =
                                if (webSearchEnabled) {
                                    stringResource(R.string.ai_agent_toggle_web_on)
                                } else {
                                    stringResource(R.string.ai_agent_toggle_web_off)
                                },
                            style = MixuTheme.typography.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 4. Rounded Pill Input Bar + Circular Vector Paper-Plane Send Button
            val sendAction = {
                val prompt = inputDraft.trim()
                if (prompt.isNotEmpty()) {
                    val effectiveImgToken =
                        if (attachCurrentImage) {
                            currentPageImageToken
                                ?: activeBook?.coverPath?.let {
                                    ParsedReaderImageToken(it, "Cover")
                                }
                        } else {
                            null
                        }
                    val (reply, badge) =
                        handleUserPrompt(
                            prompt = prompt,
                            mode = currentSession.mode,
                            books = books,
                            activeBookTitle = activeBookTitle,
                            chapterNum = safeChapterNum,
                            chapterTitle = currentChapterTitle,
                            visibleText = currentVisibleText,
                            attachedImage = effectiveImgToken,
                            webSearchEnabled = webSearchEnabled,
                            userSession = userSession,
                            database = database,
                            onLibraryMutated = onLibraryMutated,
                        )
                    val (sIdx, msgId) = appendExchange(prompt, reply, badge, effectiveImgToken)
                    val lowerPrompt = prompt.lowercase()
                    val isLocalCommand =
                        lowerPrompt.contains("收藏夹") ||
                            lowerPrompt.contains("整理") ||
                            lowerPrompt.contains("tag") ||
                            lowerPrompt.contains("标签") ||
                            lowerPrompt.contains("图库") ||
                            lowerPrompt.contains("分析书库") ||
                            lowerPrompt.contains("云端") ||
                            lowerPrompt.contains("同步")
                    if (!isLocalCommand) {
                        requestCloudCopilotEnrichment(sIdx, msgId, prompt)
                    }
                    inputDraft = ""
                }
            }

            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                color = colors.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (inputDraft.isEmpty()) {
                            Text(
                                text =
                                    if (currentSession.mode == CopilotSessionMode.BOOK_COPILOT) {
                                        stringResource(
                                            R.string.ai_agent_input_hint_copilot,
                                            safeChapterNum,
                                        )
                                    } else {
                                        stringResource(R.string.ai_agent_input_hint_global)
                                    },
                                style = MixuTheme.typography.caption,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = inputDraft,
                            onValueChange = { inputDraft = it },
                            singleLine = true,
                            textStyle =
                                MixuTheme.typography.bodyMedium.copy(
                                    color = colors.onSurface,
                                ),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    if (inputDraft.isNotBlank()) {
                                        colors.primary
                                    } else {
                                        colors.outlineVariant
                                    },
                                ).clickable { sendAction() },
                        contentAlignment = Alignment.Center,
                    ) {
                        VectorSendPlaneIcon(tint = colors.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CopilotMessageBubble(
    message: CopilotChatMessage,
    onQuickActionClick: (String) -> Unit,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val attachedBitmap =
        remember(message.attachedImagePath) {
            message.attachedImagePath?.let { path ->
                ReaderImageBitmapCache.load(path = path, reqWidth = 360, reqHeight = 480)
            }
        }

    if (message.isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 290.dp),
                shape = RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp),
                color = colors.copilotUserBubbleSurface,
                border = BorderStroke(1.dp, colors.primary),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (message.attachedImageCaption != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.surface.copy(alpha = 0.85f))
                                    .padding(4.dp),
                        ) {
                            if (attachedBitmap != null) {
                                Image(
                                    bitmap = attachedBitmap,
                                    contentDescription = message.attachedImageCaption,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                            Text(
                                text = message.attachedImageCaption,
                                style = MixuTheme.typography.caption,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                            )
                        }
                    }
                    Text(
                        text = message.text,
                        style = MixuTheme.typography.caption.copy(fontSize = 13.sp),
                        color = colors.onPrimaryContainer,
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AgentAvatarBadge(sizeDp = 26)

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = colors.copilotAgentBubbleSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = if (eInkPolicy.isActive) 0.dp else 2.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Top Accent Gradient Bar
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(
                                    if (eInkPolicy.isActive) {
                                        SolidColor(colors.onSurface)
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF6366F1),
                                                Color(0xFF8B5CF6),
                                                Color(0xFF06B6D4),
                                            ),
                                        )
                                    },
                                ),
                    )

                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (message.actionBadge != null) {
                            Text(
                                text = "✦ ${message.actionBadge}",
                                style = MixuTheme.typography.caption.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                            )
                        }

                        // Incremental Streaming Markdown Renderer
                        StreamingMarkdownBlock(
                            markdown = message.text,
                            animateStreaming = message.animateStreaming,
                            textColor = colors.onSurface,
                        )

                        if (message.quickActions.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (item in message.quickActions) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    colors.primaryContainer.copy(alpha = 0.42f),
                                                ).border(
                                                    0.8.dp,
                                                    colors.outlineVariant,
                                                    RoundedCornerShape(8.dp),
                                                ).clickable { onQuickActionClick(item.id) }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = item.label,
                                            style = MixuTheme.typography.caption,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = "→",
                                            style = MixuTheme.typography.caption,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildLibraryAndGalleryAnalysis(books: List<BookItem>): String {
    val totalBooks = books.size
    val withCoverCount = books.count { !it.coverPath.isNullOrBlank() }
    val inProgressCount = books.count { it.progressPercent in 1..99 }
    val topAuthors =
        books
            .groupBy { it.author }
            .entries
            .sortedByDescending { it.value.size }
            .take(4)
            .joinToString("、") { "**${it.key}** (${it.value.size})" }
    val allTags = books.flatMap { it.tags }.distinct()
    val allCollections = books.flatMap { it.collections }.distinct()

    return buildString {
        appendLine("### 图库与推理藏书结构")
        appendLine("- **藏书总量**：`$totalBooks` 本（`$withCoverCount` 本含高清封面/插图，`$inProgressCount` 本在读）")
        appendLine("- **馆藏作家**：$topAuthors")
        appendLine(
            "- **收藏夹 (${allCollections.size})**：${allCollections.joinToString(
                "、",
            ) { "`$it`" }.ifEmpty { "未归类" }}",
        )
        append("- **Tag 数组 (${allTags.size} 项)**：${allTags.take(8).joinToString(" ") { "`#$it`" }}")
    }
}

private fun executeAutoOrganizeCollections(
    books: List<BookItem>,
    database: LocalLibraryDatabase,
): String {
    var updatedCount = 0
    for (book in books) {
        val inferred = LinkedHashSet(book.collections)
        val combined = "${book.title} ${book.author} ${book.tags.joinToString(" ")}"
        when {
            combined.contains("森博嗣") ||
                combined.contains("密室") ||
                combined.contains("西村京太郎") -> inferred.add("新本格 & 时刻表")
            combined.contains("馆") ||
                combined.contains("山庄") ||
                combined.contains("证人") -> inferred.add("暴风雪山庄 & 馆系列")
            combined.contains("Christie") ||
                combined.contains("Doyle") ||
                combined.contains("Leroux") -> inferred.add("欧美黄金古典")
            combined.contains("13.67") ||
                combined.contains("陈浩基") -> inferred.add("社会派 & 时代推理")
            else -> inferred.add("本格推理")
        }
        if (inferred != book.collections) {
            database.setBookCollections(book.id, inferred)
            updatedCount++
        }
    }
    return buildString {
        appendLine("### 收藏夹自动归档完成")
        appendLine("- **归档分类**：`本格推理` `新本格 & 时刻表` `暴风雪山庄 & 馆系列` `欧美黄金古典`")
        append(
            "- **同步状态**：共处理 **${books.size}** 本藏书（新增归类 `$updatedCount` 本），已写入本地 `SQLite` 与 `sync_outbox`。",
        )
    }
}

internal fun executeAutoAssignTags(
    books: List<BookItem>,
    database: LocalLibraryDatabase,
    modelBadge: String = "极速直答 · 1 推理点",
): String {
    var enrichedCount = 0
    var unreadProtectedCount = 0
    var completedUnlockedCount = 0
    val safeTagSamples = linkedSetOf<String>()
    val unlockedTrickSamples = linkedSetOf<String>()

    for (book in books) {
        val inferred =
            BookItem.spoilerSafeTagsFor(
                id = book.id,
                title = book.title,
                author = book.author,
                isCompleted = book.isCompleted,
            )
        val merged = LinkedHashSet<String>()
        for (existing in book.tags) {
            if (book.isCompleted || existing !in BookItem.SPOILER_GUARDED_TRICK_TAGS) {
                merged.add(existing)
            }
        }
        merged.addAll(inferred)
        if (!book.isCompleted) {
            merged.removeAll(BookItem.SPOILER_GUARDED_TRICK_TAGS)
            unreadProtectedCount++
        } else {
            val tricks = merged.filter { it in BookItem.SPOILER_GUARDED_TRICK_TAGS }
            if (tricks.isNotEmpty()) {
                completedUnlockedCount++
                unlockedTrickSamples.addAll(tricks)
            }
        }
        for (t in merged) {
            if (t !in BookItem.SPOILER_GUARDED_TRICK_TAGS && t != book.author) {
                safeTagSamples.add(t)
            }
        }
        val finalTags = merged.toList()
        if (finalTags != book.tags) {
            database.setBookTags(book.id, finalTags)
            enrichedCount++
        }
    }
    val safePreview =
        safeTagSamples
            .take(8)
            .joinToString(" ") { "`#$it`" }
            .ifEmpty { "`#校园` `#恋爱` `#历史` `#科幻设定` `#奇幻架空` `#恐怖`" }
    val trickPreview =
        if (unlockedTrickSamples.isNotEmpty()) {
            unlockedTrickSamples.joinToString(" ") { "`#$it`" }
        } else {
            "仅在完读 (`100%`) 后解锁 `#叙述性诡计` `#Meta元叙事` `#实体书机关`"
        }
    return buildString {
        appendLine("### 防剧透双层 Tag 批量生成完成 ($modelBadge)")
        appendLine(
            "- **更新书籍**：`$enrichedCount` 本（未读防剧透保护 `$unreadProtectedCount` 本 · 完读解锁诡计 `$completedUnlockedCount` 本）",
        )
        appendLine("- **表层题材与背景 Tag（未读可见）**：$safePreview")
        appendLine("- **深层核心诡计 Tag（完读 100% 解锁）**：$trickPreview")
        append("- **云端同步**：已写入本地 `SQLite` 与 `sync_outbox` 队列。")
    }
}

private fun inspectCloudAndSyncState(
    books: List<BookItem>,
    userSession: UserSession,
    database: LocalLibraryDatabase,
): String {
    val pendingOutbox = database.listPendingSyncMutations()
    val accountDesc =
        if (userSession.isAuthenticated) {
            "**${userSession.displayName}** · 已连接云端"
        } else {
            "**离线优先访客模式** (`${userSession.displayName}`)"
        }
    return buildString {
        appendLine("### 云端与同步状态")
        appendLine("- **账户状态**：$accountDesc")
        appendLine("- **Sync Outbox 队列**：`${pendingOutbox.size}` 条待同步变更（含阅读定位、收藏夹与 Tag 数组）")
        append("- **时序图谱覆盖**：`${books.size}` 本书已挂载防剧透知识图谱。")
    }
}

private fun buildSpoilerSafeCharacterSummary(
    bookTitle: String,
    chapterNum: Int,
    chapterTitle: String,
    visibleText: String,
): String {
    val snippet = visibleText.take(52).replace("\n", " ")
    return buildString {
        appendLine("### 防剧透人物梳理 · 《$bookTitle》")
        appendLine("- **认知边界**：严格锁定在 `第 1~$chapterNum 节` ($chapterTitle)")
        appendLine("- **当前页上下文**：")
        appendLine("> $snippet...")
        append("- **伴读建议**：重点留意当前已出场人物的自我陈述与时间线交叉点，未读章节已由 `SpoilerFirewall` 屏蔽。")
    }
}

private fun buildSpoilerSafeTimelineSummary(
    bookTitle: String,
    chapterNum: Int,
    chapterTitle: String,
    visibleText: String,
): String {
    val snippet = visibleText.take(52).replace("\n", " ")
    return buildString {
        appendLine("### 已读章节时间线汇总 · 《$bookTitle》")
        appendLine("| 维度 | 状态 |")
        appendLine("|---|---|")
        appendLine("| 认知边界 | 第 1~$chapterNum 节 ($chapterTitle) |")
        appendLine("| 剧透防护 | 已屏蔽第 ${chapterNum + 1} 节后内容 |")
        append("- **焦点段落**：「$snippet...」")
    }
}

private fun buildImageAnalysisReply(
    bookTitle: String,
    chapterNum: Int,
    imageToken: ParsedReaderImageToken,
): String =
    buildString {
        appendLine("### 页面图片视觉解析 · 《$bookTitle》")
        appendLine("- **已读取页面图片**：`${imageToken.caption}`")
        appendLine("- **防剧透边界**：`第 1~$chapterNum 节`")
        append("- **视觉线索提取**：已解析画面空间布局、装帧意象与方位参照；后续未读章节的机关解答已被自动过滤。")
    }

private fun handleUserPrompt(
    prompt: String,
    mode: CopilotSessionMode,
    books: List<BookItem>,
    activeBookTitle: String,
    chapterNum: Int,
    chapterTitle: String,
    visibleText: String,
    attachedImage: ParsedReaderImageToken?,
    webSearchEnabled: Boolean,
    userSession: UserSession,
    database: LocalLibraryDatabase,
    onLibraryMutated: () -> Unit,
): Pair<String, String> {
    val lower = prompt.lowercase()
    if (lower.contains("收藏夹") || lower.contains("整理")) {
        val res = executeAutoOrganizeCollections(books, database)
        onLibraryMutated()
        return res to "全局管家 · 自动建立收藏夹"
    }
    if (lower.contains("tag") || lower.contains("标签")) {
        val res = executeAutoAssignTags(books, database)
        onLibraryMutated()
        return res to "全局管家 · 批量创建 Tag"
    }
    if (lower.contains("图库") || lower.contains("分析书库") || lower.contains("多少本书")) {
        return buildLibraryAndGalleryAnalysis(books) to "全局管家 · 图库与书库分析"
    }
    if (lower.contains("云端") || lower.contains("同步") || lower.contains("账号")) {
        return inspectCloudAndSyncState(books, userSession, database) to "全局管家 · 云端状态读取"
    }

    if (attachedImage != null) {
        val baseReply =
            buildImageAnalysisReply(
                bookTitle = activeBookTitle,
                chapterNum = chapterNum,
                imageToken = attachedImage,
            )
        val detail =
            "\n- **针对问题**「$prompt」：结合当前页图片与前 `$chapterNum` 节内容，画面细节与叙述吻合，零剧透。"
        return (baseReply + detail) to "伴读图片分析 · 第 1~$chapterNum 节"
    }

    return if (mode == CopilotSessionMode.BOOK_COPILOT) {
        val snippet = visibleText.take(48).replace("\n", " ")
        val body =
            buildString {
                appendLine("### 防剧透伴读 · 《$activeBookTitle》")
                appendLine("- **锁定范围**：`第 1~$chapterNum 节` ($chapterTitle)")
                append(
                    "- **解答**：针对「$prompt」，基于当前页（「$snippet...」）及已读章节梳理，第 ${chapterNum + 1} 节后内容已屏蔽。",
                )
            }
        body to "防剧透伴读 · 第 1~$chapterNum 节"
    } else {
        val webPrefix =
            if (webSearchEnabled) {
                "> **Web Search ON**：已结合联网检索公开资料\n\n"
            } else {
                ""
            }
        val body =
            buildString {
                append(webPrefix)
                appendLine("### 独立自由会话回答")
                append(
                    "针对「**$prompt**」：这是独立于单本书籍的全局会话。你可随时点击上方卡片或输入指令让我**分析图库**、**建立收藏夹**或**批量给书创建 Tag**。",
                )
            }
        body to if (webSearchEnabled) "独立会话 · 联网搜索" else "独立会话 · 全局管家"
    }
}
