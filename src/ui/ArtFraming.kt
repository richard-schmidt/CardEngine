package ccgui

import ccg.ArtRect
import ccg.FaceDoc

// ---------------------------------------------------------------------------
// Card-art cropping. A card is drawn at three sizes, each a different SHAPE,
// so one crop cannot serve them all:
//
//     focused card   420 x 78 dp   (the whole picture, contained -- no crop)
//     field tile     104 x 46 dp   the SOLID rectangle
//     shrunk tile     84 x 26 dp   the DASHED rectangle
//
// Decisions, so they live here rather than inside a composable.
// ---------------------------------------------------------------------------

/** `104 / 46` -- the field tile's art band, from `CgCardTile`. */
const val FIELD_ASPECT = 104f / 46f

/** `84 / 26` -- the shrunk tile's art band (hand, docked lanes, card list). */
const val MINI_ASPECT = 84f / 26f

/** No rectangle may shrink past this fraction of the image: below it the crop
 *  is a few source pixels blown across a whole tile. */
const val MIN_RECT = 0.06f

/**
 * The crop a tile shows when the card has none of its own: centred, and cut to
 * the tile's shape. This is what an uncropped picture has always looked like --
 * it is `ContentScale.Crop`, written down.
 */
fun centreCrop(imgW: Float, imgH: Float, boxAspect: Float): ArtRect {
    if (imgW <= 0f || imgH <= 0f || boxAspect <= 0f) return ArtRect()
    val imgAspect = imgW / imgH
    return if (imgAspect > boxAspect) {
        // Source is wider than the box: full height, bite into the width.
        val w = boxAspect / imgAspect
        ArtRect(x = (1f - w) / 2f, y = 0f, w = w, h = 1f)
    } else {
        val h = imgAspect / boxAspect
        ArtRect(x = 0f, y = (1f - h) / 2f, w = 1f, h = h)
    }
}

/**
 * Force `r` to the tile's shape and back inside the picture, so what the author
 * draws is exactly what the tile shows. `w` leads and `h` follows (a drag has
 * one dominant axis).
 */
fun lockRect(r: ArtRect, imgW: Float, imgH: Float, boxAspect: Float): ArtRect {
    if (imgW <= 0f || imgH <= 0f || boxAspect <= 0f) return r
    var w = r.w.coerceIn(MIN_RECT, 1f)
    var h = (w * imgW) / (boxAspect * imgH)
    if (h > 1f) { h = 1f; w = (boxAspect * imgH) / imgW }
    if (w > 1f) { w = 1f; h = (w * imgW) / (boxAspect * imgH) }
    return ArtRect(
        x = r.x.coerceIn(0f, (1f - w).coerceAtLeast(0f)),
        y = r.y.coerceIn(0f, (1f - h).coerceAtLeast(0f)),
        w = w,
        h = h,
    )
}

/** Move a rect by a fraction of the image, staying inside it. */
fun moveRect(r: ArtRect, dx: Float, dy: Float): ArtRect = r.copy(
    x = (r.x + dx).coerceIn(0f, (1f - r.w).coerceAtLeast(0f)),
    y = (r.y + dy).coerceIn(0f, (1f - r.h).coerceAtLeast(0f)),
)

/**
 * Resize from one corner. `dx` is the drag along the width in image fractions;
 * `east`/`south` say which corner is being pulled, so the OPPOSITE corner is
 * the one that stays put.
 */
fun resizeRect(r: ArtRect, dx: Float, east: Boolean, south: Boolean, imgW: Float, imgH: Float, boxAspect: Float): ArtRect {
    val right = r.x + r.w
    val bottom = r.y + r.h
    val wanted = if (east) r.w + dx else r.w - dx
    val locked = lockRect(r.copy(w = wanted), imgW, imgH, boxAspect)
    val x = if (east) r.x else right - locked.w
    val y = if (south) r.y else bottom - locked.h
    return ArtRect(
        x = x.coerceIn(0f, (1f - locked.w).coerceAtLeast(0f)),
        y = y.coerceIn(0f, (1f - locked.h).coerceAtLeast(0f)),
        w = locked.w,
        h = locked.h,
    )
}

/**
 * Which crop a drawing of `face` uses. The shrunk tile INHERITS the field crop
 * until given its own. Null = none chosen; the renderer uses [centreCrop].
 */
fun rectFor(face: FaceDoc, compact: Boolean): ArtRect? =
    if (compact) face.artMiniRect ?: face.artFieldRect else face.artFieldRect

/** True when this face's shrunk tile is cropped separately from its field one. */
fun hasOwnMiniRect(face: FaceDoc): Boolean = face.artMiniRect != null

/** What a rendered card needs about its picture: the file and its crops. */
data class CardArtRef(val file: String, val field: ArtRect?, val mini: ArtRect?) {
    /** The crop this drawing should use; null = a centred crop of its shape. */
    fun rect(compact: Boolean): ArtRect? = if (compact) mini ?: field else field
}

/**
 * Card key -> its art, built once from the doc (only cards with art appear).
 * Joined on [ccg.CardDoc.key], never the display name.
 */
fun artRefs(doc: ccg.GameDoc): Map<String, CardArtRef> =
    doc.cards.mapNotNull { c ->
        val f = c.faces.firstOrNull() ?: return@mapNotNull null
        val file = f.art?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        c.key() to CardArtRef(file, f.artFieldRect, f.artMiniRect)
    }.toMap()
