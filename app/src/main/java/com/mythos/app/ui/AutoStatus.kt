package com.mythos.app.ui

import com.mythos.app.ProviderChoice
import com.mythos.app.UserSettings

/** Tampilan chip model: model kosong berarti otomatis di dalam provider yang dipilih. */
internal fun autoChip(s: UserSettings, active: String? = null, healthy: Int? = null, total: Int = 0): Pair<String, String> =
    s.displayModel to s.displayProvider

internal fun uiModelChip(s: UserSettings, active: String? = null, healthy: Int? = null, total: Int = 0): Pair<String, String> =
    autoChip(s, active, healthy, total)

internal fun chipProvider(s: UserSettings, active: String? = null): ProviderChoice = s.provider
