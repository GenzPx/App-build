package com.monitorcheck.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternScannerLogicTest {

    private fun levelFor(score: Int): RiskLevel = when {
        score <= 0 -> RiskLevel.SAFE
        score < 15 -> RiskLevel.LOW
        score < 35 -> RiskLevel.SUSPICIOUS
        else -> RiskLevel.HIGH
    }

    @Test
    fun `zero score is safe`() {
        assertEquals(RiskLevel.SAFE, levelFor(0))
        assertEquals(RiskLevel.SAFE, levelFor(-5))
    }

    @Test
    fun `low band covers one to fourteen`() {
        assertEquals(RiskLevel.LOW, levelFor(1))
        assertEquals(RiskLevel.LOW, levelFor(14))
    }

    @Test
    fun `suspicious band covers fifteen to thirty four`() {
        assertEquals(RiskLevel.SUSPICIOUS, levelFor(15))
        assertEquals(RiskLevel.SUSPICIOUS, levelFor(34))
    }

    @Test
    fun `high risk starts at thirty five`() {
        assertEquals(RiskLevel.HIGH, levelFor(35))
        assertEquals(RiskLevel.HIGH, levelFor(500))
    }

    @Test
    fun `thresholds are monotonic`() {
        var previousRank = -1
        listOf(0, 1, 14, 15, 34, 35, 100).forEach { score ->
            val rank = levelFor(score).rank
            assertTrue("rank must never decrease as score rises", rank >= previousRank)
            previousRank = rank
        }
    }

    @Test
    fun `findings sum to the reported score`() {
        val findings = listOf(
            Finding("a", "detail", 10),
            Finding("b", "detail", 5),
            Finding("c", "informational only", 0)
        )
        assertEquals(15, findings.sumOf { it.weight })
        assertEquals(RiskLevel.SUSPICIOUS, levelFor(findings.sumOf { it.weight }))
    }

    @Test
    fun `informational findings do not change the level`() {
        val informational = listOf(Finding("note", "no impact", 0))
        assertEquals(RiskLevel.SAFE, levelFor(informational.sumOf { it.weight }))
    }

    @Test
    fun `every risk level has a distinct label`() {
        val labels = RiskLevel.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }
}
