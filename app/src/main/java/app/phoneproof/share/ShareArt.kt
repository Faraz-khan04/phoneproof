package app.phoneproof.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import app.phoneproof.R
import app.phoneproof.ui.components.UiStatus
import app.phoneproof.ui.screens.ReportUi
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/** QR codes and the shareable report card (1080 × 1350, the 4:5 size listings show uncropped). */
object ShareArt {

    fun qr(text: String, size: Int = 720, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ecc, EncodeHintType.MARGIN to 2))
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /** Draws the card with the same tokens as the app (Signal blocks). */
    fun card(context: Context, ui: ReportUi, link: String?): Bitmap {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val regular = ResourcesCompat.getFont(context, R.font.nunito_sans_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.nunito_sans_bold) ?: Typeface.DEFAULT_BOLD
        val heavy = ResourcesCompat.getFont(context, R.font.nunito_sans_extrabold) ?: Typeface.DEFAULT_BOLD
        fun paint(tf: Typeface, sizePx: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = sizePx; this.color = color }

        val canvas = 0xFFF2F6F7.toInt(); val ink = 0xFF131C20.toInt(); val muted = 0xFF445258.toInt()
        val fill = when (ui.verdict) {
            UiStatus.Pass -> 0xFF05773B.toInt(); UiStatus.Warn -> 0xFF9C6306.toInt()
            UiStatus.Fail -> 0xFFC22826.toInt(); UiStatus.None -> 0xFF5B686E.toInt()
        }
        c.drawColor(canvas)

        // Verdict block (the one loud thing)
        val blockBottom = 520f
        c.drawRoundRect(RectF(0f, -80f, w.toFloat(), blockBottom), 64f, 64f, Paint().apply { color = fill; isAntiAlias = true })
        c.drawText(ui.verdictKicker.uppercase(), 72f, 132f, paint(bold, 34f, Color.WHITE).apply { letterSpacing = 0.04f })
        c.drawText(ui.verdictTitle, 72f, 236f, paint(heavy, 88f, Color.WHITE).apply { letterSpacing = -0.02f })
        drawWrapped(c, ui.verdictBody, 72f, 312f, w - 144f, paint(regular, 38f, Color.WHITE), 52f, 4)

        // Summary counts
        val passed = ui.tests.count { it.status == UiStatus.Pass }
        val issues = ui.tests.count { it.status == UiStatus.Warn || it.status == UiStatus.Fail }
        val skipped = ui.tests.count { it.status == UiStatus.None }
        val cardTop = 572f
        c.drawRoundRect(RectF(48f, cardTop, w - 48f, cardTop + 180f), 48f, 48f, Paint().apply { color = Color.WHITE; isAntiAlias = true })
        listOf(Triple("$passed", "Passed", 0xFF05773B.toInt()), Triple("$issues", "Check this", 0xFF9C6306.toInt()), Triple("$skipped", "Not tested", muted))
            .forEachIndexed { i, (num, lbl, col) ->
                val x = 96f + i * 320f
                c.drawText(num, x, cardTop + 98f, paint(heavy, 72f, col))
                c.drawText(lbl, x, cardTop + 148f, paint(bold, 30f, muted))
            }

        // Issues first, then a few passes
        var y = cardTop + 260f
        val rows = ui.tests.sortedBy { if (it.status == UiStatus.Pass) 1 else 0 }.take(6)
        rows.forEach { t ->
            val col = when (t.status) { UiStatus.Pass -> 0xFF05773B.toInt(); UiStatus.Warn -> 0xFF9C6306.toInt(); UiStatus.Fail -> 0xFFC22826.toInt(); UiStatus.None -> 0xFFB7C1C5.toInt() }
            c.drawCircle(84f, y - 12f, 16f, Paint().apply { color = col; isAntiAlias = true })
            c.drawText(t.name, 124f, y, paint(bold, 36f, ink))
            c.drawText(t.source, 124f, y + 40f, paint(regular, 28f, muted))
            y += 92f
        }

        // QR + footer
        val footerTop = h - 250f
        if (link != null) {
            val q = qr(link, 200)
            c.drawBitmap(q, w - 72f - 200f, footerTop, null)
            c.drawText("Scan to verify", 72f, footerTop + 70f, paint(heavy, 44f, ink))
            c.drawText(link.removePrefix("https://"), 72f, footerTop + 120f, paint(regular, 28f, muted))
        } else {
            c.drawText("Verify in the PhoneProof app", 72f, footerTop + 70f, paint(heavy, 44f, ink))
        }
        c.drawText("Self-test report, signed by this device. Not a certification.", 72f, h - 56f, paint(regular, 26f, muted))
        return bmp
    }

    private fun drawWrapped(c: Canvas, text: String, x: Float, y0: Float, maxW: Float, p: Paint, lineH: Float, maxLines: Int) {
        var y = y0; var line = ""; var lines = 0
        for (word in text.split(" ")) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(trial) > maxW && line.isNotEmpty()) {
                c.drawText(line, x, y, p); y += lineH; line = word
                if (++lines == maxLines - 1) break
            } else line = trial
        }
        if (line.isNotEmpty()) c.drawText(line, x, y, p)
    }

    /** Writes [bmp] to cache and opens the share sheet. */
    fun shareImage(context: Context, bmp: Bitmap, text: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "phoneproof-report.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
