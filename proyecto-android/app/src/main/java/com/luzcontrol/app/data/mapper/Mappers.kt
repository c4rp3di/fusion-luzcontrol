package com.luzcontrol.app.data.mapper

import com.luzcontrol.app.data.local.entity.*
import com.luzcontrol.app.data.remote.dto.*
import com.luzcontrol.app.domain.model.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- Entidad -> dominio ----------

fun SupplyEntity.toDomain() = Supply(cups, distributorCode, distributor, address, municipality, province, pointType, alias, isActive)

fun ContractEntity.toDomain() = Contract(cups, contractedPowerP1, contractedPowerP2, tariffType, marketer, startDate, timeDiscrimination)

fun ConsumptionEntity.toDomain() = ConsumptionPoint(
    start = Instant.ofEpochSecond(datetime),
    kwh = consumptionKwh,
    surplusKwh = surplusKwh,
    real = obtainMethod.equals("Real", ignoreCase = true)
)

fun PriceEntity.toDomain() = PricePoint(Instant.ofEpochSecond(datetime), pricePvpcEurKwh, priceSpotEurKwh)

fun MaxPowerEntity.toDomain() = MaxPowerPoint(LocalDate.parse(date), time, maxPowerKw, TariffPeriod.fromCode(period))

fun SyncLogEntity.toDomain() = SyncLog(id, Instant.ofEpochSecond(timestamp), source, status == "OK", errorMessage)

// ---------- DTO -> entidad ----------

private val DATADIS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

fun parseDatadisDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), DATADIS_DATE)
} catch (e: Exception) {
    try { LocalDate.parse(text.trim()) } catch (e2: Exception) { null }
}

private fun parseMinutes(time: String): Int {
    val parts = time.trim().split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return h * 60 + m
}

fun DatadisSupplyDto.toEntity(existingAlias: String?, active: Boolean): SupplyEntity = SupplyEntity(
    cups = cups,
    distributorCode = distributorCode.orEmpty(),
    distributor = distributor.orEmpty(),
    address = address.orEmpty(),
    municipality = municipality.orEmpty(),
    province = province.orEmpty(),
    pointType = pointType ?: 5,
    alias = existingAlias,
    isActive = active
)

fun DatadisContractDto.toEntity(fallbackCups: String): ContractEntity {
    val powers = contractedPowerkW.orEmpty()
    return ContractEntity(
        cups = cups ?: fallbackCups,
        contractedPowerP1 = powers.getOrNull(0) ?: 0.0,
        contractedPowerP2 = powers.getOrNull(1) ?: powers.getOrNull(0) ?: 0.0,
        tariffType = accessFare ?: accesFare ?: codeFare.orEmpty(),
        marketer = marketer.orEmpty(),
        startDate = startDate,
        timeDiscrimination = timeDiscrimination
    )
}

/**
 * Convierte la curva de carga de Datadis. Día completo: inicio = medianoche + índice × paso (válido en
 * días de 23/25 h). Día incompleto: se usa el campo `time` (hora de fin de intervalo) para no desplazar lecturas.
 */
fun List<DatadisConsumptionDto>.toEntities(cups: String, zone: ZoneId, stepMinutes: Long = 60): List<ConsumptionEntity> {
    val out = ArrayList<ConsumptionEntity>(size)
    val groups = LinkedHashMap<LocalDate, MutableList<DatadisConsumptionDto>>()
    for (dto in this) {
        val d = parseDatadisDate(dto.date.orEmpty()) ?: continue
        groups.getOrPut(d) { ArrayList() }.add(dto)
    }
    for ((date, items) in groups) {
        val dayStart = date.atStartOfDay(zone)
        val sorted = items.sortedBy { parseMinutes(it.time.orEmpty()) }
        val dayMinutes = Duration.between(dayStart, date.plusDays(1).atStartOfDay(zone)).toMinutes()
        val fullDay = sorted.size.toLong() * stepMinutes == dayMinutes
        sorted.forEachIndexed { index, dto ->
            val start = if (fullDay) {
                // Día completo: por índice (correcto en días de 23/25 h)
                dayStart.plus(Duration.ofMinutes(stepMinutes * index)).toInstant()
            } else {
                // Día incompleto: la hora de Datadis es de FIN de intervalo ("10:00" = 09:00-10:00)
                val startMin = (parseMinutes(dto.time.orEmpty()) - stepMinutes).coerceAtLeast(0)
                dayStart.plus(Duration.ofMinutes(startMin)).toInstant()
            }
            out += ConsumptionEntity(
                cups = cups,
                datetime = start.epochSecond,
                consumptionKwh = dto.consumptionKWh ?: 0.0,
                obtainMethod = dto.obtainMethod ?: "Real",
                surplusKwh = dto.surplusEnergyKWh ?: 0.0
            )
        }
    }
    return out
}

fun periodFromDatadis(text: String?): Int {
    val t = text.orEmpty().trim().uppercase()
    return when {
        t.contains("PUNTA") -> 1
        t.contains("LLANO") -> 2
        t.contains("VALLE") -> 3
        // Periodos de potencia 2.0TD: 1 = punta/llano, 2 = valle
        t == "1" || t == "P1" -> 1
        else -> 3
    }
}

fun DatadisMaxPowerDto.toEntity(fallbackCups: String): MaxPowerEntity? {
    val d = parseDatadisDate(date.orEmpty()) ?: return null
    return MaxPowerEntity(
        cups = fallbackCups,
        date = d.toString(),
        time = time.orEmpty(),
        maxPowerKw = maxPower ?: 0.0,
        period = periodFromDatadis(period)
    )
}
