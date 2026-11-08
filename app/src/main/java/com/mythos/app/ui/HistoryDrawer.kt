package com.mythos.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythos.app.R

internal val DrawerBg = Color(0xFFF1F1EF)

private fun itemStyle(c: Color = Color(0xFF141413)) =
    TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, color = c)

@Composable
private fun NavItem(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(label, style = itemStyle())
    }
}

/**
 * Isi laci navigasi 1:1 sesuai video:
 * - Latar #F1F1EF, judul serif "Mythos" di kiri atas.
 * - Empat menu ("Chat", "Proyek", "Code", "Artefak") dengan ikon kustom identik video.
 * - Seksi "Terbaru" dengan ikon gelembung percakapan di tiap baris.
 * - Bilah bawah mengambang dengan gradien pudar halus: avatar bulat di kiri dan tombol hitam "+ Chat baru" di kanan.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryDrawerContent(
    chats: List<ChatMeta>,
    onNew: () -> Unit,
    onOpen: (ChatMeta) -> Unit,
    onDelete: (ChatMeta) -> Unit,
    onProjects: () -> Unit,
    onCode: () -> Unit,
    onArtifacts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxHeight()
            .background(DrawerBg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Text(
                "Mythos",
                style = TextStyle(
                    fontFamily = MythosSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    color = MythosColors.Ink,
                ),
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 18.dp)
            )
            NavItem({ DrawerChatGlyph() }, "Chat", onNew)
            NavItem({ ProyekGlyph() }, "Proyek", onProjects)
            NavItem({ CodeGlyph() }, "Code", onCode)
            NavItem({ ArtefakGlyph() }, "Artefak", onArtifacts)
            Spacer(Modifier.height(18.dp))
            if (chats.isNotEmpty()) {
                Text(
                    "Terbaru",
                    style = TextStyle(
                        fontFamily = MythosSans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFF4B4948),
                    ),
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
                )
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                if (chats.isNotEmpty()) {
                    items(chats, key = { it.id }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .combinedClickable(
                                    onClick = { onOpen(c) },
                                    onLongClickLabel = "Hapus riwayat",
                                    onLongClick = { onDelete(c) }
                                )
                                .padding(start = 24.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BubbleGlyph()
                            Spacer(Modifier.width(16.dp))
                            Text(
                                c.title.ifBlank { "(tanpa judul)" },
                                style = itemStyle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            "Belum ada riwayat chat.",
                            style = TextStyle(
                                fontFamily = MythosSans,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = Color(0xFF8E8C87),
                            ),
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)
                        )
                    }
                }
            }
        }

        // Bilah bawah mengambang dengan gradien pudar di atas daftar "Terbaru"
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0.0f to DrawerBg.copy(alpha = 0f),
                        0.38f to DrawerBg.copy(alpha = 0.92f),
                        1.0f to DrawerBg,
                    )
                )
                .padding(top = 20.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 26.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .shadow(2.dp, CircleShape, ambientColor = Color(0x14000000), spotColor = Color(0x1E000000))
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(0.67.dp, Color(0xFFDCDCD9), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_drawer_avatar),
                        contentDescription = "Profil",
                        modifier = Modifier.size(43.dp).clip(CircleShape)
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .height(48.dp)
                        .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x22000000), spotColor = Color(0x2E000000))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF202020))
                        .border(0.67.dp, Color(0xFF3A3A3A), RoundedCornerShape(24.dp))
                        .clickable(onClick = onNew)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Chat baru",
                        style = TextStyle(
                            fontFamily = MythosSans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.5.sp,
                            color = Color.White,
                        )
                    )
                }
            }
            NavBarFill()
        }
    }
}
