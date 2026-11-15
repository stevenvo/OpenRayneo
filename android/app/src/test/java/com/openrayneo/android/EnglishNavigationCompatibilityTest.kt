package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class EnglishNavigationCompatibilityTest {
    @Test fun chineseGaodeNotificationStillParses() {
        val result = GaodeNotificationParser.parse(listOf("直行314米｜高德导航中"))
        assertEquals("Straight", result?.direction)
        assertEquals(314, result?.meters)
    }

    @Test fun kilometerDistanceStillParses() {
        val result = GaodeNotificationParser.parse(listOf("前方1.2公里后向右转，进入人民路"))
        assertEquals("Right", result?.direction)
        assertEquals(1200, result?.meters)
    }

    @Test fun genericNavigationNoticeDoesNotInventATurn() {
        assertNull(GaodeNotificationParser.parse(listOf("正在导航", "高德地图")))
    }

    @Test fun navigationWireGridKeepsItsDimensions() {
        val frame = NavigationFrame.render("Right", 80, "人民路")
        assertTrue(NavigationFrame.isGrid(frame))
        assertEquals(5, frame.lines().size)
        assertTrue(frame.lines().all { it.length == 20 })
    }

    @Test fun chineseInputProducesEnglishDisplayLabels() {
        val instruction = GaodeNotificationParser.parse(listOf("右转80米｜高德导航中"))!!
        val frame = NavigationFrame.render(instruction.direction, instruction.meters, "Main St")
        val readable = java.text.Normalizer.normalize(frame, java.text.Normalizer.Form.NFKC)
        assertTrue(readable.contains("80m"))
        assertTrue(readable.contains("Right"))
        assertFalse(frame.any { it in '\u4e00'..'\u9fff' })
    }

    @Test fun immediateTurnsAndAllDirectionLabelsFitTheGrid() {
        for (direction in listOf("Left", "Right", "Straight", "Bear L", "Bear R", "Sharp L", "Sharp R", "U-turn")) {
            val frame = NavigationFrame.render(direction, 0)
            val readable = java.text.Normalizer.normalize(frame, java.text.Normalizer.Form.NFKC)
            assertTrue(NavigationFrame.isGrid(frame))
            assertTrue(readable.contains("Now"))
            assertTrue(readable.contains(direction))
            assertTrue(frame.contains('█'))
        }
    }
}
