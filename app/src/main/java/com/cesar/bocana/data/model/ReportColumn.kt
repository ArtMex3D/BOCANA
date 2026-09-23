package com.cesar.bocana.data.model

enum class ReportColumn(val title: String) {
    PRODUCT_NAME("Producto"),
    STOCK_C04("C04"),
    STOCK_MATRIZ("Matriz"),
    STOCK_TOTAL("Total"),
    CONSUMO_SEMANAL("Consumo/sem"),
    CONSUMO_MENSUAL("Consumo/mes"),
    SE_AGOTA_EN("Se agota"),
    ULTIMA_ACTUALIZACION("Actualizado"),

    // Se conservan para no romper configuraciones antiguas que aún puedan existir.
    CONSUMO("Consumo"),
    UNIT("Unidad")
}
