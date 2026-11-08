package com.mythos.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.mythos.app.ProviderChoice
import com.mythos.app.R

/**
 * Logo penyedia. Untuk mengganti dengan berkas resmi: taruh gambar bernama sama (ic_provider_*) di res/drawable
 * (hapus dulu file .xml dengan nama yang sama bila gambarnya PNG/WebP); kode ini tidak perlu diubah.
 * null = penyedia belum punya logo (baris tampil tanpa ikon).
 */
@DrawableRes
internal fun providerLogo(p: ProviderChoice): Int? = when (p) {
    ProviderChoice.OPENAI -> R.drawable.ic_provider_openai
    ProviderChoice.ANTHROPIC -> R.drawable.ic_provider_anthropic
    ProviderChoice.GEMINI -> R.drawable.ic_provider_gemini
    ProviderChoice.DEEPSEEK -> R.drawable.ic_provider_deepseek
    ProviderChoice.GROQ -> R.drawable.ic_provider_groq
    ProviderChoice.OLLAMA -> R.drawable.ic_provider_ollama
    ProviderChoice.HUGGINGFACE -> R.drawable.ic_provider_huggingface
    ProviderChoice.GITHUB_MODELS -> R.drawable.ic_provider_github
    ProviderChoice.OPENROUTER -> R.drawable.ic_provider_openrouter
}

/** Digambar dengan Image (bukan Icon) agar warna asli logo tidak ditimpa tint. */
@Composable
internal fun ProviderLogo(p: ProviderChoice, size: Dp, modifier: Modifier = Modifier) {
    val res = providerLogo(p) ?: return
    Image(painterResource(res), contentDescription = null, modifier = modifier.size(size))   // label ada di teks sebelahnya
}
