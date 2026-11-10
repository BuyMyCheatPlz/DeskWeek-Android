package com.deskweek.android.core.calendar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 农历跨语言对照：桌面端 `vendor/lunar`（JS）与 Android 端 `cn.6tail:lunar`（Java）
 * 对 2026 全年 + 2027 年 1 月共 396 天，农历月/日、节气、节日必须逐日一致。
 */
class LunarProviderTest {

    private val days: JsonArray by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("lunar-2026.json")
            ?: error("lunar-2026.json missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject["days"]!!.jsonArray
    }

    @Test
    fun `every day matches the desktop lunar implementation`() {
        assertEquals(396, days.size)
        val allDiffs = mutableListOf<String>()

        for (element in days) {
            val expected = element.jsonObject
            val date = LocalDate.parse(expected["date"]!!.jsonPrimitive.content)
            val actual = LunarProvider.of(date)

            val diffs = mutableListOf<String>()
            fun check(field: String, exp: Any, act: Any) {
                if (exp != act) diffs.add("$date $field expected=$exp actual=$act")
            }
            check("lunarMonth", expected["lunarMonth"]!!.jsonPrimitive.content.toInt(), actual.lunarMonth)
            check("lunarDay", expected["lunarDay"]!!.jsonPrimitive.content.toInt(), actual.lunarDay)
            check("monthCn", expected["monthCn"]!!.jsonPrimitive.content, actual.monthCn)
            check("dayCn", expected["dayCn"]!!.jsonPrimitive.content, actual.dayCn)
            check("jieQi", expected["jieQi"]!!.jsonPrimitive.content, actual.jieQi)
            check(
                "lunarFestivals",
                expected["lunarFestivals"]!!.jsonArray.map { it.jsonPrimitive.content },
                actual.lunarFestivals,
            )
            check(
                "solarFestivals",
                expected["solarFestivals"]!!.jsonArray.map { it.jsonPrimitive.content },
                actual.solarFestivals,
            )
            if (diffs.isNotEmpty()) allDiffs.addAll(diffs)
        }

        assertEquals(
            "diff count=${allDiffs.size}\n" + allDiffs.take(12).joinToString("\n"),
            0,
            allDiffs.size,
        )
    }

    @Test
    fun `report mismatches`() {
        // 诊断用：输出全部差异，便于判断哪些字段口径不同
    }

    @Test
    fun `known dates resolve to expected lunar values`() {
        // 2026 年春节：2 月 17 日
        val springFestival = LunarProvider.of(LocalDate.of(2026, 2, 17))
        assertEquals(1, springFestival.lunarMonth)
        assertEquals(1, springFestival.lunarDay)
        assertEquals("正", springFestival.monthCn)
        assertEquals("初一", springFestival.dayCn)
        assertEquals("春节", springFestival.lunarFestivals.firstOrNull())

        // 2026 年立春：2 月 4 日
        assertEquals("立春", LunarProvider.of(LocalDate.of(2026, 2, 4)).jieQi)

        // 2026 年中秋：9 月 25 日
        assertEquals("中秋节", LunarProvider.of(LocalDate.of(2026, 9, 25)).lunarFestivals.firstOrNull())
    }

    @Test
    fun `short label prefers solar term then festival then day`() {
        assertEquals("小寒", LunarProvider.of(LocalDate.of(2026, 1, 5)).shortLabel)
        assertEquals("春节", LunarProvider.of(LocalDate.of(2026, 2, 17)).shortLabel)
        assertEquals("雨水", LunarProvider.of(LocalDate.of(2026, 2, 18)).shortLabel)
        assertEquals("初三", LunarProvider.of(LocalDate.of(2026, 11, 11)).shortLabel)
    }
}
