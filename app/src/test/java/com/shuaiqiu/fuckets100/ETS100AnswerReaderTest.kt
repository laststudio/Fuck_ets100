package com.shuaiqiu.fuckets100

import org.junit.Assert.assertEquals
import org.junit.Test

class ETS100AnswerReaderTest {
    @Test
    fun localPaperDisplayNumberKeepsOldestAsOneWhileListIsNewestFirst() {
        val totalGroups = 4

        assertEquals(4, ETS100AnswerReader.localPaperDisplayNumber(0, totalGroups))
        assertEquals(3, ETS100AnswerReader.localPaperDisplayNumber(1, totalGroups))
        assertEquals(2, ETS100AnswerReader.localPaperDisplayNumber(2, totalGroups))
        assertEquals(1, ETS100AnswerReader.localPaperDisplayNumber(3, totalGroups))
    }

    @Test
    fun localPaperDisplayNumberFallsBackToSequentialWhenTotalIsUnknown() {
        assertEquals(1, ETS100AnswerReader.localPaperDisplayNumber(0, 0))
        assertEquals(2, ETS100AnswerReader.localPaperDisplayNumber(1, 0))
    }
}
