package com.monitorcheck.monitor
import org.junit.Assert.assertTrue
import org.junit.Test
class HistoryAnalyzerTest{@Test fun repeatedHighRamProducesFinding(){val p=(0 until 4).map{HistoryPoint(it.toLong(),25.0,85.0,70.0,32.0,36.0,0.0,0.0)};assertTrue(HistoryAnalyzer.findings(p).any{it.title=="RAM pressure observed"})}@Test fun emptyHistoryHasNoFindings(){assertTrue(HistoryAnalyzer.findings(emptyList()).isEmpty())}}
