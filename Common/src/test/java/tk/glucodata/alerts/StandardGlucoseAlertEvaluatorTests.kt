package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.util.GlucoseFormatter

class StandardGlucoseAlertEvaluatorTests {

    private val activeConfig: (AlertConfig) -> Boolean = { true }

    @Test
    fun lowAndHighConditionsOnlyActivateInTheirOwnDirection() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = 9.0f)
        )

        val low = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 3.9f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )
        val high = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 9.1f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(AlertType.LOW in low)
        assertFalse(AlertType.HIGH in low)
        assertTrue(AlertType.HIGH in high)
        assertFalse(AlertType.LOW in high)
    }

    @Test
    fun exactThresholdIsNotAnActiveCondition() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = 9.0f)
        )

        val lowBoundary = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW),
            isMmol = true,
            isConfigActive = activeConfig
        )
        val highBoundary = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 9.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(lowBoundary.isEmpty())
        assertTrue(highBoundary.isEmpty())
    }

    private fun mmol(mgdl: Int): Float = GlucoseFormatter.displayFromMgDl(mgdl.toFloat(), true)

    private fun isActive(
        type: AlertType,
        threshold: Float,
        value: Float,
        isMmol: Boolean,
        wasActive: Boolean = false,
        rearmMargin: Float? = null
    ): Boolean = type in StandardGlucoseAlertEvaluator.resolveActive(
        glucoseValue = value,
        rate = 0f,
        configs = mapOf(type to AlertConfig(type, enabled = true, threshold = threshold, rearmMargin = rearmMargin)),
        alertTypes = listOf(type),
        isMmol = isMmol,
        isConfigActive = activeConfig,
        wasConditionActive = { wasActive }
    )

    @Test
    fun aReadingShownAsTheLowThresholdDoesNotAlarm() {
        for (type in listOf(AlertType.LOW, AlertType.VERY_LOW)) {
            // 72 mg/dL is 3.996 mmol/L: every surface shows 4.0.
            assertFalse(type.name, isActive(type, 4.0f, mmol(72), isMmol = true))
            // 71 mg/dL is 3.94, shown 3.9.
            assertTrue(type.name, isActive(type, 4.0f, mmol(71), isMmol = true))
            assertFalse(type.name, isActive(type, 70f, 70f, isMmol = false))
            assertTrue(type.name, isActive(type, 70f, 69f, isMmol = false))
            // A calibrated 69.6 is shown 70.
            assertFalse(type.name, isActive(type, 70f, 69.6f, isMmol = false))
        }
    }

    @Test
    fun aReadingShownAsTheHighThresholdDoesNotAlarm() {
        for (type in listOf(AlertType.HIGH, AlertType.VERY_HIGH)) {
            // 181 mg/dL is 10.05 mmol/L, shown 10.0; 182 is 10.10, shown 10.1.
            assertFalse(type.name, isActive(type, 10.0f, mmol(181), isMmol = true))
            assertTrue(type.name, isActive(type, 10.0f, mmol(182), isMmol = true))
            assertFalse(type.name, isActive(type, 180f, 180f, isMmol = false))
            assertTrue(type.name, isActive(type, 180f, 181f, isMmol = false))
            assertFalse(type.name, isActive(type, 180f, 180.4f, isMmol = false))
        }
    }

    @Test
    fun anEpisodeEndsWhenTheReadingIsShownPastTheRearmMargin() {
        // Low 4.0 + 0.5: 80 mg/dL (4.44, shown 4.4) is still low, 81 (4.4955, shown 4.5) is over.
        assertTrue(isActive(AlertType.LOW, 4.0f, mmol(80), isMmol = true, wasActive = true, rearmMargin = 0.5f))
        assertFalse(isActive(AlertType.LOW, 4.0f, mmol(81), isMmol = true, wasActive = true, rearmMargin = 0.5f))
        // High 10.0 - 0.5: 173 mg/dL (9.60, shown 9.6) is still high, 172 (9.546, shown 9.5) is over.
        assertTrue(isActive(AlertType.HIGH, 10.0f, mmol(173), isMmol = true, wasActive = true, rearmMargin = 0.5f))
        assertFalse(isActive(AlertType.HIGH, 10.0f, mmol(172), isMmol = true, wasActive = true, rearmMargin = 0.5f))
    }

    @Test
    fun forecastUsesProjectedDisplayValueWithoutReplacingPrimaryValue() {
        val configs = mapOf(
            AlertType.PRE_HIGH to AlertConfig(
                type = AlertType.PRE_HIGH,
                enabled = true,
                threshold = 9.0f,
                forecastMinutes = 20
            )
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 8.4f,
            rate = 2.0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        val condition = active.getValue(AlertType.PRE_HIGH)
        assertEquals(8.4f, condition.glucoseValue, 0.001f)
        assertEquals(10.62f, condition.evaluatedValue, 0.02f)
    }

    @Test
    fun forecastWithMissingRateDoesNotFire() {
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 4.0f,
                forecastMinutes = 20
            )
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.2f,
            rate = Float.NaN,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(active.isEmpty())
    }

    @Test
    fun forecastDoesNotEnterAfterCurrentValueAlreadyCrossedForecastThreshold() {
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 3.9f,
                forecastMinutes = 20
            )
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 3.85f,
            rate = -1.0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(active.isEmpty())
    }

    @Test
    fun activeForecastSurvivesBoundaryJitterUntilMeaningfulRecovery() {
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 3.9f,
                forecastMinutes = 20
            )
        )

        val jitter = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.0f,
            rate = 0.05f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig,
            wasConditionActive = { true }
        )
        // Meaningful recovery = the MEASURED value clears threshold + margin
        // (3.9 + 1.1 default). 4.2 used to end the episode under the old
        // 0.2 mmol margin; the rework keeps it alive on purpose.
        val stillUndecided = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.2f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig,
            wasConditionActive = { true }
        )
        val recovered = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 5.1f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig,
            wasConditionActive = { true }
        )

        assertTrue(AlertType.PRE_LOW in jitter)
        assertTrue(AlertType.PRE_LOW in stillUndecided)
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun forecastJitterDoesNotCreateASecondEpisodeEntry() {
        val episodes = AlertEpisodeState<AlertType>()
        val config = AlertConfig(
            type = AlertType.PRE_LOW,
            enabled = true,
            threshold = 3.9f,
            forecastMinutes = 20
        )

        fun evaluate(glucose: Float, rate: Float): AlertEpisodeTransition<AlertType> {
            val active = StandardGlucoseAlertEvaluator.resolveActive(
                glucoseValue = glucose,
                rate = rate,
                configs = mapOf(AlertType.PRE_LOW to config),
                alertTypes = listOf(AlertType.PRE_LOW),
                isMmol = true,
                isConfigActive = activeConfig,
                wasConditionActive = episodes::isActive
            )
            return episodes.update(active.keys)
        }

        assertTrue(evaluate(4.2f, -1.0f).shouldTryFire(AlertType.PRE_LOW))
        assertFalse(evaluate(4.0f, 0.05f).shouldTryFire(AlertType.PRE_LOW))
        // Recovery past threshold + margin (3.9 + 1.1) ends the episode ...
        assertTrue(AlertType.PRE_LOW in evaluate(5.1f, 0f).cleared)
        // ... and only then is a fresh entry a fresh episode.
        assertTrue(evaluate(4.2f, -1.0f).shouldTryFire(AlertType.PRE_LOW))
    }

    @Test
    fun highForecastUsesSymmetricSafeSideAndRecoveryRules() {
        assertFalse(
            ForecastThresholdPolicy.isActive(
                type = AlertType.PRE_HIGH,
                currentValue = 9.1f,
                projectedValue = 10.0f,
                threshold = 9.0f,
                wasActive = false,
                isMmol = true
            )
        )
        assertTrue(
            ForecastThresholdPolicy.isActive(
                type = AlertType.PRE_HIGH,
                currentValue = 8.8f,
                projectedValue = 9.3f,
                threshold = 9.0f,
                wasActive = false,
                isMmol = true
            )
        )
        // 8.7 has not recovered past threshold - margin (9.0 - 1.1 = 7.9): the
        // prediction is undecided and the episode survives.
        assertTrue(
            ForecastThresholdPolicy.isActive(
                type = AlertType.PRE_HIGH,
                currentValue = 8.7f,
                projectedValue = 8.7f,
                threshold = 9.0f,
                wasActive = true,
                isMmol = true
            )
        )
        assertFalse(
            ForecastThresholdPolicy.isActive(
                type = AlertType.PRE_HIGH,
                currentValue = 7.8f,
                projectedValue = 7.8f,
                threshold = 9.0f,
                wasActive = true,
                isMmol = true
            )
        )
    }

    @Test
    fun disabledInactiveAndInvalidThresholdConfigsAreIgnored() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = false, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = Float.NaN),
            AlertType.VERY_HIGH to AlertConfig(AlertType.VERY_HIGH, enabled = true, threshold = 12.0f)
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 13.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH, AlertType.VERY_HIGH),
            isMmol = true,
            isConfigActive = { it.type != AlertType.VERY_HIGH }
        )

        assertTrue(active.isEmpty())
    }

    @Test
    fun conditionBeyondThresholdEntersWhenTimeWindowOpens() {
        val episodes = AlertEpisodeState<AlertType>()
        val config = AlertConfig(AlertType.HIGH, enabled = true, threshold = 6.4f)

        val inactive = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 6.5f,
            rate = 0f,
            configs = mapOf(AlertType.HIGH to config),
            alertTypes = listOf(AlertType.HIGH),
            isMmol = true,
            isConfigActive = { false }
        )
        episodes.update(inactive.keys)

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 6.5f,
            rate = 0f,
            configs = mapOf(AlertType.HIGH to config),
            alertTypes = listOf(AlertType.HIGH),
            isMmol = true,
            isConfigActive = { true }
        )

        assertTrue(episodes.update(active.keys).shouldTryFire(AlertType.HIGH))
    }

    @Test
    fun untrustedRateSilencesForecastsButLeavesThresholdAlertsUntouched() {
        // Post-reboot window: no TrendVelocityProvider registered yet, the rate
        // is a two-point fallback slope. A -3 mg/dl/min artifact at 125 mg/dl
        // projects to 33 over 30 minutes - the forecast must stay silent, while
        // threshold alerts (which measure the actual value) keep working.
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 70.2f,
                forecastMinutes = 30
            ),
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 130f)
        )

        val untrusted = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 125f,
            rate = -3.05f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW, AlertType.LOW),
            isMmol = false,
            isConfigActive = activeConfig,
            forecastRateTrusted = false
        )
        val trusted = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 125f,
            rate = -3.05f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW, AlertType.LOW),
            isMmol = false,
            isConfigActive = activeConfig,
            forecastRateTrusted = true
        )

        assertFalse(AlertType.PRE_LOW in untrusted)
        assertTrue(AlertType.LOW in untrusted)
        assertTrue(AlertType.PRE_LOW in trusted)
    }
}
