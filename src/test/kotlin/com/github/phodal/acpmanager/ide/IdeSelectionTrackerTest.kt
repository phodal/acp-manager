package com.github.phodal.acpmanager.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test

/**
 * Tests for IdeSelectionTracker functionality.
 */
class IdeSelectionTrackerTest : BasePlatformTestCase() {

    @Test
    fun testSelectionChangedNotificationDataClass() {
        // Test the data class directly without needing an active editor
        val notification = IdeNotification.SelectionChanged(
            filePath = "/test/file.kt",
            startLine = 0,
            startColumn = 0,
            endLine = 0,
            endColumn = 0,
            selectedText = null,
            cursorOffset = 0
        )

        assertEquals("selection_changed", notification.method)
        assertNotNull(notification)
    }

    @Test
    fun testSelectionChangedHasRequiredFields() {
        // Test that SelectionChanged has all required fields
        val notification = IdeNotification.SelectionChanged(
            filePath = "/test/file.kt",
            startLine = 10,
            startColumn = 5,
            endLine = 15,
            endColumn = 20,
            selectedText = "code",
            cursorOffset = 150,
            fileType = "Kotlin"
        )

        // Verify all required fields are present
        assertEquals(10, notification.startLine)
        assertEquals(5, notification.startColumn)
        assertEquals(15, notification.endLine)
        assertEquals(20, notification.endColumn)

        // Verify cursor offset is tracked
        assertTrue("cursorOffset should be >= 0", notification.cursorOffset >= 0)
        assertEquals("Kotlin", notification.fileType)
    }

    @Test
    fun testDebouncingConfiguration() {
        // Test that debouncing delay is configurable
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracker1 = IdeSelectionTracker(project, scope, debounceDelayMs = 100L)
        val tracker2 = IdeSelectionTracker(project, scope, debounceDelayMs = 500L)

        // Both trackers should be created successfully with different debounce delays
        assertNotNull(tracker1)
        assertNotNull(tracker2)

        tracker1.stopTracking()
        tracker2.stopTracking()
    }

    @Test
    fun testStopTracking() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracker = IdeSelectionTracker(project, scope, debounceDelayMs = 100L)

        tracker.startTracking { _ -> }

        // Verify tracker can be stopped without errors
        tracker.stopTracking()

        // Verify tracker can be stopped multiple times safely
        tracker.stopTracking()

        assertNotNull(tracker)
    }

    @Test
    fun testSelectionChangedDataClass() {
        val notification = IdeNotification.SelectionChanged(
            filePath = "/path/to/file.kt",
            startLine = 10,
            startColumn = 5,
            endLine = 15,
            endColumn = 20,
            selectedText = "some code",
            cursorOffset = 150,
            fileType = "Kotlin"
        )

        assertEquals("selection_changed", notification.method)
        assertEquals("/path/to/file.kt", notification.filePath)
        assertEquals(10, notification.startLine)
        assertEquals(5, notification.startColumn)
        assertEquals(15, notification.endLine)
        assertEquals(20, notification.endColumn)
        assertEquals("some code", notification.selectedText)
        assertEquals(150, notification.cursorOffset)
        assertEquals("Kotlin", notification.fileType)
    }

    @Test
    fun testSelectionChangedWithoutSelection() {
        val notification = IdeNotification.SelectionChanged(
            filePath = "/path/to/file.kt",
            startLine = 0,
            startColumn = 0,
            endLine = 0,
            endColumn = 0,
            selectedText = null,
            cursorOffset = 0
        )

        assertEquals("selection_changed", notification.method)
        assertNull(notification.selectedText)
        assertEquals(0, notification.cursorOffset)
    }
}

