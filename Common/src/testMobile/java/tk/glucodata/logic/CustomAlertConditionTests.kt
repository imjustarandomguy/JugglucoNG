package tk.glucodata.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertType
import tk.glucodata.ui.util.GlucoseFormatter

class CustomAlertConditionTests {

    private fun mmol(mgdl: Int): Float = GlucoseFormatter.displayFromMgDl(mgdl.toFloat(), true)

    private fun active(type: CustomAlertType, threshold: Float, glucose: Float, isMmol: Boolean): Boolean =
        CustomAlertManager.isConditionActive(CustomAlertConfig(type = type, threshold = threshold), glucose, isMmol)

    @Test
    fun aReadingShownAsTheThresholdIsNotPastIt() {
        // 72 mg/dL is 3.996 mmol/L, shown 4.0; 71 is 3.94, shown 3.9.
        assertFalse(active(CustomAlertType.LOW, 4.0f, mmol(72), isMmol = true))
        assertTrue(active(CustomAlertType.LOW, 4.0f, mmol(71), isMmol = true))
        assertFalse(active(CustomAlertType.LOW, 70f, 70f, isMmol = false))
        assertTrue(active(CustomAlertType.LOW, 70f, 69f, isMmol = false))

        // 181 mg/dL is 10.05 mmol/L, shown 10.0; 182 is 10.10, shown 10.1.
        assertFalse(active(CustomAlertType.HIGH, 10.0f, mmol(181), isMmol = true))
        assertTrue(active(CustomAlertType.HIGH, 10.0f, mmol(182), isMmol = true))
        assertFalse(active(CustomAlertType.HIGH, 180f, 180f, isMmol = false))
        assertTrue(active(CustomAlertType.HIGH, 180f, 181f, isMmol = false))
    }
}
