package top.rootu.lampa

import com.google.gson.JsonParser

/** Bounded display data returned by our read-only browser query. */
data class WebPlaybackSnapshot(
    val key: String, val title: String, val subtitle: String,
    val artwork: String, val tmdbPath: String, val state: Int,
    val position: Long, val duration: Long, val rate: Float, val seekable: Boolean,
) {
    companion object {
        fun parse(raw: String): WebPlaybackSnapshot? = try {
            if (raw.length > 24_000) null else {
                var value = JsonParser.parseString(raw)
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value = JsonParser.parseString(value.asString)
                if (!value.isJsonObject) null else {
                    val obj = value.asJsonObject
                    fun text(name: String, limit: Int): String = obj.get(name)?.takeIf {
                        it.isJsonPrimitive && it.asJsonPrimitive.isString
                    }?.asString.orEmpty().replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(limit)
                    fun number(name: String): Double = obj.get(name)?.takeIf {
                        it.isJsonPrimitive && it.asJsonPrimitive.isNumber
                    }?.asDouble?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
                    val state = number("state").toInt()
                    if (state !in setOf(1, 2, 3, 6, 7)) null else WebPlaybackSnapshot(
                        text("key", 80), text("title", 500), text("subtitle", 500),
                        text("artwork", 4096), text("tmdbPath", 1024), state,
                        (number("position").coerceAtMost(31_536_000.0) * 1000).toLong(),
                        (number("duration").coerceAtMost(31_536_000.0) * 1000).toLong(),
                        number("rate").coerceAtMost(16.0).toFloat(),
                        obj.get("seekable")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true,
                    )
                }
            }
        } catch (_: RuntimeException) { null }
    }
}
