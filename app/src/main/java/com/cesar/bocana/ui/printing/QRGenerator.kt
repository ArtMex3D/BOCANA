package com.cesar.bocana.ui.printing

import android.graphics.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QRGenerator {

    /**
     * Genera un Bitmap de un código QR con una letra opcional en el centro.
     * @param text El contenido a codificar en el QR.
     * @param size El ancho y alto del QR en píxeles.
     * @param centerChar La letra opcional para superponer en el centro (ej. 'S' o 'M').
     * @param color El color de los módulos del QR.
     * @return Un Bitmap del código QR, o null si ocurre un error.
     */
    fun generate(
        text: String,
        size: Int,
        centerChar: Char? = null,
        color: Int = Color.BLACK
    ): Bitmap? {
        if (text.isEmpty() || size <= 0) return null

        try {
            val hints = hashMapOf<EncodeHintType, Any>().apply {
                this[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H // Alta corrección para soportar la letra en el centro
                this[EncodeHintType.CHARACTER_SET] = "UTF-8"
                this[EncodeHintType.MARGIN] = 1
            }

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height

            // Usar RGB_565 para ahorrar memoria (la mitad que ARGB_8888)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) color else Color.WHITE)
                }
            }

            // Si se especifica una letra central, la añadimos
            return if (centerChar != null) {
                addCenterCharacter(bmp, centerChar, color)
            } else {
                bmp
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun addCenterCharacter(qrBitmap: Bitmap, char: Char, mainColor: Int): Bitmap {
        val resultBitmap = qrBitmap.copy(qrBitmap.config, true)
        val canvas = Canvas(resultBitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val centerX = qrBitmap.width / 2f
        val centerY = qrBitmap.height / 2f

        // Dibujar un círculo blanco de fondo para que la letra sea legible
        val radius = qrBitmap.width / 5.5f // Radio del círculo
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, radius, paint)

        // Dibujar un borde delgado alrededor del círculo
        paint.color = mainColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(centerX, centerY, radius, paint)

        // Configurar el paint para la letra
        paint.color = mainColor
        paint.style = Paint.Style.FILL
        paint.textSize = radius * 1.5f // Tamaño del texto relativo al radio
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER

        // Centrar el texto verticalmente en el círculo
        val textBounds = Rect()
        paint.getTextBounds(char.toString(), 0, 1, textBounds)
        val textY = centerY - textBounds.exactCenterY()

        canvas.drawText(char.toString(), centerX, textY, paint)

        return resultBitmap
    }
}