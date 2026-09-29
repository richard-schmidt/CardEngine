package com.ccg

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ccg.ArtRect
import ccg.FaceDoc
import ccgui.FIELD_ASPECT
import ccgui.MINI_ASPECT
import ccgui.centreCrop
import ccgui.hasOwnMiniRect
import ccgui.lockRect
import ccgui.moveRect
import ccgui.rectFor
import ccgui.resizeRect
import java.io.File
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Card art: sidecar image files (GameStore.artFile) decoded downsampled with a
// small process-wide cache, a chosen RECTANGLE drawn, and the modal where
// rectangles are drawn. A missing file falls back to the ◈ glyph.
// ---------------------------------------------------------------------------

private val ArtCache = object : LruCache<String, ImageBitmap>(24) {}

/** The tile bar: a board/hand thumbnail is never drawn anywhere near this big. */
const val ART_PX_TILE = 640

/** The editing bar: the modal shows the whole file and the focused card shows
 *  it at its own shape, so both want more than a thumbnail's worth of pixels.
 *  Only those two ask for it, so the extra memory is paid by one card at a
 *  time rather than by a whole board. */
const val ART_PX_EDIT = 1600

/** Decode `filename` from the game's art dir, downsampled to ~`maxPx`, cached
 *  by path AND size. Null when there is no art or the file is gone. */
@Composable
fun rememberCardArt(
    store: GameStore,
    gameId: String,
    filename: String?,
    maxPx: Int = ART_PX_TILE,
): ImageBitmap? {
    if (filename.isNullOrEmpty() || gameId.isEmpty()) return null
    val path = store.artFile(gameId, filename).path
    // The key carries maxPx: the same picture decoded at two sizes is two
    // entries, or the board would show whatever the editor happened to cache.
    val key = "$path@$maxPx"
    return remember(key) {
        ArtCache.get(key) ?: run {
            val f = File(path)
            if (!f.exists()) {
                null
            } else {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.asImageBitmap()
                        ?.also { ArtCache.put(key, it) }
                }.getOrNull()
            }
        }
    }
}

/**
 * The ONE place a card's picture is drawn (tile, preview, modal), so a crop
 * means the same everywhere. A rectangle of the source fills the box; `rect`
 * null = a centred crop of the box's shape. `whole = true` contains the entire
 * picture (the focused card).
 */
@Composable
fun ArtLayer(art: ImageBitmap, rect: ArtRect?, modifier: Modifier = Modifier, whole: Boolean = false) {
    Canvas(modifier.fillMaxSize()) {
        val iw = art.width.toFloat()
        val ih = art.height.toFloat()
        if (iw <= 0f || ih <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val r = when {
            whole -> ArtRect()
            rect != null -> rect
            else -> centreCrop(iw, ih, size.width / size.height)
        }
        val sx = (r.x * iw).roundToInt().coerceIn(0, art.width - 1)
        val sy = (r.y * ih).roundToInt().coerceIn(0, art.height - 1)
        val sw = (r.w * iw).roundToInt().coerceIn(1, art.width - sx)
        val sh = (r.h * ih).roundToInt().coerceIn(1, art.height - sy)
        drawImage(
            image = art,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
}

// -- the modal -------------------------------------------------------------

private val FIELD_INK = Color(0xFF4FD6C9)   // solid  = on the field
private val MINI_INK = Color(0xFFB79CFF)    // dashed = shrunk

/** One draggable rectangle over the picture. `box` is the drawn image's size in
 *  pixels, so a drag converts to fractions of the image and nothing depends on
 *  how big the picture happens to be on screen. */
@Composable
private fun CropBox(
    rect: ArtRect,
    boxW: Float,
    boxH: Float,
    imgW: Float,
    imgH: Float,
    aspect: Float,
    ink: Color,
    dashed: Boolean,
    label: String,
    onRect: (ArtRect) -> Unit,
) {
    val d = LocalDensity.current
    // `pointerInput` captures its values ONCE for the detector's life, so the
    // drag reads `rect` / `onRect` through `rememberUpdatedState`. (Keying on
    // them would restart the detector and cancel the drag.)
    val liveRect by rememberUpdatedState(rect)
    val liveOnRect by rememberUpdatedState(onRect)
    val x = with(d) { (rect.x * boxW).toDp() }
    val y = with(d) { (rect.y * boxH).toDp() }
    val w = with(d) { (rect.w * boxW).toDp() }
    val h = with(d) { (rect.h * boxH).toDp() }
    Box(Modifier.offset(x, y).size(w, h)) {
        // The outline. Drawn rather than bordered so the dashes are real.
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(boxW, boxH, imgW, imgH) {
                    detectDragGestures { _, drag ->
                        liveOnRect(moveRect(liveRect, drag.x / boxW, drag.y / boxH))
                    }
                },
        ) {
            val stroke = if (dashed) {
                Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect
                        .dashPathEffect(floatArrayOf(9f, 7f), 0f),
                )
            } else {
                Stroke(width = 2.dp.toPx())
            }
            drawRect(color = ink, style = stroke)
        }
        Text(
            label,
            color = ink,
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.TopStart).offset(y = (-13).dp),
        )
        // Four corners. Each one keeps the OPPOSITE corner still.
        for ((corner, align) in listOf(
            ("nw" to Alignment.TopStart), ("ne" to Alignment.TopEnd),
            ("sw" to Alignment.BottomStart), ("se" to Alignment.BottomEnd),
        )) {
            val east = corner[1] == 'e'
            val south = corner[0] == 's'
            Box(
                Modifier
                    .align(align)
                    .size(24.dp)
                    .pointerInput(boxW, boxH, imgW, imgH, corner) {
                        detectDragGestures { _, drag ->
                            liveOnRect(resizeRect(liveRect, drag.x / boxW, east, south, imgW, imgH, aspect))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(13.dp).background(Cg.bg).border(2.dp, ink, RoundedCornerShape(3.dp)))
            }
        }
    }
}

/**
 * The card-art modal -- the only place art is set or cropped. The whole
 * picture is on screen and each tile marks what it takes: SOLID for the field
 * tile, DASHED for the shrunk tile (which follows the field crop until
 * switched on).
 */
@Composable
fun CgArtStudio(
    store: GameStore,
    gameId: String,
    face: FaceDoc,
    onFace: (FaceDoc) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val img = rememberCardArt(store, gameId, face.art, maxPx = ART_PX_EDIT)
    var showMini by remember(face.art) { mutableStateOf(hasOwnMiniRect(face)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && gameId.isNotEmpty()) {
            store.importArt(gameId, uri, ctx.contentResolver)?.let { a ->
                // A new picture invalidates crops taken of the old one.
                onFace(face.copy(art = a, artFieldRect = null, artMiniRect = null))
            }
        }
    }
    val iw = (img?.width ?: 1).toFloat()
    val ih = (img?.height ?: 1).toFloat()
    val fieldRect = face.artFieldRect ?: centreCrop(iw, ih, FIELD_ASPECT)
    val miniRect = face.artMiniRect ?: fieldRect

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Cg.bg)
                .padding(horizontal = 12.dp)
                .padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(face.name.ifEmpty { "Card art" }, color = Cg.ink, fontSize = 15.sp)
                CgButton("done") { onDismiss() }
            }

            if (img == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Cg.surfaceAlt)
                        .pointerInput(Unit) { detectDragGestures { _, _ -> } },
                    contentAlignment = Alignment.Center,
                ) {
                    CgButton("choose an image") { picker.launch("image/*") }
                }
            } else {
                // The picture, contained, with the rectangles over it. The
                // BOX is the drawn image exactly -- a rect's fractions are of
                // the picture, never of whatever space is left around it.
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val d = LocalDensity.current
                    val availW = with(d) { maxWidth.toPx() }
                    val availH = with(d) { maxHeight.toPx() }
                    val scale = minOf(availW / iw, availH / ih)
                    val boxW = iw * scale
                    val boxH = ih * scale
                    Box(
                        Modifier
                            .size(with(d) { boxW.toDp() }, with(d) { boxH.toDp() })
                            .background(Cg.surfaceAlt),
                    ) {
                        ArtLayer(img, null, whole = true)
                        CropBox(
                            rect = fieldRect, boxW = boxW, boxH = boxH, imgW = iw, imgH = ih,
                            aspect = FIELD_ASPECT, ink = FIELD_INK, dashed = false, label = "FIELD",
                        ) { r -> onFace(face.copy(artFieldRect = lockRect(r, iw, ih, FIELD_ASPECT))) }
                        if (showMini) {
                            CropBox(
                                rect = miniRect, boxW = boxW, boxH = boxH, imgW = iw, imgH = ih,
                                aspect = MINI_ASPECT, ink = MINI_INK, dashed = true, label = "MINI",
                            ) { r -> onFace(face.copy(artMiniRect = lockRect(r, iw, ih, MINI_ASPECT))) }
                        }
                    }
                }

                // What each rectangle feeds, at the real tile proportions.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("field", color = FIELD_INK, fontSize = 9.sp)
                        Box(
                            Modifier.width(104.dp).aspectRatio(FIELD_ASPECT)
                                .clip(RoundedCornerShape(4.dp)).background(Cg.surfaceAlt),
                        ) { ArtLayer(img, fieldRect) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("shrunk", color = if (showMini) MINI_INK else Cg.dim, fontSize = 9.sp)
                        Box(
                            Modifier.width(84.dp).aspectRatio(MINI_ASPECT)
                                .clip(RoundedCornerShape(4.dp)).background(Cg.surfaceAlt),
                        ) { ArtLayer(img, miniRect) }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CgButton(if (showMini) "shrunk: own crop" else "shrunk: follows field") {
                        showMini = !showMini
                        onFace(
                            face.copy(
                                artMiniRect = if (showMini) lockRect(fieldRect, iw, ih, MINI_ASPECT) else null,
                            ),
                        )
                    }
                    CgButton("replace") { picker.launch("image/*") }
                    CgButton("remove") {
                        onFace(face.copy(art = null, artFieldRect = null, artMiniRect = null))
                        onDismiss()
                    }
                }
                Text(
                    "drag a rectangle to move it · drag a corner to resize · " +
                        "each stays locked to its tile's shape",
                    color = Cg.dim, fontSize = 10.sp,
                )
            }
        }
    }
}
