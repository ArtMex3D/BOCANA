package com.cesar.bocana.data.model

import java.util.Date

data class ReportConfig(
    val productIds: List<String>,
    val columns: List<ReportColumn>,
    val dateRange: Pair<Date, Date>?, // Null si no se calcula consumo
    val reportTitle: String = "Reporte de Inventario"
)