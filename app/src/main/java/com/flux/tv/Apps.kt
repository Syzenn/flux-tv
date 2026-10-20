package com.flux.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build

/** Une appli installée sur le boîtier, qu'on peut ouvrir depuis l'accueil. */
class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    /** Bannière 16:9 des applis Android TV (Netflix, YouTube…), quand elle existe. */
    val banner: Drawable?,
    val launch: Intent,
)

object Apps {

    /** Applis IPTV mises en tête de rangée. */
    private val PRIORITY = listOf("iron")

    /** À appeler hors du thread principal : charger les icônes prend un peu de temps. */
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, AppEntry>()

        fun query(category: String, tv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val list: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || found.containsKey(pkg)) continue
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val banner = if (tv) runCatching { ai.loadBanner(pm) }.getOrNull() else null
                val icon = runCatching { ai.loadIcon(pm) }.getOrNull()
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg).trim()
                found[pkg] = AppEntry(pkg, label, icon, banner, launch)
            }
        }

        // D'abord les applis faites pour la TV (avec bannière), puis les autres.
        query(Intent.CATEGORY_LEANBACK_LAUNCHER, tv = true)
        query(Intent.CATEGORY_LAUNCHER, tv = false)

        return found.values.sortedWith(
            compareBy<AppEntry>(
                { app -> PRIORITY.indexOfFirst { app.label.contains(it, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it } },
                { it.label.lowercase() },
            )
        )
    }
}
