package app.ghostroute.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Small packaged vector-style glyphs; no icon-font/network dependency. Decorative by default. */
enum class FeatureGlyph { SEARCH, SPOT, ROUTE, TOOLS, PROFILE, TIMER, HISTORY, DASHBOARD, SETTINGS, COPY, SHARE, LIVE, PLAY, STOP, CHEVRON }

@Composable
fun FeatureIcon(glyph: FeatureGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(tint, p(x,y), p(xx,yy), 1.8f*u, StrokeCap.Round)
        fun ring(x: Float, y: Float, radius: Float) = drawCircle(tint, radius*u, p(x,y), style = stroke)
        fun path(vararg points: Pair<Float,Float>) {
            val path = Path()
            points.forEachIndexed { i, v -> if (i == 0) path.moveTo(v.first*u,v.second*u) else path.lineTo(v.first*u,v.second*u) }
            drawPath(path,tint,style=stroke)
        }
        when (glyph) {
            FeatureGlyph.SEARCH -> { ring(10f,10f,6.5f); line(15f,15f,21f,21f) }
            FeatureGlyph.SPOT -> { path(6f to 3f,18f to 3f,18f to 21f,12f to 17f,6f to 21f,6f to 3f) }
            FeatureGlyph.ROUTE -> { ring(5f,18f,2.5f);ring(19f,5f,2.5f);path(5f to 15f,5f to 8f,14f to 8f,14f to 18f,19f to 18f,19f to 8f) }
            FeatureGlyph.PROFILE -> { ring(12f,7f,3.5f); val q=Path().apply { moveTo(4*u,21*u); cubicTo(4*u,11*u,20*u,11*u,20*u,21*u) };drawPath(q,tint,style=stroke) }
            FeatureGlyph.TIMER -> {ring(12f,13f,8f);line(12f,13f,12f,8f);line(12f,13f,16f,15f);line(9f,2f,15f,2f)}
            FeatureGlyph.HISTORY -> {ring(12f,12f,8.5f);line(12f,12f,12f,7f);line(12f,12f,16f,14f)}
            FeatureGlyph.DASHBOARD -> {line(5f,19f,5f,13f);line(12f,19f,12f,5f);line(19f,19f,19f,9f)}
            FeatureGlyph.TOOLS -> { listOf(5f,15f).forEach { x -> listOf(5f,15f).forEach { y -> drawRoundRect(tint,p(x,y),androidx.compose.ui.geometry.Size(5*u,5*u),androidx.compose.ui.geometry.CornerRadius(1.5f*u),style=stroke) } } }
            FeatureGlyph.SETTINGS -> {line(3f,6f,21f,6f);line(3f,12f,21f,12f);line(3f,18f,21f,18f);drawCircle(tint,2.5f*u,p(8f,6f));drawCircle(tint,2.5f*u,p(16f,12f));drawCircle(tint,2.5f*u,p(10f,18f))}
            FeatureGlyph.COPY -> {path(9f to 7f,20f to 7f,20f to 21f,9f to 21f,9f to 7f);path(15f to 3f,4f to 3f,4f to 17f)}
            FeatureGlyph.SHARE -> {ring(5f,12f,2.5f);ring(19f,5f,2.5f);ring(19f,19f,2.5f);line(7f,11f,16.5f,6f);line(7f,13f,16.5f,18f)}
            FeatureGlyph.LIVE -> {ring(12f,12f,6.5f);drawCircle(tint,2.3f*u,p(12f,12f));line(12f,1f,12f,4f);line(12f,20f,12f,23f);line(1f,12f,4f,12f);line(20f,12f,23f,12f)}
            FeatureGlyph.PLAY -> {val q=Path().apply {moveTo(7*u,4*u);lineTo(20*u,12*u);lineTo(7*u,20*u);close()};drawPath(q,tint)}
            FeatureGlyph.STOP -> drawRoundRect(tint,p(5f,5f),androidx.compose.ui.geometry.Size(14*u,14*u),androidx.compose.ui.geometry.CornerRadius(3f*u))
            FeatureGlyph.CHEVRON -> path(9f to 5f,16f to 12f,9f to 19f)
        }
    }
}

@Composable
fun FeatureIconTile(glyph: FeatureGlyph, accent: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).background(accent.copy(alpha = 0.13f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
        FeatureIcon(glyph, accent)
    }
}
