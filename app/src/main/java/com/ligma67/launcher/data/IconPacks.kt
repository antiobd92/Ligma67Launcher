package com.ligma67.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class IconPackInfo(val packageName: String, val label: String)

/**
 * ADW / Nova / Apex compatible icon pack (appfilter.xml) support — the format used by
 * virtually every icon pack on the Play Store.
 */
class IconPack private constructor(
    private val res: Resources,
    private val pkg: String,
    private val byComponent: Map<String, String>,
    private val byPackage: Map<String, String>,
) {
    private val idCache = HashMap<String, Int>()

    fun drawableFor(cn: ComponentName): Drawable? {
        val name = byComponent["${cn.packageName}/${cn.className}"] ?: byPackage[cn.packageName] ?: return null
        val id = synchronized(idCache) {
            idCache.getOrPut(name) { res.getIdentifier(name, "drawable", pkg) }
        }
        if (id == 0) return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
    }

    companion object {
        private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme")

        fun installed(context: Context): List<IconPackInfo> {
            val pm = context.packageManager
            val found = LinkedHashMap<String, IconPackInfo>()
            val intents = ACTIONS.map { Intent(it) } +
                Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME")
            for (intent in intents) {
                val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
                for (ri in list) {
                    val p = ri.activityInfo.packageName
                    if (p !in found) found[p] = IconPackInfo(p, ri.loadLabel(pm).toString())
                }
            }
            return found.values.sortedBy { it.label.lowercase() }
        }

        fun load(context: Context, pkg: String): IconPack? = runCatching {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val parser: XmlPullParser = res.getIdentifier("appfilter", "xml", pkg).takeIf { it != 0 }
                ?.let { res.getXml(it) }
                ?: XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(res.assets.open("appfilter.xml"), "UTF-8")
                }
            val byComponent = HashMap<String, String>()
            val byPackage = HashMap<String, String>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (comp != null && !drawable.isNullOrEmpty()) {
                        val inner = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                        val p = inner.substringBefore('/')
                        var c = inner.substringAfter('/', "")
                        if (c.startsWith(".")) c = p + c
                        if (p.isNotEmpty()) {
                            if (c.isNotEmpty()) byComponent["$p/$c"] = drawable
                            byPackage.putIfAbsent(p, drawable)
                        }
                    }
                }
                event = parser.next()
            }
            IconPack(res, pkg, byComponent, byPackage)
        }.getOrNull()
    }
}
