package app.gamenative.ui.component.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContainerConfigDialogTabNavigationTest {
    @Test
    fun `forward navigation wraps from last tab to first`() {
        assertEquals(0, cycleContainerConfigTab(currentIndex = 8, tabCount = 9, offset = 1))
    }

    @Test
    fun `backward navigation wraps from first tab to last`() {
        assertEquals(8, cycleContainerConfigTab(currentIndex = 0, tabCount = 9, offset = -1))
    }

    @Test
    fun `navigation fails fast when no tabs are available`() {
        assertThrows(IllegalArgumentException::class.java) {
            cycleContainerConfigTab(currentIndex = 0, tabCount = 0, offset = 1)
        }
    }
}
