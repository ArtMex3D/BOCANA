package com.cesar.bocana.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Configuración global del PDF de traspasos.
 *
 * Firestore es la fuente compartida entre dispositivos y Room conserva la
 * última versión válida para arranque inmediato y funcionamiento sin red.
 */
@Entity(tableName = "transfer_pdf_config")
data class TransferPdfConfig(
    @PrimaryKey
    val id: String = DOCUMENT_ID,
    val titleText: String = DEFAULT_TITLE,
    val headerBackgroundHex: String = DEFAULT_HEADER_BACKGROUND,
    val headerTextHex: String = DEFAULT_HEADER_TEXT,
    val zebraHex: String = DEFAULT_ZEBRA,
    val updatedAtMillis: Long = 0L
) {
    fun normalized(): TransferPdfConfig = copy(
        id = DOCUMENT_ID,
        titleText = titleText.trim().take(MAX_TITLE_LENGTH).ifBlank { DEFAULT_TITLE },
        headerBackgroundHex = normalizeHex(headerBackgroundHex, DEFAULT_HEADER_BACKGROUND),
        headerTextHex = normalizeHex(headerTextHex, DEFAULT_HEADER_TEXT),
        zebraHex = normalizeHex(zebraHex, DEFAULT_ZEBRA)
    )

    companion object {
        const val COLLECTION = "app_config"
        const val DOCUMENT_ID = "transfer_pdf"

        const val DEFAULT_TITLE = "Traspaso Matriz a Congelador"
        const val DEFAULT_HEADER_BACKGROUND = "#37474F"
        const val DEFAULT_HEADER_TEXT = "#FFFFFF"
        const val DEFAULT_ZEBRA = "#E8F1FF"
        const val MAX_TITLE_LENGTH = 80

        const val LEGACY_PREFS_NAME = "PdfColorConfig"
        const val LEGACY_HEADER_BACKGROUND = "headerBgColor"
        const val LEGACY_HEADER_TEXT = "headerFontColor"
        const val LEGACY_ZEBRA = "zebraColor"

        private val HEX_PATTERN = Regex("^#[0-9A-F]{6}$")

        fun defaults(): TransferPdfConfig = TransferPdfConfig()

        fun normalizeHex(value: String?, fallback: String): String {
            val raw = value.orEmpty().trim().uppercase()
            val candidate = when {
                raw.matches(Regex("^[0-9A-F]{6}$")) -> "#$raw"
                raw.matches(Regex("^#[0-9A-F]{6}$")) -> raw
                raw.matches(Regex("^#[0-9A-F]{8}$")) -> "#${raw.takeLast(6)}"
                else -> fallback
            }
            return if (HEX_PATTERN.matches(candidate)) candidate else fallback
        }
    }
}
