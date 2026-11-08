package com.mythos.app.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pengait dari ViewModel ke lembar "Tambahkan ke chat" dan "Konektor" (sebelumnya hanya pajangan):
 * proyek yang sungguhan, riset web, konektor, dan input suara.
 */
class WorkspaceHooks(
    val projects: List<ChatMeta>,
    val currentProjectId: String?,
    val webEnabled: Boolean,
    val research: Boolean,
    val githubReady: Boolean,
    val onResearch: (Boolean) -> Unit,
    val onWeb: (Boolean) -> Unit,
    val onPickProject: (ChatMeta?) -> Unit,
    val onMic: () -> Unit,
    val workspaceReady: Boolean = false,
    val activeGoogleAccountEmail: String = "",
    val googleAccountCount: Int = 0,
    val gmailConnected: Boolean = false,
    val calendarConnected: Boolean = false,
    val driveConnected: Boolean = false,
    val onToggleGoogleService: (com.mythos.app.agent.WorkspaceServiceKind, Boolean) -> Unit = { _, _ -> },
    val currentModel: String = "",
    val onPickModel: (String) -> Unit = {},
) {
    val projectLabel: String
        get() = currentProjectId?.let { id -> projects.firstOrNull { it.id == id }?.title?.ifBlank { null } ?: id } ?: "Tidak ada"
}

data class ProjectInfo(val meta: ChatMeta, val files: Int, val bytes: Long)

data class WorkFile(val file: File, val rel: String, val project: String, val size: Long, val modified: Long)

object Workspace {
    private val ARTIFACT_EXT = setOf(
        "html", "htm", "md", "txt", "svg", "pdf", "png", "jpg", "jpeg", "gif", "webp",
        "json", "csv", "docx", "xlsx", "pptx", "zip", "apk"
    )
    private val TEXT_EXT = setOf(
        "html", "htm", "md", "txt", "svg", "json", "csv", "kt", "kts", "java", "xml", "gradle", "yml", "yaml", "js", "ts",
        "css", "py", "sh", "toml", "properties", "c", "cpp", "h", "rs", "go", "sql", "ini", "gitignore", "log", "tsx", "jsx"
    )
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp")
    const val MAX_TEXT_BYTES = 200_000L

    fun ext(f: File): String = f.name.substringAfterLast('.', "").lowercase(Locale.US)
    fun isText(f: File): Boolean = ext(f) in TEXT_EXT || (ext(f).isEmpty() && (!f.exists() || (f.isFile && f.length() < MAX_TEXT_BYTES && !com.mythos.app.agent.isBinary(f))))
    fun isImage(f: File): Boolean = ext(f) in IMAGE_EXT
    fun isWeb(f: File): Boolean = ext(f).let { it == "html" || it == "htm" || it == "svg" }

    fun mimeOf(f: File): String {
        val e = ext(f)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)
            ?: if (isText(f)) "text/plain" else "application/octet-stream"
    }

    /** Semua berkas proyek (tanpa folder .git), diurutkan menurut jalur. */
    fun allFiles(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().maxDepth(8)
            .onEnter { it == dir || it.name != ".git" }
            .filter { it.isFile }
            .take(3000)
            .toList()
            .sortedBy { it.relativeTo(dir).path.lowercase(Locale.US) }
    }

    fun rel(dir: File, f: File): String = f.relativeTo(dir).path.replace(File.separatorChar, '/')

    /** Artefak = hasil buatan agen yang bisa dibuka/dibagikan; unggahan pengguna, .github, dan berkas tersembunyi dilewati. */
    fun isArtifact(rel: String): Boolean {
        val parts = rel.split('/')
        if (parts.any { it.startsWith(".") }) return false
        if (parts.size > 1 && parts[0] == "uploads") return false
        return rel.substringAfterLast('.', "").lowercase(Locale.US) in ARTIFACT_EXT
    }

    fun scanProjects(chats: List<ChatMeta>): List<ProjectInfo> = chats.map { m ->
        val files = allFiles(File(m.projectPath))
        ProjectInfo(m, files.size, files.sumOf { it.length() })
    }

    fun scanArtifacts(chats: List<ChatMeta>): List<WorkFile> {
        val out = ArrayList<WorkFile>()
        for (m in chats) {
            val dir = File(m.projectPath)
            for (f in allFiles(dir)) {
                val r = rel(dir, f)
                if (isArtifact(r)) out.add(WorkFile(f, r, m.title.ifBlank { m.id }, f.length(), f.lastModified()))
            }
        }
        return out.sortedByDescending { it.modified }.take(500)
    }

    fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    }

    fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id", "ID")).format(Date(ms))
}

// ---------------------------------------------------------------- kerangka bersama

private val RowBg = Color.White

private fun headStyle() = TextStyle(fontFamily = MythosSerif, fontSize = 24.sp, color = MythosColors.Ink)
private fun rowTitle() = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 20.sp, color = MythosColors.Ink)
private fun rowSub() = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Light, fontSize = 12.sp, lineHeight = 16.sp, color = MythosColors.Hint)

@Composable
private fun WorkspaceScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MythosColors.Background)) {
        TopBar {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clickable(onClickLabel = "Kembali", role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", tint = MythosColors.Ink) }
                Spacer(Modifier.width(4.dp))
                Text(title, style = headStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                actions()
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        NavBarFill()
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = rowSub().copy(fontSize = 13.sp, lineHeight = 19.sp))
    }
}

@Composable
private fun IconBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = label }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = MythosColors.Hint, modifier = Modifier.size(19.dp)) }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFFEFEDEB)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, style = rowTitle().copy(fontSize = 13.sp)) }
}

// ---------------------------------------------------------------- Proyek

@Composable
fun ProjectsScreen(
    chats: List<ChatMeta>,
    refreshKey: Any,
    onBack: () -> Unit,
    onOpen: (ChatMeta) -> Unit,
    onFiles: (ChatMeta) -> Unit,
    onExport: (ChatMeta) -> Unit,
    onDelete: (ChatMeta) -> Unit,
) {
    val infos by produceState<List<ProjectInfo>?>(null, chats.toList(), refreshKey) {
        value = withContext(Dispatchers.IO) { Workspace.scanProjects(chats.toList()) }
    }
    WorkspaceScaffold("Proyek", onBack) {
        val list = infos
        when {
            list == null -> EmptyNote("Memuat proyek…")
            list.isEmpty() -> EmptyNote("Belum ada proyek. Setiap percakapan dengan agen membuat satu proyek; hasilnya muncul di sini.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.meta.id }) { p ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RowBg).clickable { onOpen(p.meta) }.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
                        Text(p.meta.title.ifBlank { "(tanpa judul)" }, style = rowTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.files} file · ${Workspace.size(p.bytes)} · ${Workspace.date(p.meta.updatedAt)}", style = rowSub())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconBtn(Icons.Outlined.Code, "Lihat file") { onFiles(p.meta) }
                            IconBtn(Icons.Outlined.Share, "Ekspor ZIP") { onExport(p.meta) }
                            IconBtn(Icons.Outlined.Delete, "Hapus proyek") { onDelete(p.meta) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Code

@Composable
fun CodeScreen(
    projects: List<ChatMeta>,
    selectedId: String?,
    refreshKey: Any,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenExternal: (File) -> Unit,
    onShare: (File) -> Unit,
) {
    val selected = projects.firstOrNull { it.id == selectedId }
    val dir = selected?.let { File(it.projectPath) }
    var open by remember(selectedId) { mutableStateOf<File?>(null) }
    var menu by remember { mutableStateOf(false) }
    val files by produceState<List<File>?>(null, dir, refreshKey) {
        value = if (dir == null) emptyList() else withContext(Dispatchers.IO) { Workspace.allFiles(dir) }
    }
    BackHandler(enabled = open != null) { open = null }

    val current = open
    if (current != null && dir != null) {
        FileViewer(current, Workspace.rel(dir, current), onClose = { open = null }, onOpenExternal = onOpenExternal, onShare = onShare)
        return
    }
    WorkspaceScaffold("Code", onBack, actions = {
        if (projects.isNotEmpty()) {
            Box {
                Pill(selected?.title?.ifBlank { null }?.take(18) ?: "Pilih proyek") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    projects.forEach { m ->
                        DropdownMenuItem(text = { Text(m.title.ifBlank { m.id }, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { menu = false; onSelect(m.id) })
                    }
                }
            }
        }
    }) {
        val list = files
        when {
            projects.isEmpty() -> EmptyNote("Belum ada proyek. Mulai chat dengan agen, lalu file kodenya bisa dilihat di sini.")
            dir == null -> EmptyNote("Pilih proyek di kanan atas untuk melihat file kodenya.")
            list == null -> EmptyNote("Memuat file…")
            list.isEmpty() -> EmptyNote("Proyek ini belum punya file.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(list, key = { it.path }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RowBg).clickable { open = f }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(fileIcon(f), null, tint = MythosColors.Hint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Workspace.rel(dir, f), style = rowTitle().copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(Workspace.size(f.length()), style = rowSub())
                    }
                }
            }
        }
    }
}

private fun fileIcon(f: File): ImageVector = when {
    Workspace.isImage(f) -> Icons.Outlined.Image
    Workspace.isText(f) && Workspace.ext(f) in setOf("md", "txt", "csv", "json") -> Icons.Outlined.Description
    Workspace.isText(f) -> Icons.Outlined.Code
    else -> Icons.Outlined.Inventory2
}

// ---------------------------------------------------------------- Artefak

@Composable
fun ArtifactsScreen(
    chats: List<ChatMeta>,
    refreshKey: Any,
    onBack: () -> Unit,
    onOpenExternal: (File) -> Unit,
    onShare: (File) -> Unit,
) {
    val items by produceState<List<WorkFile>?>(null, chats.toList(), refreshKey) {
        value = withContext(Dispatchers.IO) { Workspace.scanArtifacts(chats.toList()) }
    }
    var open by remember { mutableStateOf<WorkFile?>(null) }
    BackHandler(enabled = open != null) { open = null }
    val current = open
    if (current != null) {
        FileViewer(current.file, current.rel, onClose = { open = null }, onOpenExternal = onOpenExternal, onShare = onShare)
        return
    }
    WorkspaceScaffold("Artefak", onBack) {
        val list = items
        when {
            list == null -> EmptyNote("Memuat artefak…")
            list.isEmpty() -> EmptyNote("Belum ada artefak. Minta agen membuat halaman HTML, dokumen, gambar SVG, atau berkas lain; hasilnya muncul di sini.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.file.path }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RowBg).clickable { open = a }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFEBEBE8)), contentAlignment = Alignment.Center) {
                            Icon(fileIcon(a.file), null, tint = MythosColors.Icon, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.rel, style = rowTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${a.project} · ${Workspace.size(a.size)} · ${Workspace.date(a.modified)}", style = rowSub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconBtn(Icons.Outlined.Share, "Bagikan ${a.rel}") { onShare(a.file) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- penampil berkas

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPreview(content: String, mime: String, js: Boolean) {
    androidx.compose.runtime.key(content, js) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = js
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL(null, content, mime, "utf-8", null)
                }
            },
        )
    }
}

@Composable
private fun FileViewer(file: File, rel: String, onClose: () -> Unit, onOpenExternal: (File) -> Unit, onShare: (File) -> Unit) {
    val isText = Workspace.isText(file)
    val isImage = Workspace.isImage(file)
    val isWeb = Workspace.isWeb(file)
    var preview by remember(file) { mutableStateOf(isWeb) }
    var js by remember(file) { mutableStateOf(false) }
    val text by produceState<String?>(null, file) {
        value = if (!isText) "" else withContext(Dispatchers.IO) {
            try {
                if (file.length() > Workspace.MAX_TEXT_BYTES) {
                    val buf = ByteArray(Workspace.MAX_TEXT_BYTES.toInt())
                    var off = 0
                    file.inputStream().use { s ->
                        while (off < buf.size) {
                            val n = s.read(buf, off, buf.size - off)
                            if (n <= 0) break
                            off += n
                        }
                    }
                    String(buf, 0, off, Charsets.UTF_8) + "\n\n… (dipotong, file terlalu besar; pakai \"Buka dengan…\")"
                } else file.readText()
            } catch (e: Exception) {
                "(gagal membaca file: ${e.message})"
            }
        }
    }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = if (!isImage) null else withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    WorkspaceScaffold(rel.substringAfterLast('/'), onClose) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isWeb) Pill(if (preview) "Lihat kode" else "Pratinjau") { preview = !preview }
                Pill("Buka dengan…") { onOpenExternal(file) }
                Pill("Bagikan") { onShare(file) }
            }
            if (isWeb && preview) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Izinkan JavaScript", style = rowSub(), modifier = Modifier.weight(1f))
                    Switch(checked = js, onCheckedChange = { js = it })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(RowBg)) {
                val t = text
                val bmp = bitmap
                when {
                    isWeb && preview && t != null -> WebPreview(t, if (Workspace.ext(file) == "svg") "image/svg+xml" else "text/html", js)
                    isImage -> if (bmp != null) Image(bmp, rel, Modifier.fillMaxSize().padding(8.dp)) else EmptyNote("Memuat gambar…")
                    isText -> if (t == null) EmptyNote("Memuat…") else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(t, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = MythosColors.Ink))
                    }
                    else -> EmptyNote("Tidak ada pratinjau untuk jenis file ini (${Workspace.size(file.length())}). Pakai \"Buka dengan…\" atau \"Bagikan\".")
                }
            }
        }
    }
}
