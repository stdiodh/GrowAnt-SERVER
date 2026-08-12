package com.growant.market.port

fun interface InstrumentCatalog {
    fun contains(ticker: String): Boolean
}
