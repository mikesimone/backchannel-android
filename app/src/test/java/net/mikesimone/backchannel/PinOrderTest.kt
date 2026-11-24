package net.mikesimone.backchannel

import org.junit.Assert.assertEquals
import org.junit.Test

class PinOrderTest {

  @Test
  fun wholeListReordered() {
    assertEquals(listOf(3L, 1L, 2L), PinOrder.merge(listOf(1L, 2L, 3L), listOf(3L, 1L, 2L)))
  }

  @Test
  fun folderViewKeepsHiddenPinsInPlace() {
    // 2 and 4 aren't in this folder; 1, 3, 5 are shown and dragged into 5, 1, 3
    assertEquals(listOf(5L, 2L, 1L, 4L, 3L), PinOrder.merge(listOf(1L, 2L, 3L, 4L, 5L), listOf(5L, 1L, 3L)))
  }

  @Test
  fun chatUnpinnedMeanwhileIsIgnored() {
    assertEquals(listOf(3L, 1L), PinOrder.merge(listOf(1L, 3L), listOf(3L, 2L, 1L)))
  }

  @Test
  fun nothingVisibleChangesNothing() {
    assertEquals(listOf(1L, 2L), PinOrder.merge(listOf(1L, 2L), emptyList()))
  }
}
