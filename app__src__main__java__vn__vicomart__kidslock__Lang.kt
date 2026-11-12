package vn.vicomart.kidslock

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * In-app language: "" = follow the phone, "vi" = Tiếng Việt, "en" = English.
 * Activities follow AppCompat per-app locales; services/overlays use [wrap].
 */
object Lang {
    fun apply(ctx: Context, tag: String) {
        Prefs(ctx).langTag = tag
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }

    fun current(ctx: Context): String = Prefs(ctx).langTag

    /** Context whose resources use the chosen app language (for services and overlays). */
    fun wrap(ctx: Context): Context {
        val tag = Prefs(ctx).langTag
        if (tag.isEmpty()) return ctx
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocales(LocaleList.forLanguageTags(tag))
        return ctx.createConfigurationContext(cfg)
    }
}
