package com.cesar.bocana.ui.adapters

import com.cesar.bocana.data.model.StockLot

sealed class GroupableListItem {
    data class HeaderItem(val title: String, val groupId: String = title) : GroupableListItem()
    data class SubLoteItem(val stockLot: StockLot) : GroupableListItem()

    val id: String
        get() = when (this) {
            is HeaderItem -> "header_$groupId"
            is SubLoteItem -> "sublote_${stockLot.id}"
        }
}
