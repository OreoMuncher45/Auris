package com.music.bitchord.ui.utils

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange

/**
 * Stops a resting [androidx.compose.material3.ModalBottomSheet] from stealing a
 * touch that belongs to the content inside it. Goes on the root of the sheet's
 * content.
 *
 * Material3 1.3.1's sheet starts its own drag on touch-down, skipping the touch
 * slop and taking the event in the Initial pass before any content sees it,
 * whenever it thinks it is animating. And it is "animating" for 300 ms after
 * every scroll that ends inside it: its nested-scroll connection calls settle()
 * on every post-fling, even one with zero velocity left, and settle runs the
 * sheet's fixed 300 ms tween from the anchor it is already on to that same
 * anchor. So a finger that lands within 300 ms of the last scroll ending drags
 * the sheet. The sheet carries the content along with it, so the list under the
 * finger sees no movement and never takes the gesture back. The player collapsing
 * mid-way through scrolling the lyrics or queue was exactly that.
 *
 * The fix: if the sheet grabbed the down while it was fully open (so there was
 * no real motion to catch), consume the first move in the Main pass. The sheet's
 * drag runs after its content in that pass, sees the move consumed, and cancels,
 * and the content's own drag carries on from its touch slop as usual. A sheet
 * that is really moving (opening, or springing back from a partial drag) is left
 * alone, so a finger can still catch it.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.guardSheetFromContentTouches(sheetState: SheetState): Modifier =
    pointerInput(sheetState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Unconsumed means the sheet was not animating, so it waits for its
            // slop like any other ancestor and the content wins as normal.
            if (!down.isConsumed) return@awaitEachGesture
            val offset = runCatching { sheetState.requireOffset() }.getOrNull()
                ?: return@awaitEachGesture
            if (offset > 1f) return@awaitEachGesture
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (change.positionChange() != Offset.Zero) {
                    change.consume()
                    break
                }
            }
        }
    }
