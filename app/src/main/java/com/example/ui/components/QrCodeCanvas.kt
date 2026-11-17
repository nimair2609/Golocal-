package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun GolocalQrCodeBox(
    payload: String,
    size: Dp = 180.dp,
    modifier: Modifier = Modifier
) {
    val bitMatrix = remember(payload) {
        runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256, hints)
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(14.dp))
            .padding(10.dp)
            .semantics { contentDescription = "Scannable QR ticket code for $payload" }
            .testTag("bus_ticket_qr_canvas")
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val matrix = bitMatrix ?: return@Canvas
            val w = matrix.width
            val h = matrix.height
            val cellW = this.size.width / w.toFloat()
            val cellH = this.size.height / h.toFloat()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = Color(0xFF0A1D3D),
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW + 0.4f, cellH + 0.4f)
                        )
                    }
                }
            }
        }
    }
}
