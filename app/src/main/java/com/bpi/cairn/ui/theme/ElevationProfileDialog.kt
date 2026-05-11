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
    private val currentDistance: Double? = null // Ajout de la distance courante
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

        // On envoie la position au graphique
        currentDistance?.let { chart.setCurrentPosition(it) }

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
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f) // Ligne pointillée
    }
    // Pinceau pour l'axe Y (aligné à droite)
    private val labelPaintY = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textSize = 30f
        textAlign = Paint.Align.RIGHT
    }
    // Pinceau pour l'axe X (centré)
    private val labelPaintX = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }
    // Pinceaux pour la position
    private val positionLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5") // Bleu
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val positionDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        style = Paint.Style.FILL
    }

    // ─── Marges asymétriques pour la lisibilité ───
    private val padLeft = 130f   // Plus d'espace pour les altitudes (ex: "2500m")
    private val padRight = 40f
    private val padTop = 40f
    private val padBottom = 80f  // Espace pour les km en bas

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

            // Ligne de grille horizontale
            canvas.drawLine(padLeft, y, w - padRight, y, gridPaint)
            // Texte aligné juste avant l'axe
            canvas.drawText("${ele.roundToInt()}m", padLeft - 10f, y + 10f, labelPaintY)
        }

        // 3. Axes X et Textes (Kilométrage)
        canvas.drawLine(padLeft, padTop, padLeft, h - padBottom, axisPaint) // Axe Y
        canvas.drawLine(padLeft, h - padBottom, w - padRight, h - padBottom, axisPaint) // Axe X

        val kmSteps = (totalDist / 1000).toInt().coerceAtLeast(1)
        val kmInterval = maxOf(1, kmSteps / 5) // Affiche max 5 ou 6 graduations pour ne pas surcharger

        for (km in 0..kmSteps step kmInterval) {
            val dist = km * 1000.0
            if (dist > totalDist) break
            val x = xFor(dist)
            canvas.drawText("${km}km", x, h - 20f, labelPaintX)
            canvas.drawLine(x, h - padBottom, x, h - padBottom + 10f, axisPaint)
        }

        // 4. Dessiner la position actuelle si disponible
        currentDist?.let { cDist ->
            if (cDist in 0.0..totalDist) {
                val posX = xFor(cDist)
                var dotY = h - padBottom

                // Trouver l'altitude exacte pour dessiner le point
                for (i in 0 until distances.size - 1) {
                    if (cDist >= distances[i] && cDist <= distances[i+1]) {
                        val segmentDist = distances[i+1] - distances[i]
                        val ratio = if (segmentDist > 0) (cDist - distances[i]) / segmentDist else 0.0
                        val ele = elevations[i] + ratio * (elevations[i+1] - elevations[i])
                        dotY = yFor(ele)
                        break
                    }
                }

                // Ligne verticale bleue
                canvas.drawLine(posX, padTop, posX, h - padBottom, positionLinePaint)
                // Point bleu sur la courbe
                canvas.drawCircle(posX, dotY, 12f, positionDotPaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // On augmente un peu la hauteur relative (0.55 au lieu de 0.45) pour compenser les marges
        setMeasuredDimension(w, (w * 0.55f).toInt())
    }
}