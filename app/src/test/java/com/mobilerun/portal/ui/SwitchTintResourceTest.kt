package com.mobilerun.portal.ui

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SwitchTintResourceTest {
    private val resDir: File = locateResDir()

    @Test
    fun switchStyleUsesExplicitTintSelectors() {
        val themes = resource("values/themes.xml").readText()

        assertTrue(themes.contains("<item name=\"useMaterialThemeColors\">false</item>"))
        assertTrue(themes.contains("<item name=\"thumbTint\">@color/mobilerun_switch_thumb_tint</item>"))
        assertTrue(themes.contains("<item name=\"trackTint\">@color/mobilerun_switch_track_tint</item>"))
    }

    @Test
    fun trackTintSeparatesCheckedAndUncheckedStates() {
        val track = resource("color/mobilerun_switch_track_tint.xml").readText()

        assertTrue(track.contains("android:state_enabled=\"false\""))
        assertTrue(track.contains("android:color=\"@color/text_gray_dark\""))
        assertTrue(track.contains("android:state_checked=\"true\""))
        assertTrue(track.contains("android:color=\"@color/mobilerun_primary\""))
        assertTrue(track.contains("android:color=\"@color/text_gray_light\""))

        val checkedColor = resolvedColor("mobilerun_primary")
        val uncheckedColor = resolvedColor("text_gray_light")
        assertNotEquals("Checked and unchecked colors must differ in day mode", checkedColor, uncheckedColor)

        val checkedNightColor = resolvedColor("mobilerun_primary", "values-night")
        val uncheckedNightColor = resolvedColor("text_gray_light", "values-night")
        assertNotEquals(
            "Checked and unchecked colors must differ in night mode",
            checkedNightColor,
            uncheckedNightColor,
        )
    }

    @Test
    fun thumbTintUsesWhiteWhenEnabledAndGrayWhenDisabled() {
        val thumb = resource("color/mobilerun_switch_thumb_tint.xml").readText()

        assertTrue(thumb.contains("android:state_enabled=\"false\""))
        assertTrue(thumb.contains("android:color=\"@color/text_gray\""))
        assertTrue(thumb.contains("android:color=\"@color/white\""))
    }

    private fun resource(path: String): File = File(resDir, path)

    private fun resolvedColor(name: String, qualifier: String = "values"): String {
        val baseColors = colorValues("values/colors.xml")
        val qualifiedColors = if (qualifier == "values") {
            emptyMap()
        } else {
            colorValues("$qualifier/colors.xml")
        }
        val resolving = linkedSetOf<String>()

        fun resolve(resourceName: String): String {
            check(resourceName !in resolving) {
                "Color resource cycle: ${(resolving.toList() + resourceName).joinToString(" -> ")}"
            }
            resolving += resourceName
            val value = qualifiedColors[resourceName] ?: baseColors[resourceName]
            requireNotNull(value) { "Missing color resource: $resourceName" }

            return if (value.startsWith("@color/")) {
                resolve(value.removePrefix("@color/"))
            } else {
                canonicalColor(value)
            }
        }

        return resolve(name)
    }

    private fun colorValues(path: String): Map<String, String> {
        val regex = Regex("""<color\s+name="([^"]+)">([^<]+)</color>""")
        return regex.findAll(resource(path).readText()).associate { match ->
            match.groupValues[1] to match.groupValues[2].trim()
        }
    }

    private fun canonicalColor(value: String): String {
        val hex = value.removePrefix("#")
        require(hex.matches(Regex("[0-9A-Fa-f]+"))) { "Unsupported color value: $value" }
        val argb = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> error("Unsupported color value: $value")
        }
        return "#${argb.uppercase()}"
    }

    private fun locateResDir(): File {
        val start = File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))
            .absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { File(it, "src/main/res") }
            .first { File(it, "values/themes.xml").isFile }
    }
}
