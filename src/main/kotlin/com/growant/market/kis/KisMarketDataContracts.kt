package com.growant.market.kis

import com.growant.market.provider.ProviderOhlcv
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val H0STCNT0 = "H0STCNT0"
private const val H0STCNT0_FIELD_COUNT = 46
private val DOMESTIC_TICKER = Regex("^[0-9]{6}$")
private val BUSINESS_DATE = Regex("^[0-9]{8}$")
private val TRADE_TIME = Regex("^[0-9]{6}$")

data class KisH0stcnt0Trade(
    val reportedTicker: String,
    val businessDate: LocalDate,
    val tradeTime: LocalTime,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val accumulatedVolume: BigDecimal,
    val marketOperationCode: String,
    val hourClassCode: String,
    val wireOrdinal: Int,
)

/** Plaintext H0STCNT0 data frame only. JSON control messages and encrypted frames use separate paths. */
object KisH0stcnt0FrameParser {
    fun parse(frame: String): List<KisH0stcnt0Trade> {
        require(frame.count { it == '|' } == 3) { "KIS H0STCNT0 frame must contain exactly three pipes" }
        val parts = frame.split('|', limit = 4)
        require(parts.size == 4) { "KIS H0STCNT0 frame must contain four pipe-delimited parts" }
        require(parts[0] != "1") { "KIS H0STCNT0 encrypted frame requires decryption before parsing" }
        require(parts[0] == "0") { "KIS H0STCNT0 frame encryption flag must be 0 or 1" }
        require(parts[1] == H0STCNT0) { "KIS frame TR ID must be H0STCNT0" }

        val rowCount = parts[2].toIntOrNull()
            ?: throw IllegalArgumentException("KIS H0STCNT0 row count must be a decimal integer")
        require(rowCount > 0) { "KIS H0STCNT0 row count must be positive" }

        val fields = parts[3].split('^')
        val expectedFieldCount = rowCount.toLong() * H0STCNT0_FIELD_COUNT
        require(fields.size.toLong() == expectedFieldCount) {
            "KIS H0STCNT0 payload must contain $expectedFieldCount fields"
        }

        return List(rowCount) { rowIndex ->
            val offset = rowIndex * H0STCNT0_FIELD_COUNT
            parseRow(fields.subList(offset, offset + H0STCNT0_FIELD_COUNT), rowIndex)
        }
    }

    private fun parseRow(fields: List<String>, rowIndex: Int): KisH0stcnt0Trade {
        val ticker = fields[0]
        require(ticker.isNotBlank()) { "KIS H0STCNT0 row[$rowIndex].MKSC_SHRN_ISCD must not be blank" }

        val price = parseDecimal(fields[2], "STCK_PRPR", rowIndex)
        require(price.signum() > 0) { "KIS H0STCNT0 row[$rowIndex].STCK_PRPR must be positive" }
        val quantity = parseDecimal(fields[12], "CNTG_VOL", rowIndex)
        require(quantity.signum() >= 0) { "KIS H0STCNT0 row[$rowIndex].CNTG_VOL must not be negative" }
        val accumulatedVolume = parseDecimal(fields[13], "ACML_VOL", rowIndex)
        require(accumulatedVolume.signum() >= 0) {
            "KIS H0STCNT0 row[$rowIndex].ACML_VOL must not be negative"
        }

        return KisH0stcnt0Trade(
            reportedTicker = ticker,
            businessDate = parseBusinessDate(fields[33], "H0STCNT0 row[$rowIndex].BSOP_DATE"),
            tradeTime = parseTradeTime(fields[1], "H0STCNT0 row[$rowIndex].STCK_CNTG_HOUR"),
            price = price,
            quantity = quantity,
            accumulatedVolume = accumulatedVolume,
            marketOperationCode = requiredWireText(fields[34], "NEW_MKOP_CLS_CODE", rowIndex),
            hourClassCode = requiredWireText(fields[43], "HOUR_CLS_CODE", rowIndex),
            wireOrdinal = rowIndex,
        )
    }

    private fun parseDecimal(value: String, field: String, rowIndex: Int): BigDecimal =
        value.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("KIS H0STCNT0 row[$rowIndex].$field must be numeric")

    private fun requiredWireText(value: String, field: String, rowIndex: Int): String = value.also {
        require(it.isNotBlank()) { "KIS H0STCNT0 row[$rowIndex].$field must not be blank" }
    }
}

enum class KisMinuteCandleEndpoint(val trId: String) {
    TODAY("FHKST03010200"),
    DAILY("FHKST03010230"),
}

enum class KisMarketDivision(val fidCode: String) {
    KRX("J"),
    NXT("NX"),
    INTEGRATED("UN"),
}

sealed interface KisMinuteCandleRequest {
    val ticker: String
    val marketDivision: KisMarketDivision
    val endpoint: KisMinuteCandleEndpoint

    fun queryParameters(): Map<String, String>
}

data class KisTodayMinuteCandleRequest(
    override val ticker: String,
    override val marketDivision: KisMarketDivision,
    val inputTime: LocalTime,
) : KisMinuteCandleRequest {
    override val endpoint = KisMinuteCandleEndpoint.TODAY

    init {
        require(DOMESTIC_TICKER.matches(ticker)) { "ticker must be a 6-digit domestic ticker" }
        require(inputTime.nano == 0) { "inputTime must have whole-second precision" }
    }

    override fun queryParameters(): Map<String, String> = linkedMapOf(
        "FID_COND_MRKT_DIV_CODE" to marketDivision.fidCode,
        "FID_INPUT_ISCD" to ticker,
        "FID_INPUT_HOUR_1" to inputTime.format(DateTimeFormatter.ofPattern("HHmmss")),
        "FID_PW_DATA_INCU_YN" to "Y",
        "FID_ETC_CLS_CODE" to "",
    )
}

data class KisDailyMinuteCandleRequest(
    override val ticker: String,
    override val marketDivision: KisMarketDivision,
    val inputDate: LocalDate,
    val inputTime: LocalTime,
) : KisMinuteCandleRequest {
    override val endpoint = KisMinuteCandleEndpoint.DAILY

    init {
        require(DOMESTIC_TICKER.matches(ticker)) { "ticker must be a 6-digit domestic ticker" }
        require(inputTime.nano == 0) { "inputTime must have whole-second precision" }
    }

    override fun queryParameters(): Map<String, String> = linkedMapOf(
        "FID_COND_MRKT_DIV_CODE" to marketDivision.fidCode,
        "FID_INPUT_ISCD" to ticker,
        "FID_INPUT_HOUR_1" to inputTime.format(DateTimeFormatter.ofPattern("HHmmss")),
        "FID_INPUT_DATE_1" to inputDate.format(DateTimeFormatter.BASIC_ISO_DATE),
        "FID_PW_DATA_INCU_YN" to "Y",
        "FID_FAKE_TICK_INCU_YN" to "N",
    )
}

data class KisMinuteCandleRow(
    val businessDate: LocalDate,
    val tradeTime: LocalTime,
    val values: ProviderOhlcv,
    /** KIS 명세상 분 거래대금이 아니라 당일 누적 거래대금이다. */
    val accumulatedTradingValue: BigDecimal,
    val wireOrdinal: Int,
)

data class KisMinuteCandleResponse(
    val request: KisMinuteCandleRequest,
    val candles: List<KisMinuteCandleRow>,
)

class KisMinuteCandleResponseParser(
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    fun parse(
        request: KisMinuteCandleRequest,
        responseBody: String,
    ): KisMinuteCandleResponse {
        require(responseBody.isNotBlank()) { "KIS minute candle response must not be blank" }

        val root = try {
            objectMapper.readTree(responseBody)
        } catch (exception: JacksonException) {
            throw IllegalArgumentException("KIS minute candle response is not valid JSON", exception)
        }
        require(root.isObject) { "KIS minute candle response root must be an object" }

        val resultCode = requiredText(root, "rt_cd", "response")
        if (resultCode != "0") {
            val messageCode = root.get("msg_cd")
                ?.takeIf(JsonNode::isString)
                ?.asString()
                ?: "unknown"
            throw IllegalArgumentException("KIS minute candle response failed: msg_cd=$messageCode")
        }

        val candleNodes = root.path("output2")
        require(candleNodes.isArray) { "KIS minute candle response output2 must be an array" }
        val candles = candleNodes.mapIndexed { index, node -> parseCandle(node, index) }

        return KisMinuteCandleResponse(request = request, candles = candles)
    }

    private fun parseCandle(node: JsonNode, index: Int): KisMinuteCandleRow {
        require(node.isObject) { "KIS minute candle[$index] must be an object" }

        return KisMinuteCandleRow(
            businessDate = parseBusinessDate(
                requiredText(node, "stck_bsop_date", "minute candle[$index]"),
                "minute candle[$index].stck_bsop_date",
            ),
            tradeTime = parseTradeTime(
                requiredText(node, "stck_cntg_hour", "minute candle[$index]"),
                "minute candle[$index].stck_cntg_hour",
            ),
            values = ProviderOhlcv(
                openPrice = requiredDecimal(node, "stck_oprc", index),
                highPrice = requiredDecimal(node, "stck_hgpr", index),
                lowPrice = requiredDecimal(node, "stck_lwpr", index),
                closePrice = requiredDecimal(node, "stck_prpr", index),
                volume = requiredDecimal(node, "cntg_vol", index),
            ),
            accumulatedTradingValue = requiredDecimal(node, "acml_tr_pbmn", index),
            wireOrdinal = index,
        )
    }

    private fun requiredDecimal(node: JsonNode, field: String, index: Int): BigDecimal =
        requiredText(node, field, "minute candle[$index]").toBigDecimalOrNull()
            ?: throw IllegalArgumentException("KIS minute candle[$index].$field must be numeric")

    private fun requiredText(node: JsonNode, field: String, context: String): String {
        val value = node.get(field)
        require(value != null && value.isString) { "KIS $context.$field must be a string" }
        return value.asString().also {
            require(it.isNotBlank()) { "KIS $context.$field must not be blank" }
        }
    }
}

private fun parseBusinessDate(value: String, field: String): LocalDate {
    require(BUSINESS_DATE.matches(value)) { "KIS $field must use yyyyMMdd" }
    return try {
        LocalDate.of(
            value.substring(0, 4).toInt(),
            value.substring(4, 6).toInt(),
            value.substring(6, 8).toInt(),
        )
    } catch (exception: DateTimeException) {
        throw IllegalArgumentException("KIS $field must be a valid date", exception)
    }
}

private fun parseTradeTime(value: String, field: String): LocalTime {
    require(TRADE_TIME.matches(value)) { "KIS $field must use HHmmss" }
    return try {
        LocalTime.of(
            value.substring(0, 2).toInt(),
            value.substring(2, 4).toInt(),
            value.substring(4, 6).toInt(),
        )
    } catch (exception: DateTimeException) {
        throw IllegalArgumentException("KIS $field must be a valid time", exception)
    }
}
