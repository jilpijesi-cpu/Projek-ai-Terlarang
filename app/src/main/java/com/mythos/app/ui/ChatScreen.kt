package com.mythos.app.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.ProviderChoice
import com.mythos.app.UserSettings
import com.mythos.app.agent.LatencyTrace
import com.mythos.app.agent.StepStatus
import kotlin.math.exp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow

private const val LABEL_HOLD_MS = 500L

@Composable
private fun rememberHeldLabel(target: String): String {
    var shown by remember { mutableStateOf(target) }
    var since by remember { mutableLongStateOf(0L) }
    val queue = remember { Channel<String>(4, BufferOverflow.DROP_OLDEST) }
    LaunchedEffect(target) { queue.trySend(target) }
    LaunchedEffect(queue) {
        for (next in queue) {
            if (next == shown) continue
            val wait = LABEL_HOLD_MS - (SystemClock.uptimeMillis() - since)
            if (wait > 0) delay(wait)
            shown = next
            since = SystemClock.uptimeMillis()
        }
    }
    return shown
}

@Composable
fun ChatScreen(
    chat: List<ChatItem>,
    running: Boolean,
    settings: UserSettings,
    incognito: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onMenu: () -> Unit,
    onPickProvider: (ProviderChoice) -> Unit,
    onOpenSettings: (SettingsSection) -> Unit,
    onExport: () -> Unit,
    ws: WorkspaceHooks,
    attachments: List<Attachment>,
    onAttach: (AttachSource) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onUndo: (ChangesItem) -> Unit,
    loadDiff: suspend (ChangesItem, String) -> String,
    onOpenFile: (ChangesItem, String, Boolean) -> Unit = { _, _, _ -> },
    onOpenPath: (String, Boolean) -> Unit = { _, _ -> },
    onSendEmail: (EmailDraftItem) -> Unit = {},
    activity: LiveActivity? = null,
    subAgents: SubAgentStatus = SubAgentStatus(),
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(SheetKind.NONE) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val initialIds = remember { chat.map { it.id }.toSet() }
    val played = remember { HashSet<Long>() }

    val allBlocks = toBlocks(chat)
    var released by remember { mutableIntStateOf(allBlocks.size) }
    if (released > allBlocks.size) released = allBlocks.size
    var releasedAt by remember { mutableLongStateOf(0L) }
    val latestBlocks by rememberUpdatedState(allBlocks)
    LaunchedEffect(allBlocks.size) {
        if (released > allBlocks.size) released = allBlocks.size
        while (released < latestBlocks.size) {
            val list = latestBlocks
            if (released > list.size) {
                released = list.size
                break
            }
            val prev = list.getOrNull(released - 1)
            if (prev != null && list[released] !is UserBlock) {
                val now = SystemClock.uptimeMillis()
                val doneAt = (prev as? TextBlock)?.let { RevealClock.doneAt(it.item.id) }
                if (!releaseReady(prev, now, releasedAt, doneAt)) {
                    delay(40L)
                    continue
                }
            }
            (prev as? TextBlock)?.let { RevealClock.clear(it.item.id) }
            released++
            releasedAt = SystemClock.uptimeMillis()
        }
    }
    val blocks = allBlocks.take(released.coerceAtMost(allBlocks.size))
    val lastTextId = (blocks.lastOrNull() as? TextBlock)?.item?.id
    var settledId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(lastTextId, running) {
        if (lastTextId == null || !running) return@LaunchedEffect
        while (true) {
            val d = RevealClock.doneAt(lastTextId)
            settledId = if (d != null && SystemClock.uptimeMillis() - d >= TEXT_PAUSE_MS) lastTextId else null
            delay(40L)
        }
    }
    val extraLive = needsExtraLive(blocks, running, activity, textSettled = lastTextId == null || settledId == lastTextId)
    val baseLabel = liveLabel(blocks, activity)
    val lastTool = (blocks.lastOrNull() as? StepsBlock)?.items?.lastOrNull() as? ToolItem
    var thinkTick by remember { mutableIntStateOf(0) }
    val waiting = running && baseLabel == THINKING_LABEL
    LaunchedEffect(waiting, lastTool?.id) {
        thinkTick = 0
        while (waiting) {
            delay(THINK_ROTATE_MS)
            thinkTick++
        }
    }
    val label = rememberHeldLabel(if (waiting) thinkingPhrase(lastTool, thinkTick) else baseLabel)

    var stalled by remember { mutableStateOf(false) }
    val progress = progressKey(chat, activity)
    LaunchedEffect(progress, running) {
        stalled = false
        if (running) {
            delay(STALL_MS)
            stalled = true
        }
    }
    // Detik sejak kemajuan terakhir: hitungan kecil di samping label supaya menunggu model yang lambat tampak bekerja, bukan macet.
    var idleSecs by remember { mutableIntStateOf(0) }
    LaunchedEffect(progress, running) {
        idleSecs = 0
        while (running) {
            delay(1_000L)
            idleSecs++
        }
    }
    var subTick by remember { mutableIntStateOf(0) }
    val subActive = running && subAgents.running.isNotEmpty()
    LaunchedEffect(subActive) {
        subTick = 0
        while (subActive) {
            delay(THINK_ROTATE_MS)
            subTick++
        }
    }
    val subText = if (subActive) subAgents.phrase(subTick) else null
    val showStall = running && (stalled || subText != null)
    val total = blocks.size + (if (extraLive) 1 else 0) + (if (showStall) 1 else 0)

    // Chat baru dari beranda / pesan baru: pesan pengguna disematkan di ATAS layar dan jawaban mengalir di bawahnya lalu layar mengikuti teks begitu jawaban memenuhi layar; pengguna bebas menggeser kapan saja.
    val startsPinned = remember {
        (running && allBlocks.lastOrNull() is UserBlock) || (allBlocks.size == 1 && allBlocks[0] is UserBlock)
    }
    var stick by remember { mutableStateOf(!startsPinned) }
    var pinned by remember { mutableStateOf<Long?>(if (startsPinned) (allBlocks.lastOrNull() as? UserBlock)?.item?.id else null) }
    val userDragged = remember { booleanArrayOf(false) }
    val lastUserIdx = blocks.indexOfLast { it is UserBlock }
    val lastUserId = (blocks.getOrNull(lastUserIdx) as? UserBlock)?.item?.id
    val lastUserIdxNow by rememberUpdatedState(lastUserIdx)
    val totalNow by rememberUpdatedState(total)
    // Ruang kosong di ujung daftar agar pesan pengguna bisa naik ke atas walau jawabannya masih pendek (nilai mentah).
    var rawTail by remember { mutableIntStateOf(0) }
    LaunchedEffect(lastUserId) {
        if (lastUserId != null && lastUserId !in initialIds && lastUserId != pinned) {
            stick = false
            pinned = lastUserId
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val ui = lastUserIdxNow
            val n = totalNow
            if (ui < 0) return@snapshotFlow 0
            val after = info.visibleItemsInfo.filter { it.key != TAIL_KEY && it.index >= ui }
            when {
                after.none { it.index == ui } -> -1                       // pesan pengguna di luar layar: pertahankan nilai lama
                after.size < n - ui -> 0                                   // isi sudah lebih panjang dari layar
                else -> {
                    val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
                    (viewport - after.sumOf { it.size }).coerceAtLeast(0)
                }
            }
        }.collect { v -> if (v >= 0) rawTail = v }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                userDragged[0] = true
                pinned = null
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving && userDragged[0]) {
                userDragged[0] = false
                stick = !listState.canScrollForward && rawTail == 0
            }
        }
    }
    LaunchedEffect(pinned) {
        val id = pinned ?: return@LaunchedEffect
        val idx = latestBlocks.indexOfFirst { it is UserBlock && it.item.id == id }
        // Satu gulir halus saja. Koreksi setelah ruang kosong di ujung terhitung ditangani LaunchedEffect(rawTail) di bawah.
        if (idx >= 0 && !atItemTop(listState, idx)) listState.animateScrollToItem(idx)
    }
    LaunchedEffect(rawTail) {
        val id = pinned ?: return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val idx = latestBlocks.indexOfFirst { it is UserBlock && it.item.id == id }
        if (idx >= 0 && !atItemTop(listState, idx)) listState.scrollToItem(idx)
    }
    // Setelah jawaban memenuhi layar (tak ada lagi ruang kosong di bawahnya), layar mulai mengikuti teks yang mengalir.
    // Hanya jika pengguna belum menyentuh daftar: begitu pengguna menggeser, pin dilepas dan kendali sepenuhnya miliknya
    // (kembali mengikuti bila ia menggulir ke dasar atau menekan tombol turun).
    var roomSeenFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(rawTail, pinned) {
        val id = pinned ?: return@LaunchedEffect
        if (rawTail > 0) {
            roomSeenFor = id
        } else if (shouldStartFollowing(id, roomSeenFor, rawTail, userDragged[0])) {
            stick = true
            pinned = null
        }
    }
    // Mengikuti dasar daftar saat teks mengalir: SATU pelari yang bergerak tiap bingkai dengan gerak meredam halus.
    // Dulu: gulir 4000 px sekali tiap 3 bingkai + animateScrollToItem tiap blok baru (menahan gulir ~300 ms lalu menyusul
    // dengan lompatan), sehingga layar naik per baris. dispatchRawDelta tidak memakai mutex gulir, jadi tidak saling membatalkan.
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val stickNow by rememberUpdatedState(stick)
    val follow = remember { { wake.trySend(Unit); Unit } }
    LaunchedEffect(Unit) {
        // Membuka chat lama: langsung ke dasar satu kali (tanpa animasi panjang).
        if (stick && totalNow > 0) listState.scrollToItem(maxOf(0, totalNow - 1))
    }
    LaunchedEffect(total) { if (total > 0) wake.trySend(Unit) }
    LaunchedEffect(listState) {
        for (ping in wake) {
            var last = withFrameNanos { it }
            var idle = 0
            while (idle < 24) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                last = now
                if (!stickNow || listState.isScrollInProgress) {
                    idle++
                    continue
                }
                val dist = distanceToBottom(listState)
                if (dist <= 0f) {
                    idle++
                    continue
                }
                idle = 0
                val step = (dist * (1f - exp(-16f * dt))).coerceAtLeast(1f).coerceAtMost(dist)
                listState.dispatchRawDelta(step)
            }
        }
    }

    val density = LocalDensity.current
    var inputPx by remember { mutableIntStateOf(0) }
    val inputDp = with(density) { inputPx.toDp() }
    val tailDp = with(density) { (if (stick) 0 else rawTail).toDp() }
    val bg = MythosColors.Background
    val firstUser = chat.firstOrNull { it is UserItem } as? UserItem
    val title = remember(firstUser?.id) { firstUser?.text?.lineSequence()?.firstOrNull().orEmpty().trim().take(48) }
    // Daftar chat memenuhi SELURUH layar; bilah atas dan kartu input mengambang di atasnya, jadi teks tembus di belakang ikon
    // dan satu-satunya pembatas adalah tepi layar HP.
    Box(Modifier.fillMaxSize().background(bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp + 12.dp,
                bottom = inputDp + 8.dp
            )
        ) {
                itemsIndexed(blocks, key = { i, b -> if (running && i == blocks.lastIndex && b is StepsBlock) LIVE_SLOT_KEY else blockKey(b) }) { i, b ->
                    Box {
                    when (b) {
                        is UserBlock -> ItemView(b.item, active = false, animate = false, onTick = follow)
                        is StepsBlock -> StepsView(b.items, live = running && i == blocks.lastIndex, label = label, isWaiting = waiting, afterUser = i > 0 && blocks[i - 1] is UserBlock, elapsed = if (waiting) idleSecs else 0)
                        is TextBlock -> {
                            val id = b.item.id
                            DisposableEffect(id) { onDispose { played.add(id) } }
                            TextItemView(b.item, animate = id !in initialIds && id !in played, onTick = follow)
                        }
                        is TailBlock -> {
                            val id = b.item.id
                            DisposableEffect(id) { onDispose { played.add(id) } }
                            ItemView(
                                b.item, active = false, animate = id !in initialIds && id !in played, onTick = follow,
                                canUndo = !running, onUndo = onUndo, loadDiff = loadDiff, onOpenFile = onOpenFile,
                                onOpenPath = onOpenPath, onSendEmail = onSendEmail
                            )
                        }
                    }
                    }
                }
                if (extraLive) {
                    item(key = LIVE_SLOT_KEY) {
                        Box {
                            StepsView(emptyList(), live = true, label = label, isWaiting = waiting, afterUser = blocks.lastOrNull() is UserBlock, elapsed = if (waiting) idleSecs else 0)
                        }
                    }
                }
                if (showStall) {
                    item(key = "stalled") { StallNote(subText ?: STALL_TEXT, tight = extraLive || blocks.lastOrNull() is StepsBlock) }
                }
                item(key = TAIL_KEY) { Spacer(Modifier.height(tailDp)) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to bg.copy(alpha = 0.92f), 0.7f to bg.copy(alpha = 0.55f), 1f to bg.copy(alpha = 0f)))
        ) {
            TopBar {
                // Bilah atas gaya rekaman acuan: ikon polos tanpa lingkaran, judul percakapan di tengah.
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(onMenu, label = "Buka riwayat") { HeaderMenuGlyph() }
                    Text(
                        title,
                        modifier = Modifier.weight(1f).padding(start = 11.dp, end = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Medium, fontSize = 15.5.sp, color = MythosColors.Ink)
                    )
                    GlyphButton(onNewChat, label = "Chat baru") { EditGlyph() }
                    Box {
                        GlyphButton({ menuOpen = true }, label = "Menu lainnya") { HeaderKebabGlyph() }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Pengaturan") }, onClick = { menuOpen = false; onOpenSettings(SettingsSection.MODEL) })
                            DropdownMenuItem(text = { Text("Ekspor proyek (ZIP)") }, onClick = { menuOpen = false; onExport() })
                        }
                    }
                }
            }
        }
        if (!stick && listState.canScrollForward) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inputDp + 12.dp)
                    .shadow(4.dp, CircleShape, ambientColor = Color(0x14000000), spotColor = Color(0x22000000))
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable {
                        stick = true
                        pinned = null
                        scope.launch {
                            listState.scrollToItem(maxOf(0, total - 1))
                            listState.scrollBy(100_000f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) { ArrowDownGlyph() }
        }
        val chip = uiModelChip(settings)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { inputPx = it.height }
                .background(Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.3f to bg.copy(alpha = 0.8f), 1f to bg.copy(alpha = 0.95f)))
        ) {
            Spacer(Modifier.height(10.dp))
            MythosInputCard(
                text = text,
                onText = onTextChange,
                placeholder = "Balas Mythos…",
                modelName = chip.first,
                effort = chip.second,
                provider = chipProvider(settings),
                running = running,
                onPlus = { sheet = SheetKind.ADD },
                onModel = { sheet = SheetKind.MODEL },
                onMic = { sheet = SheetKind.VOICE },
                onSend = { if (text.isNotBlank() || attachments.isNotEmpty()) onSend(text.trim()) },
                onStop = onStop,
                dashed = false,
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                compact = true,
            )
            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    MythosSheets(sheet, { sheet = SheetKind.NONE }, { sheet = it }, settings, onPickProvider, onOpenSettings, ws, onAttach)
}

@Composable
private fun ActivityRow(
    live: Boolean,
    label: String,
    icon: GroupIcon,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    elapsed: Int = 0,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(enabled = expandable, onClick = onToggle)
            .padding(start = 97.px, end = 99.px),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(74.px)) {
            Crossfade(targetState = live, animationSpec = tween(200), label = "lead") { l ->
                when {
                    l -> LoaderDots()
                    icon == GroupIcon.TERMINAL -> TerminalGlyph()
                    else -> ClockGlyph()
                }
            }
        }
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                fadeIn(tween(250)).togetherWith(fadeOut(tween(200)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(250) })
            },
            modifier = Modifier.weight(1f, fill = false),
            label = "activity"
        ) { l -> ShimmerText(l, live) }
        if (SHOW_ELAPSED && live && elapsed >= 4) {
            Spacer(Modifier.width(8.dp))
            Text(
                "${elapsed} dtk",
                style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = MythosColors.Hint),
                maxLines = 1,
            )
        }
        if (expandable) {
            Spacer(Modifier.width(5.dp))
            ChevronGlyph(if (expanded) 90f else 0f)
        }
    }
}

@Composable
private fun StepsView(items: List<ChatItem>, live: Boolean, label: String, isWaiting: Boolean = false, afterUser: Boolean = false, elapsed: Int = 0) {
    var open by remember(items.firstOrNull()?.id) { mutableStateOf(false) }
    var peek by remember(items.firstOrNull()?.id) { mutableStateOf(false) }
    val hasItems = items.isNotEmpty()
    LaunchedEffect(live, hasItems) {
        peek = false
        if (!DETAIL_PEEK_ENABLED || !live || !hasItems) return@LaunchedEffect
        while (true) {
            delay(DETAIL_PEEK_EVERY_MS)
            peek = true
            delay(DETAIL_PEEK_SHOW_MS)
            peek = false
        }
    }
    val lastLive = remember(items.firstOrNull()?.id) { arrayOf<String?>(null) }
    if (live && !isWaiting && label != THINKING_LABEL && items.none { it is NoticeItem && oneLineNarration(it.text) == label }) lastLive[0] = label
    Column(Modifier.fillMaxWidth().padding(top = if (afterUser) 6.dp else 0.dp, bottom = 20.dp)) {
        ActivityRow(
            live = live,
            label = if (live) label else finishedLabel(lastLive[0], items),
            elapsed = if (live) elapsed else 0,
            icon = groupIcon(items),
            expandable = items.isNotEmpty(),
            expanded = open || peek,
            onToggle = { open = !open }
        )
        AnimatedVisibility(visible = (open || peek) && items.isNotEmpty(), enter = expandVertically(tween(220)) + fadeIn(tween(220)), exit = fadeOut(tween(220))) {
            Column {
                (if (open) items else items.takeLast(DETAIL_PEEK_MAX)).forEach { ItemView(it, active = false, animate = false, onTick = {}) }
            }
        }
    }
}

@Composable
private fun StallNote(text: String, tight: Boolean) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    // Tinggi tata letak 0: catatan hanya menggambar di ruang kosong bawah baris aktivitas, jadi muncul/hilangnya tidak menggeser isi.
    Box(Modifier.layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        layout(p.width, 0) { p.place(0, 0) }
    }) {
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 2 }
    ) {
        Crossfade(targetState = text, animationSpec = tween(300), label = "stall-note") { t ->
            Text(
                t,
                style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, color = androidx.compose.ui.graphics.Color(0xFF85847F)),
                modifier = Modifier.offset(y = if (tight) (-20).dp else 0.dp).padding(start = 171.px, top = if (tight) 0.dp else 8.dp, bottom = 8.dp)
            )
        }
    }
    }
}

@Composable
private fun StepRowShell(
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    detail: (@Composable () -> Unit)? = null,
    head: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clickable(enabled = expandable, onClick = onToggle)
                .padding(start = 97.px, end = 99.px),
            verticalAlignment = Alignment.CenterVertically
        ) { head() }
        if (expanded && detail != null) {
            Box(Modifier.fillMaxWidth().padding(start = 171.px, end = 99.px, bottom = 8.dp)) { detail() }
        }
    }
}

@Composable
private fun StepRow(
    title: String,
    active: Boolean,
    animate: Boolean,
    failed: Boolean = false,
    plainDot: Boolean = true,
    detail: (@Composable () -> Unit)? = null,
) {
    var open by remember(title.removeSuffix("…")) { mutableStateOf(false) }
    val base = cleanLine(title).removeSuffix("…")
    val typed = rememberTyped(base, animate)
    val shown = typed + if (title.endsWith("…") && typed.length == base.length) "…" else ""
    val visible = remember { MutableTransitionState(!animate).apply { targetState = true } }
    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(650)) + expandVertically(tween(450))) {
        StepRowShell(expandable = detail != null, expanded = open, onToggle = { open = !open }, detail = detail) {
            Box(Modifier.width(74.px)) {
                when {
                    active -> LoaderDots()
                    failed -> StaticDot(MythosColors.Error)
                    plainDot -> StaticDot(androidx.compose.ui.graphics.Color(0xFFB8B6B1))
                }
            }
            ShimmerText(shown, active, Modifier.weight(1f))
            if (detail != null) {
                Spacer(Modifier.width(28.px))
                ChevronGlyph(if (open) 90f else 0f)
            }
        }
    }
}

@Composable
private fun Mono(text: String, color: androidx.compose.ui.graphics.Color = MythosColors.Hint) {
    Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp, color = color))
}

@Composable
private fun TextItemView(item: ChatItem, animate: Boolean, onTick: () -> Unit) {
    val rawText = when (item) {
        is ThoughtItem -> item.text
        is FinalItem -> item.text
        else -> ""
    }
    val text = remember(rawText) { cleanForDisplay(rawText, keepTables = true) }
    val live = (item as? ThoughtItem)?.live == true
    val revealed = rememberRevealed(text, animate, onTick)
    var settling by remember { mutableStateOf(animate) }
    LaunchedEffect(revealed) {
        if (animate) {
            settling = true
            delay(300)
            settling = false
        }
    }
    val finished = revealed >= text.length
    LaunchedEffect(item.id, finished) {
        if (finished) RevealClock.markDone(item.id, SystemClock.uptimeMillis()) else RevealClock.clear(item.id)
    }
    val streaming = live || revealed < text.length
    val fading = revealed < text.length || (animate && settling)
    val visible = text.substring(0, revealed)
    val proseOf: (String, Boolean) -> AnnotatedString = { prose, isLast ->
        val base = markdownToAnnotated(prose)
        if (fading && isLast) withTailFade(base, MythosColors.Ink) else base
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 60.px, end = 60.px, top = 0.dp, bottom = 16.dp)
    ) {
        AnswerBody(visible, assistantStyle(), proseOf)
        if (LatencyTrace.SHOW && item is FinalItem && !streaming) {
            LatencyTrace.summaryOf(item.id)?.let {
                Text(
                    it,
                    style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Light, fontSize = 11.sp, color = MythosColors.Hint),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        if (item is FinalItem && !streaming && item.showStatus) {
            Text(
                if (item.verified) "✓ Perubahan sudah diperiksa ulang" else "Sebagian perubahan belum diperiksa ulang",
                style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Light, fontSize = 12.5.sp, color = MythosColors.Hint),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ItemView(
    item: ChatItem,
    active: Boolean,
    animate: Boolean,
    onTick: () -> Unit,
    canUndo: Boolean = false,
    onUndo: (ChangesItem) -> Unit = {},
    loadDiff: suspend (ChangesItem, String) -> String = { _, _ -> "" },
    onOpenFile: (ChangesItem, String, Boolean) -> Unit = { _, _, _ -> },
    onOpenPath: (String, Boolean) -> Unit = { _, _ -> },
    onSendEmail: (EmailDraftItem) -> Unit = {},
) {
    when (item) {
        is UserItem -> Column(
            Modifier.fillMaxWidth().padding(end = 24.px, bottom = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (item.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = if (item.text.isNotBlank()) 6.dp else 0.dp)) {
                    item.attachments.forEach { AttachmentCard(it) }
                }
            }
            if (item.text.isNotBlank()) {
                val shape = RoundedCornerShape(15.dp)
                Box(
                    Modifier
                        .widthIn(max = 308.dp)
                        .clip(shape)
                        .background(MythosColors.Bubble)
                        .border(0.33.dp, MythosColors.BubbleBorder, shape)
                        .padding(start = 12.dp, end = 12.dp, top = 13.dp, bottom = 13.dp)
                ) {
                    SelectionContainer { Text(item.text, style = bodyStyle().copy(fontSize = 14.5.sp, lineHeight = 19.sp)) }
                }
            }
        }

        is ThoughtItem, is FinalItem -> TextItemView(item, animate, onTick)

        is ToolItem -> StepRow(
            title = toolTitle(item.tool, item.summary) + if (item.status == ToolStatus.RUNNING) "…" else "",
            active = active,
            animate = animate,
            failed = item.status == ToolStatus.ERROR,
            detail = {
                Column {
                    Mono(item.summary)
                    if (item.preview.isNotBlank()) Mono(item.preview, MythosColors.Ink)
                    if (item.millis > 0) Mono("${item.millis} ms")
                }
            }
        )

        is PlanItem -> {
            val done = item.steps.count { it.status == StepStatus.DONE }
            StepRow(
                title = "Rencana · $done/${item.steps.size} selesai",
                active = false,
                animate = animate,
                detail = {
                    Column {
                        item.steps.forEachIndexed { i, s ->
                            val mark = when (s.status) {
                                StepStatus.TODO -> "○"
                                StepStatus.DOING -> "◐"
                                StepStatus.DONE -> "●"
                                StepStatus.BLOCKED -> "✕"
                            }
                            Text("$mark ${i + 1}. ${s.title}", style = bodyStyle())
                        }
                    }
                }
            )
        }

        is NoticeItem -> StepRow(title = item.text, active = false, animate = animate, plainDot = false)

        is ErrorItem -> Text(
            item.text,
            style = bodyStyle(MythosColors.Error),
            modifier = Modifier.fillMaxWidth().padding(start = 49.px, end = 49.px, top = 8.dp, bottom = 8.dp)
        )

        is EmailDraftItem -> EmailDraftCard(item, onOpenPath) { onSendEmail(item) }

        is ChangesItem -> ChangesCard(item, canUndo, { onUndo(item) }, { path -> loadDiff(item, path) }, { path, share -> onOpenFile(item, path, share) })
    }
}

/** Kunci item ruang kosong di ujung daftar chat (agar pesan terakhir pengguna bisa naik ke atas layar). */
private const val TAIL_KEY = "tail-space"

/**
 * Kunci SATU slot untuk baris aktivitas hidup: dipakai bersama oleh baris tambahan (sebelum kelompok langkah dirilis) dan kelompok
 * langkah terakhir yang sedang berjalan. Pergantian keduanya jadi ganti isi di slot yang sama, bukan hapus + sisip, jadi tinggi
 * daftar tidak berubah sesaat (penyebab sentakan ~180 px dua bingkai pada rekaman). Keduanya tidak pernah ada bersamaan:
 * needsExtraLive selalu false bila blok terakhir adalah kelompok langkah.
 */
/** Kapan layar mulai mengikuti teks: jawaban sudah memenuhi layar (tak ada ruang kosong di bawahnya) dan pengguna belum menyentuh daftar. */
internal fun shouldStartFollowing(pinned: Long?, roomSeenFor: Long?, rawTail: Int, userDragged: Boolean): Boolean =
    pinned != null && roomSeenFor == pinned && rawTail <= 0 && !userDragged

private const val LIVE_SLOT_KEY = "live-slot"

/** Selisih (px) antara dasar isi daftar dan tepi bawah area terlihat; 0 bila sudah di dasar. Aman terhadap perbedaan offset: canScrollForward jadi penentu akhir. */
private fun distanceToBottom(state: androidx.compose.foundation.lazy.LazyListState): Float {
    if (!state.canScrollForward) return 0f
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index != info.totalItemsCount - 1) return info.viewportSize.height.toFloat()
    val edge = info.viewportEndOffset - info.afterContentPadding
    return (last.offset + last.size - edge).toFloat().coerceAtLeast(24f)
}

/** Daftar sudah tepat di awal item [idx] (tanpa selisih gulir): tidak perlu menggulir lagi, jadi tidak ada lompatan. */
private fun atItemTop(state: androidx.compose.foundation.lazy.LazyListState, idx: Int): Boolean =
    state.firstVisibleItemIndex == idx && state.firstVisibleItemScrollOffset == 0
