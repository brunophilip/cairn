package com.bpi.cairn.ui.theme

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.bpi.cairn.R
import com.bpi.cairn.gpx.GpxTrack
import com.bpi.cairn.gpx.cumulativeDistances
import kotlin.math.roundToInt

// ─── Elevation Profile Dialog ─────────────────────────────────────────────────

class ElevationProfileDialog(
    context: Context,
    private val track: GpxTrack,
    private val currentIndex: Int? = null // ✅ On reçoit maintenant l'index depuis MainActivity
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_elevation)
        window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        val chart = findViewById<ElevationChartView>(R.id.elevationChart)
        chart.setTrack(track)

        // ✅ On convertit l'index exact du cycliste en distance (mètres) pour le graphique
        if (currentIndex != null) {
            val distances = track.cumulativeDistances()
            if (currentIndex in distances.indices) {
                chart.setCurrentPosition(distances[currentIndex])
            }
        }

        val totalKm = track.totalDistanceMeters / 1000.0
        val gain    = track.elevationGain.roundToInt()
        val loss    = track.elevationLoss.roundToInt()
        val minEle  = track.minElevation.roundToInt()
        val maxEle  = track.maxElevation.roundToInt()

        findViewById<TextView>(R.id.tvDistance).text  = "Distance : ${"%.1f".format(totalKm)} km"
        findViewById<TextView>(R.id.tvGain).text      = "D+ : $gain m"
        findViewById<TextView>(R.id.tvLoss).text      = "D- : $loss m"
        findViewById<TextView>(R.id.tvEleRange).text  = "Alt. : $minEle – $maxEle m"

        setTitle("Profil de dénivelé")
    }
}

// ─── Custom View: Elevation Chart ─────────────────────────────────────────────

class ElevationChartView(context: Context, attrs: android.util.AttributeSet? = null) :
    View(context, attrs) {

    private var track: GpxTrack? = null
    private var distances: List<Double> = emptyList()
    private var currentDist: Double? = null

    // ─── Pinceaux (Paints) ───
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
        alpha = 150
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        strokeWidth = 2f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#424242")
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val labelPaintY = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textSize = 30f
        textAlign = Paint.Align.RIGHT
    }
    private val labelPaintX = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    // ✅ Pinceaux pour la position (PASSÉS EN ROUGE)
    private val positionLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val positionDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val padLeft = 130f
    private val padRight = 40f
    private val padTop = 40f
    private val padBottom = 80f

    fun setTrack(t: GpxTrack) {
        track = t
        distances = t.cumulativeDistances()
        invalidate()
    }

    fun setCurrentPosition(dist: Double) {
        currentDist = dist
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = track ?: return
        if (t.points.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val chartW = w - padLeft - padRight
        val chartH = h - padTop - padBottom

        val totalDist = distances.last().coerceAtLeast(1.0)
        val elevations = t.points.map { it.ele }
        val minEle = elevations.min()
        val maxEle = elevations.max()
        val eleRange = (maxEle - minEle).coerceAtLeast(1.0)

        fun xFor(dist: Double)  = padLeft + (dist / totalDist * chartW).toFloat()
        fun yFor(ele: Double)   = padTop + chartH - ((ele - minEle) / eleRange * chartH).toFloat()

        // 1. Tracer le remplissage et la ligne
        val path = Path()
        path.moveTo(xFor(distances[0]), yFor(elevations[0]))
        for (i in 1 until t.points.size) {
            path.lineTo(xFor(distances[i]), yFor(elevations[i]))
        }

        val fillPath = Path(path)
        fillPath.lineTo(xFor(totalDist), h - padBottom)
        fillPath.lineTo(padLeft, h - padBottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // 2. Grilles et textes Y (Altitude)
        val steps = 4
        for (i in 0..steps) {
            val ele = minEle + eleRange * i / steps
            val y = yFor(ele)
            canvas.drawLine(padLeft, y, w - padRight, y, gridPaint)
            canvas.drawText("${ele.roundToInt()}m", padLeft - 10f, y + 10f, labelPaintY)
        }

        // 3. Axes X et Textes (Kilométrage)
        canvas.drawLine(padLeft, padTop, padLeft, h - padBottom, axisPaint)
        canvas.drawLine(padLeft, h - padBottom, w - padRight, h - padBottom, axisPaint)

        val kmSteps = (totalDist / 1000).toInt().coerceAtLeast(1)
        val kmInterval = maxOf(1, kmSteps / 5)

        for (km in 0..kmSteps step kmInterval) {
            val dist = km * 1000.0
            if (dist > totalDist) break
            val x = xFor(dist)
            canvas.drawText("${km}km", x, h - 20f, labelPaintX)
            canvas.drawLine(x, h - padBottom, x, h - padBottom + 10f, axisPaint)
        }

        // 4. Dessiner la position actuelle avec le point et la ligne rouge
        currentDist?.let { cDist ->
            if (cDist in 0.0..totalDist) {
                val posX = xFor(cDist)
                var dotY = h - padBottom

                for (i in 0 until distances.size - 1) {
                    if (cDist >= distances[i] && cDist <= distances[i+1]) {
                        val segmentDist = distances[i+1] - distances[i]
                        val ratio = if (segmentDist > 0) (cDist - distances[i]) / segmentDist else 0.0
                        val ele = elevations[i] + ratio * (elevations[i+1] - elevations[i])
                        dotY = yFor(ele)
                        break
                    }
                }

                // Ligne verticale rouge
                canvas.drawLine(posX, padTop, posX, h - padBottom, positionLinePaint)
                // Point rouge sur la courbe
                canvas.drawCircle(posX, dotY, 15f, positionDotPaint) // Légèrement agrandi (15f au lieu de 12f) pour plus de lisibilité en roulant
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.55f).toInt())
    }
}