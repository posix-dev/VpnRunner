package vpnrunner.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/** Рисуем знак VpnRunner без зависимости от растровых файлов в окне и строке меню. */
class AppIconPainter(
    private val background: Color?,
    private val vColor: Color,
    private val routeColor: Color,
    private val circleBackground: Boolean = false,
) : Painter() {
    override val intrinsicSize = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        background?.let {
            if (circleBackground) drawCircle(it)
            else drawRoundRect(it, cornerRadius = CornerRadius(w * 0.2f, h * 0.2f))
        }

        val v = Path().apply {
            moveTo(w * 0.28f, h * 0.205f)
            lineTo(w * 0.422f, h * 0.717f)
            quadraticTo(w * 0.46f, h * 0.768f, w * 0.49f, h * 0.717f)
            lineTo(w * 0.633f, h * 0.205f)
        }
        drawPath(v, vColor, style = Stroke(width = w * 0.086f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val route = Path().apply {
            moveTo(w * 0.684f, h * 0.393f)
            lineTo(w * 0.844f, h * 0.5f)
            lineTo(w * 0.684f, h * 0.607f)
        }
        drawPath(route, routeColor, style = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    companion object {
        private val tile = Color(0xFF202735)
        private val blue = Color(0xFF86A6FF)
        private val green = Color(0xFF70D99A)

        val Window = AppIconPainter(tile, blue, green)

        fun forPhase(phase: Phase) = when (phase) {
            Phase.STOPPED -> AppIconPainter(Color(0xFF8E8E93), Color.White, Color.White, circleBackground = true)
            Phase.STARTING, Phase.STOPPING -> AppIconPainter(Color(0xFFD9922B), Color.White, Color.White, circleBackground = true)
            Phase.RUNNING -> AppIconPainter(Color(0xFF2E9E5B), Color.White, Color.White, circleBackground = true)
        }
    }
}
