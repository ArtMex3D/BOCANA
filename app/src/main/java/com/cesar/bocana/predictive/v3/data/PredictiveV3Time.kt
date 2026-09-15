package com.cesar.bocana.predictive.v3.data

import com.cesar.bocana.predictive.v3.model.SeasonRegime
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.concurrent.TimeUnit

object PredictiveV3Time {

    data class WeekRef(val year: Int, val week: Int) {
        fun checkpointId(productId: String): String = "${productId}_${year}_${week}"
        val key: String get() = "$year-W$week"
    }

    data class DateRange(val startInclusive: Date, val endExclusive: Date)

    fun currentWeek(now: Date = Date()): WeekRef = weekRef(Calendar.getInstance().apply { time = now })

    fun sameWeekPreviousYear(now: Date = Date()): WeekRef {
        val cal = Calendar.getInstance().apply { time = now }
        cal.add(Calendar.YEAR, -1)
        return weekRef(cal)
    }

    /** Mismo mes calendario del año anterior, para mostrar referencia histórica mensual real. */
    fun sameMonthPreviousYearRange(now: Date = Date()): DateRange {
        val start = Calendar.getInstance().apply {
            time = now
            add(Calendar.YEAR, -1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return DateRange(start.time, end.time)
    }

    fun completeWeeks(now: Date = Date(), count: Int = 16): List<WeekRef> {
        val cal = Calendar.getInstance().apply { time = now }
        return (count downTo 1).map { weeksAgo ->
            val copy = cal.clone() as Calendar
            copy.add(Calendar.WEEK_OF_YEAR, -weeksAgo)
            weekRef(copy)
        }
    }

    fun seasonalWeeks(now: Date = Date()): List<WeekRef> {
        val current = Calendar.getInstance().apply { time = now }
        val year = current.get(Calendar.YEAR)
        val currentStart = mondayStart(current)

        val center = if (regime(now) == SeasonRegime.LENT) {
            val easterCurrent = easterSunday(year)
            val easterPrevious = easterSunday(year - 1)
            val offsetWeeks = weekDistance(mondayStart(easterCurrent), currentStart)
            mondayStart(easterPrevious).apply { add(Calendar.WEEK_OF_YEAR, offsetWeeks) }
        } else {
            val copy = currentStart.clone() as Calendar
            copy.add(Calendar.YEAR, -1)
            copy
        }

        return listOf(-1, 0, 1).map { offset ->
            val copy = center.clone() as Calendar
            copy.add(Calendar.WEEK_OF_YEAR, offset)
            weekRef(copy)
        }
    }

    /**
     * Ventanas operativas acordadas:
     * - lunes o martes: misma carga operativa hacia jueves -> 3 días de referencia
     * - jueves o viernes: misma carga operativa hacia lunes -> 4 días de referencia
     * Fuera de esos días usa los días reales hasta la siguiente ventana preferente.
     */
    fun targetWindowDays(now: Date = Date()): Double {
        val cal = Calendar.getInstance().apply { time = now }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY, Calendar.TUESDAY -> 3.0
            Calendar.THURSDAY, Calendar.FRIDAY -> 4.0
            Calendar.WEDNESDAY -> 1.0
            Calendar.SATURDAY -> 2.0
            Calendar.SUNDAY -> 1.0
            else -> 3.0
        }
    }

    fun elapsedDaysInCurrentWeek(now: Date = Date()): Double {
        val current = Calendar.getInstance().apply { time = now }
        val monday = mondayStart(current)
        val millis = (current.timeInMillis - monday.timeInMillis).coerceAtLeast(0L)
        return (millis.toDouble() / TimeUnit.DAYS.toMillis(1).toDouble()).coerceIn(0.0, 7.0)
    }

    fun regime(now: Date = Date()): SeasonRegime {
        val cal = Calendar.getInstance().apply { time = now }
        val year = cal.get(Calendar.YEAR)
        val easter = easterSunday(year)
        val ashWednesday = easter.clone() as Calendar
        ashWednesday.add(Calendar.DAY_OF_YEAR, -46)

        val day = dayOnly(cal)
        if (!day.before(dayOnly(ashWednesday)) && !day.after(dayOnly(easter))) {
            return SeasonRegime.LENT
        }
        if (cal.get(Calendar.MONTH) == Calendar.DECEMBER) {
            return SeasonRegime.DECEMBER
        }
        return SeasonRegime.NORMAL
    }

    private fun weekRef(calendar: Calendar): WeekRef = WeekRef(
        year = calendar.get(Calendar.YEAR),
        week = calendar.get(Calendar.WEEK_OF_YEAR)
    )

    private fun mondayStart(calendar: Calendar): Calendar {
        val result = calendar.clone() as Calendar
        result.set(Calendar.HOUR_OF_DAY, 0)
        result.set(Calendar.MINUTE, 0)
        result.set(Calendar.SECOND, 0)
        result.set(Calendar.MILLISECOND, 0)
        val day = result.get(Calendar.DAY_OF_WEEK)
        val daysSinceMonday = when (day) {
            Calendar.SUNDAY -> 6
            else -> day - Calendar.MONDAY
        }
        result.add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        return result
    }

    private fun weekDistance(from: Calendar, to: Calendar): Int {
        val days = TimeUnit.MILLISECONDS.toDays(to.timeInMillis - from.timeInMillis)
        return (days / 7L).toInt()
    }

    private fun dayOnly(calendar: Calendar): Date {
        val c = calendar.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.time
    }

    // Algoritmo gregoriano de Meeus/Jones/Butcher.
    private fun easterSunday(year: Int): Calendar {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return GregorianCalendar(year, month - 1, day)
    }
}
