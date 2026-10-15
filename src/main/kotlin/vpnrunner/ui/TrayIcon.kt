package vpnrunner.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/**
 * Значок в строке меню — щит. Рисуется кодом, без файлов картинок.
 *  - выключено: серый контур (виден и на светлой, и на тёмной строке меню);
 *  - подключается / отключается: жёлтый щит;
 *  - работает: зелёный щит с галочкой.
 */
class TrayIcon private constructor(private val color: Color, private val filled: Boolean, private val check: Boolean) : Painter() {

    override val intrinsicSize = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.50f, h * 0.06f)
            lineTo(w * 0.86f, h * 0.19f)
            lineTo(w * 0.86f, h * 0.47f)
            cubicTo(w * 0.86f, h * 0.71f, w * 0.70f, h * 0.87f, w * 0.50f, h * 0.95f)
            cubicTo(w * 0.30f, h * 0.87f, w * 0.14f, h * 0.71f, w * 0.14f, h * 0.47f)
            lineTo(w * 0.14f, h * 0.19f)
            close()
        }
        if (filled) drawPath(shield, color)
        else drawPath(shield, color, style = Stroke(width = w * 0.09f, join = StrokeJoin.Round))
        if (check) {
            val mark = Path().apply {
                moveTo(w * 0.32f, h * 0.50f)
                lineTo(w * 0.45f, h * 0.63f)
                lineTo(w * 0.69f, h * 0.37f)
            }
            drawPath(mark, Color.White, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    companion object {
        val Off = TrayIcon(Color(0xFF8E8E93), filled = false, check = false)
        val Busy = TrayIcon(Color(0xFFF2B200), filled = true, check = false)
        val On = TrayIcon(Color(0xFF2FB344), filled = true, check = true)

        fun forPhase(phase: Phase) = when (phase) {
            Phase.STOPPED -> Off
            Phase.STARTING, Phase.STOPPING -> Busy
            Phase.RUNNING -> On
        }
    }
}
