package com.gildedrose

import com.gildedrose.domain.Item
import com.gildedrose.domain.Price
import com.gildedrose.domain.StockList
import com.gildedrose.foundation.AnalyticsEvent
import com.gildedrose.foundation.UncaughtExceptionEvent
import com.gildedrose.foundation.runIO
import com.gildedrose.foundation.succeedAfter
import com.gildedrose.persistence.StockListLoadingError
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Instant.parse as t
import java.time.LocalDate.parse as localdate

class PricedStockListLoaderTests {
    companion object {
        private val lastModified = t("2022-02-09T12:00:00Z")
        private val sameDayAsLastModified = t("2022-02-09T23:59:59Z")

        private val loadedStockList = StockList(
            lastModified = lastModified,
            items = listOf(
                item("banana", localdate("2022-02-08"), 42),
                item("kumquat", localdate("2022-02-10"), 101),
                item("undated", null, 50)
            )
        )
        private val expectedPricedStockList = loadedStockList.withItems(
            loadedStockList[0].withPrice(Price(666)),
            loadedStockList[1].withPrice(null),
            loadedStockList[2].withPrice(Price(999))
        )
    }

    private val stockValues = mutableMapOf<Instant, StockLoadingResult>(
        sameDayAsLastModified to Success(loadedStockList)
    )
    private val priceList = mutableMapOf<Item, (Item) -> Price?>(
        loadedStockList[0] to { Price(666) },
        loadedStockList[1] to { null },
        loadedStockList[2] to { Price(999) }
    )
    private val analyticsEvents = mutableListOf<AnalyticsEvent>()
    private val loader = PricedStockListLoader(
        { stockValues.getValue(it) },
        pricing = { item -> priceList[item]?.invoke(item) },
        analytics = { event -> analyticsEvents.add(event) }
    )

    @Test
    fun `loads and prices items`() {
        runIO {
            assertEquals(
                Success(expectedPricedStockList),
                loader.load(sameDayAsLastModified)
            )
            assertTrue(analyticsEvents.isEmpty())
        }
    }

    @Test
    fun `passes on failures to load stock`() {
        runIO {
            val loadingError = StockListLoadingError.IOError("deliberate")
            stockValues[sameDayAsLastModified] = Failure(loadingError)
            assertEquals(
                Failure(loadingError),
                loader.load(sameDayAsLastModified)
            )
            assertTrue(analyticsEvents.isEmpty())
        }
    }

    @Test
    fun `item price remembers pricing failures`() {
        runIO {
            val exception = Exception("deliberate")
            priceList[loadedStockList[2]] = { throw exception }
            assertEquals(
                Success(
                    expectedPricedStockList.copy(
                        items = expectedPricedStockList.items.toMutableList().apply {
                            set(2, get(2).copy(price = Failure(exception)))
                        }
                    )
                ),
                loader.load(sameDayAsLastModified)
            )
            with(analyticsEvents) {
                assertEquals(2, size) // one for the try and the retry
                assertTrue(all { it is UncaughtExceptionEvent })
            }
        }
    }

    @Test
    fun `retries pricing failures`() {
        runIO {
            val exception = Exception("deliberate")
            priceList[loadedStockList[2]] = succeedAfter(1, raiseError = { throw exception }) {
                Price(999)
            }
            assertEquals(
                Success(expectedPricedStockList),
                loader.load(sameDayAsLastModified)
            )
            with(analyticsEvents) {
                assertEquals(1, size)
                assertTrue(all { it is UncaughtExceptionEvent })
            }
        }
    }
}
