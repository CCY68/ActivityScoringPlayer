package com.johnson.fitness.http

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.math.BigDecimal

/**
 * Safe BigDecimal adapter:
 * - Accepts JSON NUMBER or STRING
 * - Handles "", " ", "-", "null", "NaN", "Infinity"
 * - Handles commas "1,234.56" and percent "12.3%"
 * - Never uses DecimalFormat.parse() (it can yield weird ICU paths and crash)
 *
 * Note: write() outputs JSON number (NOT formatted string).
 */
class BigDecimalTypeAdapter(
    private val nullOnInvalid: Boolean = true,
) : TypeAdapter<BigDecimal?>() {

    @Throws(IOException::class)
    override fun write(out: JsonWriter, value: BigDecimal?) {
        if (value == null) {
            out.nullValue()
            return
        }
        // ✅ 寫回 JSON number（不要千分位、不要固定小數）
        out.value(value)
    }

    @Throws(IOException::class)
    override fun read(`in`: JsonReader): BigDecimal? {
        return when (`in`.peek()) {
            JsonToken.NULL -> {
                `in`.nextNull()
                null
            }

            JsonToken.NUMBER -> {
                // ✅ Gson 會把 NUMBER 以字串形式吐出（但 token 仍是 NUMBER）
                parseBigDecimalSafely(`in`.nextString())
            }

            JsonToken.STRING -> {
                parseBigDecimalSafely(`in`.nextString())
            }

            // 這些如果後端偶爾亂給，也防一下
            JsonToken.BOOLEAN -> {
                val b = `in`.nextBoolean()
                if (nullOnInvalid) null else if (b) BigDecimal.ONE else BigDecimal.ZERO
            }

            else -> {
                // 其他型別直接跳過，避免掛
                `in`.skipValue()
                null
            }
        }
    }

    private fun parseBigDecimalSafely(raw: String?): BigDecimal? {
        if (raw == null) return null

        val s0 = raw.trim()
        if (s0.isEmpty()) return null
        if (s0 == "-" || s0.equals("null", true)) return null

        // NaN / Infinity 這類 BigDecimal 不能處理
        if (s0.equals("nan", true) ||
            s0.equals("infinity", true) ||
            s0.equals("+infinity", true) ||
            s0.equals("-infinity", true)
        ) return null

        // 去掉常見格式字元：逗號、空白、全形逗號、百分比
        // (如果你不想支援 %，可以把它拿掉)
        val normalized = s0
            .replace(",", "")
            .replace("，", "")
            .replace(" ", "")
            .removeSuffix("%")

        if (normalized.isEmpty()) return null

        return runCatching {
            BigDecimal(normalized)
        }.getOrElse {
            if (nullOnInvalid) null else BigDecimal.ZERO
        }
    }
}
