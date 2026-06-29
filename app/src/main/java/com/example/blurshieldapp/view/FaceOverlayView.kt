package com.example.blurshieldapp.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.face.Face
import kotlin.math.hypot

data class FaceBox(
    var rect: RectF,        // in BITMAP coordinates (not screen)
    val originalRect: RectF // ML Kit's original detection, for reference/reset
)

class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
    }
    private val selectedPaint = Paint().apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.argb(40, 255, 255, 0); style = Paint.Style.FILL
    }
    private val handlePaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val handleLengthPx = 15f   // length of each bracket arm
    private val handleTouchRadiusPx = 35f
    // ---------- Data ----------
    private var boxes: MutableList<FaceBox> = mutableListOf()
    val selectedFaces = mutableSetOf<Int>()

    // The shared transform matrix (set externally by PhotoEditCanvasView)
    private var transformMatrix = Matrix()
    private var bitmapW = 0f
    private var bitmapH = 0f

    var onFaceSelectionChanged: ((Set<Int>) -> Unit)? = null
    var onBoxAdjusted: ((index: Int, newRect: RectF) -> Unit)? = null

    // Drag state
    private var activeHandle: Handle? = null
    private var activeBoxIndex: Int = -1
    private var dragStartBitmapPoint: PointF? = null
    private var dragStartRect: RectF? = null
    private enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }
    private var drawingEnabled = true
    var onBoxAdjustFinished: (() -> Unit)? = null


    // ---------- Public API ----------

    fun setFaces(faces: List<Face>, bitmapWidth: Int, bitmapHeight: Int) {
        bitmapW = bitmapWidth.toFloat()
        bitmapH = bitmapHeight.toFloat()
        boxes = faces.map {
            val r = RectF(it.boundingBox)
            FaceBox(rect = RectF(r), originalRect = RectF(r))
        }.toMutableList()
        selectedFaces.clear()
        invalidate()
    }
    fun updateTransform(matrix: Matrix) {
        transformMatrix = Matrix(matrix)
        invalidate()
    }

    fun setDrawingEnabled(enabled: Boolean) {
        drawingEnabled = enabled
        invalidate()
    }

    fun selectFace(index: Int, selected: Boolean) {
        if (selected) selectedFaces.add(index) else selectedFaces.remove(index)
        onFaceSelectionChanged?.invoke(selectedFaces.toSet())
        invalidate()
    }

    fun clearSelection() {
        selectedFaces.clear()
        onFaceSelectionChanged?.invoke(selectedFaces.toSet())
        invalidate()
    }

    fun resetBox(index: Int) {
        boxes.getOrNull(index)?.let {
            it.rect = RectF(it.originalRect)
            onBoxAdjusted?.invoke(index, it.rect)
            invalidate()
        }
    }

    fun getBoxRect(index: Int): RectF? = boxes.getOrNull(index)?.rect

    // ---------- Drawing ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!drawingEnabled) return
        boxes.forEachIndexed { i, box ->
            val screenRect = mapRectToScreen(box.rect)
            val isSelected = i in selectedFaces

            if (isSelected) {
                canvas.drawRect(screenRect, fillPaint)
                canvas.drawRect(screenRect, selectedPaint)
                drawHandles(canvas, screenRect)
            } else {
                canvas.drawRect(screenRect, boxPaint)
            }
        }
    }

    // Replace the whole drawHandles() function:
    private fun drawHandles(canvas: Canvas, rect: RectF) {
        val maxAllowedLen = minOf(rect.width(), rect.height()) * 0.4f
        val len = minOf(handleLengthPx, maxAllowedLen)
        // Top-left
        canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, handlePaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + len, handlePaint)

        // Top-right
        canvas.drawLine(rect.right, rect.top, rect.right - len, rect.top, handlePaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, handlePaint)

        // Bottom-left
        canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, handlePaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - len, handlePaint)

        // Bottom-right
        canvas.drawLine(rect.right, rect.bottom, rect.right - len, rect.bottom, handlePaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - len, handlePaint)
    }

    private fun mapRectToScreen(bitmapRect: RectF): RectF {
        val pts = floatArrayOf(
            bitmapRect.left, bitmapRect.top,
            bitmapRect.right, bitmapRect.bottom
        )
        transformMatrix.mapPoints(pts)
        return RectF(
            minOf(pts[0], pts[2]), minOf(pts[1], pts[3]),
            maxOf(pts[0], pts[2]), maxOf(pts[1], pts[3])
        )
    }

    private fun screenPointToBitmap(x: Float, y: Float): PointF {
        val inverse = Matrix()
        transformMatrix.invert(inverse)
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    // ---------- Touch handling ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findHandleOrBox(event.x, event.y)
                if (hit != null) {
                    activeBoxIndex = hit.first
                    activeHandle = hit.second
                    dragStartBitmapPoint = screenPointToBitmap(event.x, event.y)
                    dragStartRect = RectF(boxes[hit.first].rect)

                    // FIX 1: Disallow parent scrollview from stealing this box adjustment
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                // FIX 2: Intercept the touch if the user pressed anywhere inside a green box
                if (findBoxAtPoint(event.x, event.y) >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = activeBoxIndex
                val handle = activeHandle
                val start = dragStartBitmapPoint
                val startRect = dragStartRect
                if (idx >= 0 && handle != null && start != null && startRect != null) {
                    val current = screenPointToBitmap(event.x, event.y)
                    val dx = current.x - start.x
                    val dy = current.y - start.y

                    val newRect = RectF(startRect)
                    when (handle) {
                        Handle.TOP_LEFT -> { newRect.left += dx; newRect.top += dy }
                        Handle.TOP_RIGHT -> { newRect.right += dx; newRect.top += dy }
                        Handle.BOTTOM_LEFT -> { newRect.left += dx; newRect.bottom += dy }
                        Handle.BOTTOM_RIGHT -> { newRect.right += dx; newRect.bottom += dy }
                        Handle.MOVE -> { newRect.offset(dx, dy) }
                    }

                    clampRect(newRect)
                    boxes[idx].rect = newRect
                    onBoxAdjusted?.invoke(idx, newRect)
                    invalidate()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // FIX 3: Release the scrollview so the user can scroll the page normally again
                parent?.requestDisallowInterceptTouchEvent(false)

                if (activeHandle != null) {
                    activeHandle = null
                    activeBoxIndex = -1
                    dragStartBitmapPoint = null
                    dragStartRect = null
                    onBoxAdjustFinished?.invoke()
                    return true
                }

                // Plain tap handling logic
                val tapped = findBoxAtPoint(event.x, event.y)
                if (tapped >= 0) {
                    selectFace(tapped, tapped !in selectedFaces)
                    return true
                }
            }
        }
        return false
    }

    private fun clampRect(rect: RectF) {
        val minSize = 30f // bitmap-space px
        if (rect.width() < minSize) {
            if (rect.left > rect.right - minSize) rect.left = rect.right - minSize
            else rect.right = rect.left + minSize
        }
        if (rect.height() < minSize) {
            if (rect.top > rect.bottom - minSize) rect.top = rect.bottom - minSize
            else rect.bottom = rect.top + minSize
        }
        rect.left = rect.left.coerceIn(0f, bitmapW - minSize)
        rect.top = rect.top.coerceIn(0f, bitmapH - minSize)
        rect.right = rect.right.coerceIn(minSize, bitmapW)
        rect.bottom = rect.bottom.coerceIn(minSize, bitmapH)
    }

    /** Returns Pair(boxIndex, Handle) if a handle or selected-box-body was hit */
    private fun findHandleOrBox(x: Float, y: Float): Pair<Int, Handle>? {
        // Check handles only on selected boxes, topmost first
        for (i in boxes.indices.reversed()) {
            if (i !in selectedFaces) continue
            val r = mapRectToScreen(boxes[i].rect)

            val corners = mapOf(
                Handle.TOP_LEFT to PointF(r.left, r.top),
                Handle.TOP_RIGHT to PointF(r.right, r.top),
                Handle.BOTTOM_LEFT to PointF(r.left, r.bottom),
                Handle.BOTTOM_RIGHT to PointF(r.right, r.bottom)
            )
            for ((handle, p) in corners) {
                if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) <= handleTouchRadiusPx) {
                    return i to handle
                }
            }
            // Inside selected box body -> move
            if (r.contains(x, y)) return i to Handle.MOVE
        }
        return null
    }

    private fun findBoxAtPoint(x: Float, y: Float): Int {
        for (i in boxes.indices.reversed()) {
            if (mapRectToScreen(boxes[i].rect).contains(x, y)) return i
        }
        return -1
    }

    // Used by parent to decide whether to let ScaleGestureDetector/pan take over
    fun isTouchOnHandleOrSelectedBox(x: Float, y: Float): Boolean {
        if (!drawingEnabled) return false
        return findHandleOrBox(x, y) != null
    }
    fun setFacesFromRects(rects: List<RectF>, bitmapWidth: Int, bitmapHeight: Int) {
        bitmapW = bitmapWidth.toFloat()
        bitmapH = bitmapHeight.toFloat()
        // Preserve existing originalRect references where possible; for simplicity, treat current as both
        boxes = rects.mapIndexed { i, r ->
            val existing = boxes.getOrNull(i)
            FaceBox(rect = RectF(r), originalRect = existing?.originalRect ?: RectF(r))
        }.toMutableList()
        invalidate()
    }

    // New method — syncs selection state from ViewModel without re-triggering callbacks
    fun syncSelection(selected: Set<Int>) {
        selectedFaces.clear()
        selectedFaces.addAll(selected)
        invalidate()
    }
}