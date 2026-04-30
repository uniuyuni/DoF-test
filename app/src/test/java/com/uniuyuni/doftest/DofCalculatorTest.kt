package com.uniuyuni.doftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DofCalculatorTest {

    @Test
    fun calculate_returnsFiniteRangeForCloseSubject() {
        val result = DofCalculator.calculate(
            sensorFormat = SensorFormat("フルフレーム", 36.0, 24.0),
            megapixels = 24.0,
            focalLengthMm = 50.0,
            aperture = 4.0,
            subjectDistanceM = 0.5,
            cocMode = CocMode.PRINT,
            considerAiryDisk = false,
        )

        assertTrue(result.cocMm > 0.0)
        assertTrue(result.hyperfocalMm > 0.0)
        assertTrue(result.nearLimitMm < 500.0)
        assertNotNull(result.farLimitMm)
        assertTrue(result.farLimitMm!! > 500.0)
    }

    @Test
    fun calculate_matchesNikon58mmDepthOfFieldTable() {
        val result = DofCalculator.calculate(
            sensorFormat = SensorFormat("フルフレーム", 36.0, 24.0),
            megapixels = 24.0,
            focalLengthMm = 58.0,
            aperture = 1.4,
            subjectDistanceM = 1.0,
            cocMode = CocMode.PRINT,
            considerAiryDisk = false,
        )

        assertEquals(0.988, result.nearLimitMm / 1000.0, 0.001)
        assertEquals(1.012, result.farLimitMm!! / 1000.0, 0.001)
    }

    @Test
    fun calculate_matchesZeiss29mmDepthOfFieldTable() {
        val result = DofCalculator.calculate(
            sensorFormat = SensorFormat("フルフレーム", 36.0, 24.0),
            megapixels = 24.0,
            focalLengthMm = 29.0,
            aperture = 1.52,
            subjectDistanceM = 1.0,
            cocMode = CocMode.PRINT,
            considerAiryDisk = false,
        )

        assertEquals(0.950, result.nearLimitMm / 1000.0, 0.001)
        assertEquals(1.055, result.farLimitMm!! / 1000.0, 0.001)
    }

    @Test
    fun calculate_usesFixedDistanceScaleCocForPrintModeAcrossSensors() {
        val fullFrame = DofCalculator.calculate(
            sensorFormat = SensorFormat("フルフレーム", 36.0, 24.0),
            megapixels = 24.0,
            focalLengthMm = 50.0,
            aperture = 4.0,
            subjectDistanceM = 3.0,
            cocMode = CocMode.PRINT,
            considerAiryDisk = false,
        )
        val apsc = DofCalculator.calculate(
            sensorFormat = SensorFormat("APS-C", 23.6, 15.7),
            megapixels = 24.0,
            focalLengthMm = 50.0,
            aperture = 4.0,
            subjectDistanceM = 3.0,
            cocMode = CocMode.PRINT,
            considerAiryDisk = false,
        )

        assertEquals(0.03, fullFrame.cocMm, 1e-9)
        assertEquals(0.03, apsc.cocMm, 1e-9)
    }

    @Test
    fun calculate_keepsPixelModeSensorDependent() {
        val fullFrame = DofCalculator.calculate(
            sensorFormat = SensorFormat("フルフレーム", 36.0, 24.0),
            megapixels = 24.0,
            focalLengthMm = 50.0,
            aperture = 4.0,
            subjectDistanceM = 3.0,
            cocMode = CocMode.PIXEL,
            considerAiryDisk = false,
        )
        val apsc = DofCalculator.calculate(
            sensorFormat = SensorFormat("APS-C", 23.6, 15.7),
            megapixels = 24.0,
            focalLengthMm = 50.0,
            aperture = 4.0,
            subjectDistanceM = 3.0,
            cocMode = CocMode.PIXEL,
            considerAiryDisk = false,
        )

        assertNotEquals(fullFrame.cocMm, apsc.cocMm, 1e-9)
    }

    @Test
    fun calculate_prefersAiryDiskWhenLargerThanDistanceScaleCoc() {
        val result = DofCalculator.calculate(
            sensorFormat = SensorFormat("APS-C", 23.6, 15.7),
            megapixels = 24.0,
            focalLengthMm = 50.0,
            aperture = 32.0,
            subjectDistanceM = 3.0,
            cocMode = CocMode.PRINT,
            considerAiryDisk = true,
        )

        val airyDiskDiameterMm = 2.44 * 0.00055 * 32.0
        assertTrue(airyDiskDiameterMm > 0.03)
        assertEquals(airyDiskDiameterMm, result.cocMm, 1e-9)
    }
}
