package net.mikesimone.backchannel

/**
 * Drag-to-reorder pinned chats. The list on screen may show only some pinned chats (a chat folder),
 * so the new on-screen order fills the slots those chats held in the full pin order and every other
 * pinned chat keeps its place.
 */
object PinOrder {
  @JvmStatic
  fun merge(allPinned: List<Long>, visibleInNewOrder: List<Long>): List<Long> {
    val pinnedSet = allPinned.toSet()
    val moved = visibleInNewOrder.filter { it in pinnedSet }.distinct()
    val movedSet = moved.toSet()
    val next = moved.iterator()
    return allPinned.map { if (it in movedSet) next.next() else it }
  }
}
