package com.icl.surveillance.ui.patients.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.icl.surveillance.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a VHF transmission tree: the source case at the top, its contacts below it, and each
 * contact's own contacts below that. Every person is a dot; tapping a dot shows its EPID number.
 * Drag to move, pinch to zoom.
 */
class ContactTreeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Node(
        val patientId: String,
        val name: String,
        val epid: String,
        /** A contact still under follow up (otherwise a case: suspected, probable, confirmed...). */
        val isContact: Boolean,
        /** The case this screen was opened from. */
        val isCurrent: Boolean,
        val children: List<Node>,
    )

    private class Placed(val node: Node, val x: Float, val y: Float)

    var onNodeTap: ((Node?) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val hGap = 52 * density
    private val vGap = 96 * density
    private val radius = 11 * density
    private val margin = 48 * density

    private var placed: List<Placed> = emptyList()
    private var edges: List<Pair<Placed, Placed>> = emptyList()
    private var selected: Placed? = null
    private var treeWidth = 0f
    private var treeHeight = 0f
    private var fitted = false

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private val caseColor = ContextCompat.getColor(context, R.color.account_danger_action)
    private val contactColor = ContextCompat.getColor(context, R.color.home_icon_tint)
    private val currentColor = ContextCompat.getColor(context, R.color.snackbar_warning)

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.home_card_subtitle)
        alpha = 140
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.home_card_background)
    }
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = ContextCompat.getColor(context, R.color.home_card_stroke)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * density
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.home_card_title)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()

    fun setTree(root: Node) {
        val nodes = mutableListOf<Placed>()
        val links = mutableListOf<Pair<Placed, Placed>>()
        var nextLeafX = 0f
        var maxDepth = 0

        // Leaves sit side by side; a parent sits centred over its children.
        fun place(node: Node, depth: Int): Placed {
            maxDepth = max(maxDepth, depth)
            val children = node.children.map { place(it, depth + 1) }
            val x = if (children.isEmpty()) {
                nextLeafX.also { nextLeafX += hGap }
            } else {
                (children.first().x + children.last().x) / 2f
            }
            val here = Placed(node, x, depth * vGap)
            nodes += here
            children.forEach { links += here to it }
            return here
        }
        place(root, 0)

        placed = nodes
        edges = links
        treeWidth = max(0f, nextLeafX - hGap)
        treeHeight = maxDepth * vGap
        selected = placed.firstOrNull { it.node.isCurrent }
        fitted = false
        fitIfReady()
        onNodeTap?.invoke(selected?.node)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitIfReady()
    }

    /** Fits the whole tree on screen (never zooming in past 100%), centred, with the root on top. */
    private fun fitIfReady() {
        if (fitted || width == 0 || placed.isEmpty()) return
        val availableW = width - 2 * margin
        val availableH = height - 2 * margin
        scale = min(1f, min(
            if (treeWidth > 0) availableW / treeWidth else 1f,
            if (treeHeight > 0) availableH / treeHeight else 1f,
        )).coerceAtLeast(MIN_SCALE)
        tx = width / 2f - (treeWidth / 2f) * scale
        ty = margin + BUBBLE_ROOM * density
        fitted = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)

        // Elbow connectors: down from the parent, across, then down to the child.
        edges.forEach { (parent, child) ->
            val midY = (parent.y + child.y) / 2f
            path.reset()
            path.moveTo(parent.x, parent.y)
            path.lineTo(parent.x, midY)
            path.lineTo(child.x, midY)
            path.lineTo(child.x, child.y)
            canvas.drawPath(path, edgePaint)
        }

        placed.forEach { p ->
            dotPaint.color = if (p.node.isContact) contactColor else caseColor
            val r = if (p.node.isCurrent) radius * 1.3f else radius
            canvas.drawCircle(p.x, p.y, r, dotPaint)
            if (p.node.isCurrent) {
                ringPaint.color = currentColor
                canvas.drawCircle(p.x, p.y, r + 4 * density, ringPaint)
            }
            if (p === selected) {
                ringPaint.color = ContextCompat.getColor(context, R.color.home_card_title)
                canvas.drawCircle(p.x, p.y, r + 8 * density, ringPaint)
            }
        }

        // EPID bubble above the tapped dot.
        selected?.let { p ->
            val label = p.node.epid.ifBlank { "EPID pending" }
            val textWidth = textPaint.measureText(label)
            val padH = 10 * density
            val bottom = p.y - radius - 12 * density
            val top = bottom - textPaint.textSize - 14 * density
            rect.set(p.x - textWidth / 2 - padH, top, p.x + textWidth / 2 + padH, bottom)
            canvas.drawRoundRect(rect, 8 * density, 8 * density, bubblePaint)
            canvas.drawRoundRect(rect, 8 * density, 8 * density, bubbleStroke)
            canvas.drawText(label, p.x, bottom - 9 * density, textPaint)
        }
        canvas.restore()
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val factor = newScale / scale
                tx = detector.focusX - (detector.focusX - tx) * factor
                ty = detector.focusY - (detector.focusY - ty) * factor
                scale = newScale
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                tx -= dx
                ty -= dy
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val wx = (e.x - tx) / scale
                val wy = (e.y - ty) / scale
                val hit = placed.minByOrNull { hypot(it.x - wx, it.y - wy) }
                    ?.takeIf { hypot(it.x - wx, it.y - wy) <= radius * 2.5f / min(1f, scale) }
                selected = hit
                onNodeTap?.invoke(hit?.node)
                invalidate()
                performClick()
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 3f
        const val BUBBLE_ROOM = 40
    }
}
