package com.example.doftest

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
        )

        assertTrue(result.cocMm > 0.0)
        assertTrue(result.hyperfocalMm > 0.0)
        assertTrue(result.nearLimitMm < 500.0)
        assertNotNull(result.farLimitMm)
        assertTrue(result.farLimitMm!! > 500.0)
    }
}
