package com.luzcontrol.app.ui.consumption

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.Color
import com.luzcontrol.app.domain.model.*
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.usecase.*
import com.luzcontrol.app.ui.components.BarEntry
import com.luzcontrol.app.ui.components.Fmt
import com.luzcontrol.app.ui.components.UiState
import com.luzcontrol.app.ui.theme.EnergyColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class RangePreset(val label: String) {
    DAY("Día"), WEEK("Semana"), MONTH("Mes"), YEAR("Año"), CUSTOM("Personalizado")
}

/**
 * Periodo mostrado: día, semana (lunes–domingo), mes, año o rango personalizado.
 * [anchor] es cualquier fecha dentro del periodo; las flechas lo desplazan una unidad.
 */
data class RangeSelection(
    val preset: RangePreset = RangePreset.MONTH,
    val anchor: LocalDate = LocalDate.now(TariffCalendar.ZONE),
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null
) {
    fun resolve(): Pair<LocalDate, LocalDate> = when (preset) {
        RangePreset.DAY -> anchor to anchor
        RangePreset.WEEK -> {
            val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            start to start.plusDays(6)
        }
        RangePreset.MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
        RangePreset.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
        RangePreset.CUSTOM -> {
            val a = customFrom ?: anchor.minusDays(29)
            val b = customTo ?: anchor
            if (a.isAfter(b)) b to a else a to b
        }
    }

    /** Desplaza el periodo [delta] unidades (−1 = anterior, +1 = siguiente). */
    fun shifted(delta: Int): RangeSelection = when (preset) {
        RangePreset.DAY -> copy(anchor = anchor.plusDays(delta.toLong()))
        RangePreset.WEEK -> copy(anchor = anchor.plusWeeks(delta.toLong()))
        RangePreset.MONTH -> copy(anchor = anchor.plusMonths(delta.toLong()))
        RangePreset.YEAR -> copy(anchor = anchor.plusYears(delta.toLong()))
        RangePreset.CUSTOM -> {
            val (a, b) = resolve()
            val span = ChronoUnit.DAYS.between(a, b) + 1
            copy(customFrom = a.plusDays(span * delta), customTo = b.plusDays(span * delta))
        }
    }

    fun canGoNext(today: LocalDate): Boolean = !shifted(1).resolve().first.isAfter(today)

    fun title(): String {
        val (a, b) = resolve()
        return when (preset) {
            RangePreset.DAY -> a.format(DateTimeFormatter.ofPattern("EEE dd/MM/yyyy", Fmt.es))
            RangePreset.WEEK -> a.format(DateTimeFormatter.ofPattern("dd/MM")) + " – " + b.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            RangePreset.MONTH -> a.format(DateTimeFormatter.ofPattern("LLLL yyyy", Fmt.es))
            RangePreset.YEAR -> a.format(DateTimeFormatter.ofPattern("yyyy"))
            RangePreset.CUSTOM -> Fmt.date(a) + " – " + Fmt.date(b)
        }
    }
}

enum class Granularity(val label: String) { QUARTER("por cuartos de hora"), HOURLY("por horas"), DAILY("por días"), MONTHLY("por meses") }

data class ConsumptionUi(
    val rangeLabel: String,
    val granularity: Granularity,
    val bars: List<BarEntry>,
    val priceLine: List<Double?>,
    val totalKwh: Double,
    val totalCostEur: Double?,
    val avgPrice: Double?,
    val anomalies: List<Anomaly>,
    val power: PowerAnalysis,
    val standby: StandbyReport?,
    val surplusKwh: Double
)

@HiltViewModel
class ConsumptionViewModel @Inject constructor(
    private val observeActiveCups: ObserveActiveCupsUseCase,
    private val energy: EnergyRepository,
    private val calculateCost: CalculateCostUseCase,
    private val detectAnomalies: DetectAnomaliesUseCase,
    private val analyzePower: AnalyzePowerUseCase,
    private val estimateStandby: EstimateStandbyUseCase
) : ViewModel() {

    private val zone = TariffCalendar.ZONE
    private val _range = MutableStateFlow(RangeSelection())
    val range: StateFlow<RangeSelection> = _range

    fun selectPreset(p: RangePreset) {
        val cur = _range.value
        val today = LocalDate.now(zone)
        var anchor = if (cur.preset == RangePreset.CUSTOM) (cur.customTo ?: today) else cur.anchor
        // Los datos de la distribuidora llegan con retraso: la vista diaria arranca en ayer
        if (p == RangePreset.DAY && !anchor.isBefore(today)) anchor = today.minusDays(1)
        _range.value = cur.copy(preset = p, anchor = anchor)
    }

    fun setCustom(from: LocalDate, to: LocalDate) {
        _range.value = _range.value.copy(preset = RangePreset.CUSTOM, customFrom = from, customTo = to, anchor = to)
    }

    fun shift(delta: Int) {
        val next = _range.value.shifted(delta)
        if (delta > 0 && !_range.value.canGoNext(LocalDate.now(zone))) return
        _range.value = next
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<ConsumptionUi>> =
        combine(observeActiveCups(), _range) { cups, r -> cups to r }
            .flatMapLatest { (cups, r) ->
                if (cups == null) flowOf<UiState<ConsumptionUi>>(
                    UiState.Empty("Aún no hay datos de consumo. Configura tus credenciales de Datadis en Ajustes.")
                )
                else build(cups, r)
            }
            .catch { emit(UiState.Error("No se pudieron leer los datos guardados.")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private fun build(cups: String, r: RangeSelection): Flow<UiState<ConsumptionUi>> {
        val today = LocalDate.now(zone)
        val (fromDate, toDate) = r.resolve()
        val from = fromDate.atStartOfDay(zone).toInstant()
        val to = toDate.plusDays(1).atStartOfDay(zone).toInstant()
        val extFrom = from.minus(28, ChronoUnit.DAYS)
        val priceFrom = from.minus(30, ChronoUnit.DAYS)
        return combine(
            energy.observeConsumption(cups, extFrom, to),
            energy.observePrices(priceFrom, to),
            energy.observeContract(cups),
            energy.observeMaxPower(cups, today.minusMonths(12).withDayOfMonth(1), today)
        ) { extConsumption, prices, contract, maxPower ->
            val inRange = extConsumption.filter { it.start >= from && it.start < to }
            if (inRange.isEmpty()) {
                UiState.Empty("No hay lecturas de consumo en el periodo seleccionado.")
            } else {
                val rangePrices = prices.filter { it.start >= from && it.start < to }
                val cost = calculateCost(inRange, rangePrices)
                val (bars, line, gran) = aggregate(inRange, rangePrices, fromDate, toDate)
                val anomalies = detectAnomalies(extConsumption, from, to)
                val standbyEnd = minOf(to, Instant.now())
                val standby = estimateStandby(
                    extConsumption.filter { it.start >= standbyEnd.minus(30, ChronoUnit.DAYS) && it.start < standbyEnd },
                    rangePrices
                )
                val ui = ConsumptionUi(
                    rangeLabel = r.title(),
                    granularity = gran,
                    bars = bars,
                    priceLine = line,
                    totalKwh = inRange.sumOf { it.kwh },
                    totalCostEur = if (cost.pricedKwh > 0) cost.totalEur else null,
                    avgPrice = cost.avgPriceEurKwh,
                    anomalies = anomalies,
                    power = analyzePower(maxPower, contract),
                    standby = standby,
                    surplusKwh = inRange.sumOf { it.surplusKwh }
                )
                UiState.Success(ui)
            }
        }
    }

    private data class Aggregated(val bars: List<BarEntry>, val line: List<Double?>, val gran: Granularity)

    private fun aggregate(
        points: List<ConsumptionPoint>,
        prices: List<PricePoint>,
        fromDate: LocalDate,
        toDate: LocalDate
    ): Aggregated {
        val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        val priceByHour = prices.associateBy { it.start.truncatedTo(ChronoUnit.HOURS) }
        return when {
            days == 1L && points.any { it.start.atZone(zone).minute != 0 } -> {
                // Datos de cuarto de hora (ajuste opcional): una barra por cuarto
                val fmt = DateTimeFormatter.ofPattern("HH:mm")
                val quarters = points.sortedBy { it.start }
                Aggregated(
                    bars = quarters.map { q ->
                        val period = TariffCalendar.periodAt(q.start)
                        BarEntry(q.start.atZone(zone).format(fmt), q.kwh, periodColor(period), period.label)
                    },
                    line = quarters.map { priceByHour[it.start.truncatedTo(ChronoUnit.HOURS)]?.pvpcEurKwh },
                    gran = Granularity.QUARTER
                )
            }
            days <= 3 -> {
                val hourly = points.groupBy { it.start.truncatedTo(ChronoUnit.HOURS) }
                    .map { (h, list) -> h to list.sumOf { it.kwh } }
                    .sortedBy { it.first }
                val fmt = DateTimeFormatter.ofPattern("dd/MM HH'h'")
                Aggregated(
                    bars = hourly.map { (h, kwh) ->
                        val period = TariffCalendar.periodAt(h)
                        BarEntry(h.atZone(zone).format(fmt), kwh, periodColor(period), period.label)
                    },
                    line = hourly.map { (h, _) -> priceByHour[h]?.pvpcEurKwh },
                    gran = Granularity.HOURLY
                )
            }
            days <= 92 -> {
                val daily = points.groupBy { it.start.atZone(zone).toLocalDate() }
                    .map { (d, list) -> d to list.sumOf { it.kwh } }
                    .sortedBy { it.first }
                val pricesByDay = prices.groupBy { it.start.atZone(zone).toLocalDate() }
                    .mapValues { (_, l) -> l.map { it.pvpcEurKwh }.average() }
                Aggregated(
                    bars = daily.map { (d, kwh) -> BarEntry(Fmt.shortDate(d), kwh) },
                    line = daily.map { (d, _) -> pricesByDay[d] },
                    gran = Granularity.DAILY
                )
            }
            else -> {
                val monthly = points.groupBy { YearMonth.from(it.start.atZone(zone)) }
                    .map { (m, list) -> m to list.sumOf { it.kwh } }
                    .sortedBy { it.first }
                val pricesByMonth = prices.groupBy { YearMonth.from(it.start.atZone(zone)) }
                    .mapValues { (_, l) -> l.map { it.pvpcEurKwh }.average() }
                val fmt = DateTimeFormatter.ofPattern("MM/yy")
                Aggregated(
                    bars = monthly.map { (m, kwh) -> BarEntry(m.format(fmt), kwh) },
                    line = monthly.map { (m, _) -> pricesByMonth[m] },
                    gran = Granularity.MONTHLY
                )
            }
        }
    }

    private fun periodColor(p: TariffPeriod): Color = when (p) {
        TariffPeriod.P1 -> EnergyColors.Punta
        TariffPeriod.P2 -> EnergyColors.Llano
        TariffPeriod.P3 -> EnergyColors.Valle
    }
}
