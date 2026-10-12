package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChartDotBufferTests {

    @Test
    fun keepsPairsInOrderAndGrowsPastItsFirstSize() {
        val buffer = ChartDotBuffer()
        repeat(500) { index -> buffer.add(index.toFloat(), -index.toFloat()) }

        assertEquals(1000, buffer.size)
        assertEquals(0f, buffer.coordinates[0], 0f)
        assertEquals(499f, buffer.coordinates[998], 0f)
        assertEquals(-499f, buffer.coordinates[999], 0f)
    }

    @Test
    fun clearKeepsTheArrayForTheNextFrame() {
        val buffer = ChartDotBuffer()
        repeat(300) { buffer.add(1f, 2f) }
        val grown = buffer.coordinates

        buffer.clear()
        buffer.add(3f, 4f)

        assertEquals(2, buffer.size)
        assertEquals(3f, buffer.coordinates[0], 0f)
        assertSame(grown, buffer.coordinates)
    }
}
