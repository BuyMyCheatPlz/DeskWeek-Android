package com.deskweek.android.core.calendar

import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar
import java.time.LocalDate

/**
 * 农历 / 节气 / 节日。
 *
 * 用 `cn.6tail:lunar` —— 与桌面端 `vendor/lunar/lunar.js` 同一作者、同一算法的 Java 版，
 * 保证两端对同一天给出相同结果（见测试 `LunarProviderTest` 的 396 天逐日对照）。
 */
object LunarProvider {

    data class DayInfo(
        val date: String,
        val lunarMonth: Int,     // 负数表示闰月
        val lunarDay: Int,
        val monthCn: String,
        val dayCn: String,
        /** 当天节气名，没有则为空 */
        val jieQi: String,
        /** 当天农历节日，如 春节、除夕 */
        val lunarFestivals: List<String>,
        /** 当天公历节日，如 元旦 */
        val solarFestivals: List<String>,
    ) {
        /** 日期格子下方的短标签：优先节气，其次节日，最后农历日 */
        val shortLabel: String
            get() = when {
                jieQi.isNotEmpty() -> jieQi
                lunarFestivals.isNotEmpty() -> lunarFestivals.first()
                solarFestivals.isNotEmpty() -> solarFestivals.first()
                lunarDay == 1 -> "${monthCn}月"
                else -> dayCn
            }

        val isFestival: Boolean
            get() = jieQi.isNotEmpty() || lunarFestivals.isNotEmpty() || solarFestivals.isNotEmpty()
    }

    fun of(date: LocalDate): DayInfo {
        val solar = Solar.fromYmd(date.year, date.monthValue, date.dayOfMonth)
        val lunar: Lunar = solar.lunar
        return DayInfo(
            date = date.toString(),
            lunarMonth = lunar.month,
            lunarDay = lunar.day,
            monthCn = lunar.monthInChinese,
            dayCn = lunar.dayInChinese,
            jieQi = lunar.jieQi ?: "",
            lunarFestivals = lunar.festivals ?: emptyList(),
            solarFestivals = (solar.festivals ?: emptyList()) + nthWeekdayFestivals(date),
        )
    }

    /**
     * JS 版 lunar.js 会给「第 N 个星期几」类公历纪念日，Java 版没有；
     * 两端对照时只差这三条（2026 年实测），在这里补齐以保持输出一致。
     */
    private fun nthWeekdayFestivals(date: LocalDate): List<String> {
        val out = ArrayList<String>(1)
        val nth = (date.dayOfMonth - 1) / 7 + 1
        when {
            date.monthValue == 5 && date.dayOfWeek == java.time.DayOfWeek.SUNDAY && nth == 3 ->
                out.add("全国助残日")
            date.monthValue == 9 && date.dayOfWeek == java.time.DayOfWeek.SATURDAY && nth == 3 ->
                out.add("全民国防教育日")
            date.monthValue == 10 && date.dayOfWeek == java.time.DayOfWeek.MONDAY && nth == 1 ->
                out.add("世界住房日")
        }
        return out
    }

    /** 供 UI 使用的缓存，避免每次重组都重算 */
    private val cache = HashMap<String, DayInfo>()

    fun ofCached(date: LocalDate): DayInfo = cache.getOrPut(date.toString()) { of(date) }
}
