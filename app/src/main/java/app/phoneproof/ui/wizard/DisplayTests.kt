package app.phoneproof.ui.wizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.phoneproof.core.DecidedBy
import app.phoneproof.core.TestOutcome
import app.phoneproof.core.TestResult
import app.phoneproof.ui.theme.PP
import app.phoneproof.ui.theme.Space
import kotlinx.coroutines.delay

/*
 * Full-screen display tests. They run edge to edge (call from an Activity in immersive mode),
 * so their colours are the test itself, not theme colours.
 */

/**
 * Touch grid: 12 × 20 cells. Swipe or tap every square; a hit square turns green.
 * PASS when all are hit within [timeLimitMs]; FAIL lists how many were missed.
 */
@Composable
fun TouchGridTest(cols: Int = 12, rows: Int = 20, timeLimitMs: Long = 60_000, onResult: (TestResult) -> Unit) {
    val total = cols * rows
    val hit = remember { BooleanArray(total) }
    var hitCount by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var done by remember { mutableStateOf(false) }

    fun finish(outcome: TestOutcome) {
        if (done) return
        done = true
        onResult(TestResult("display.touch", outcome, DecidedBy.AUTO, mapOf("cells" to total.toLong(), "missed" to (total - hitCount).toLong())))
    }

    fun mark(p: Offset) {
        if (size.width == 0 || done) return
        val c = (p.x / size.width * cols).toInt().coerceIn(0, cols - 1)
        val r = (p.y / size.height * rows).toInt().coerceIn(0, rows - 1)
        val i = r * cols + c
        if (!hit[i]) {
            hit[i] = true
            hitCount++
            if (hitCount == total) finish(TestOutcome.PASS)
        }
    }

    LaunchedEffect(Unit) {
        delay(timeLimitMs)
        finish(if (hitCount == total) TestOutcome.PASS else TestOutcome.FAIL)
    }

    val hitColor = PP.status.success
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { size = it }
                .pointerInput(Unit) { detectTapGestures(onTap = { mark(it) }) }
                .pointerInput(Unit) { detectDragGestures(onDragStart = { mark(it) }) { change, _ -> mark(change.position) } },
        ) {
            val w = this.size.width / cols
            val h = this.size.height / rows
            val gap = 2.dp.toPx()
            // Reading hitCount here makes the canvas redraw on every new square.
            if (hitCount < 0) return@Canvas
            for (r in 0 until rows) for (c in 0 until cols) {
                drawRoundRect(
                    color = if (hit[r * cols + c]) hitColor else Color(0xFFDDE2E5),
                    topLeft = Offset(c * w + gap, r * h + gap),
                    size = Size(w - 2 * gap, h - 2 * gap),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        Text(
            "${total - hitCount} left", style = MaterialTheme.typography.labelLarge, color = Color(0xFF131C20),
            modifier = Modifier.align(Alignment.Center).background(Color.White.copy(alpha = 0.85f), MaterialTheme.shapes.extraLarge)
                .padding(horizontal = Space.s16, vertical = Space.s8),
        )
    }
}

/**
 * Dead pixels and tint: full-screen red, green, blue, white, black. Tap to go to the next colour.
 * The seller then answers on a normal step ("Did you see any spots or patches?").
 */
@Composable
fun ColourFillTest(onFinished: () -> Unit) {
    val colours = listOf(Color.Red, Color(0xFF00FF00), Color.Blue, Color.White, Color.Black)
    var i by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxSize().background(colours[i]).clickable(onClickLabel = "Next colour") {
            if (i < colours.lastIndex) i++ else onFinished()
        },
    )
}
