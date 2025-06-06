package com.cesar.bocana.utils // Asegúrate que este paquete coincida con la ubicación del archivo

object FirestoreCollections {
    const val PRODUCTS = "products"
    const val SUPPLIERS = "suppliers"
    const val INVENTORY_LOTS = "inventoryLots"
    const val STOCK_MOVEMENTS = "stockMovements"
    const val PENDING_PACKAGING = "pendingPackaging"
    const val PENDING_DEVOLUCIONES = "pendingDevoluciones"
    const val USERS = "users"
    const val WEB_COMMENTS = "webComments"
    const val SEND_NOTIFICATION_QUEUE = "sendNotificationQueue"
}

object ProductFields {
    const val ID = "id"
    const val NAME = "name"
    const val UNIT = "unit"
    const val MIN_STOCK = "minStock"
    const val PROVIDER_DETAILS = "providerDetails"
    const val STOCK_MATRIZ = "stockMatriz"
    const val STOCK_CONGELADOR_04 = "stockCongelador04"
    const val TOTAL_STOCK = "totalStock"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
    const val LAST_UPDATED_BY_NAME = "lastUpdatedByName"
    const val IS_ACTIVE = "isActive"
    const val REQUIRES_PACKAGING = "requiresPackaging"
}

object SupplierFields {
    const val ID = "id"
    const val NAME = "name"
    const val CONTACT_PERSON = "contactPerson"
    const val PHONE = "phone"
    const val EMAIL = "email"
    const val IS_ACTIVE = "isActive"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
}

object StockLotFields {
    const val ID = "id"
    const val PRODUCT_ID = "productId"
    const val PRODUCT_NAME = "productName"
    const val UNIT = "unit"
    const val LOCATION = "location"
    const val SUPPLIER_ID = "supplierId"
    const val SUPPLIER_NAME = "supplierName"
    const val RECEIVED_AT = "receivedAt"
    const val MOVEMENT_ID_IN = "movementIdIn"
    const val INITIAL_QUANTITY = "initialQuantity"
    const val CURRENT_QUANTITY = "currentQuantity"
    const val LOT_NUMBER = "lotNumber"
    const val EXPIRATION_DATE = "expirationDate"
    const val IS_DEPLETED = "isDepleted"
    const val IS_PACKAGED = "isPackaged"
    const val ORIGINAL_LOT_ID = "originalLotId"
    const val ORIGINAL_RECEIVED_AT = "originalReceivedAt"
    const val ORIGINAL_SUPPLIER_ID = "originalSupplierId"
    const val ORIGINAL_SUPPLIER_NAME = "originalSupplierName"
    const val ORIGINAL_LOT_NUMBER = "originalLotNumber"
}

object StockMovementFields {
    const val ID = "id"
    const val TIMESTAMP = "timestamp"
    const val USER_ID = "userId"
    const val USER_NAME = "userName"
    const val PRODUCT_ID = "productId"
    const val PRODUCT_NAME = "productName"
    const val TYPE = "type"
    const val QUANTITY = "quantity"
    const val LOCATION_FROM = "locationFrom"
    const val LOCATION_TO = "locationTo"
    const val REASON = "reason"
    const val STOCK_AFTER_MATRIZ = "stockAfterMatriz"
    const val STOCK_AFTER_CONGELADOR_04 = "stockAfterCongelador04"
    const val STOCK_AFTER_TOTAL = "stockAfterTotal"
}

object PendingPackagingTaskFields {
    const val ID = "id"
    const val PRODUCT_ID = "productId"
    const val PRODUCT_NAME = "productName"
    const val QUANTITY_RECEIVED = "quantityReceived"
    const val UNIT = "unit"
    const val RECEIVED_AT = "receivedAt"
    const val PURCHASE_MOVEMENT_ID = "purchaseMovementId"
    const val SUPPLIER_ID = "supplierId"
    const val SUPPLIER_NAME = "supplierName"
}

object DevolucionPendienteFields {
    const val ID = "id"
    const val PRODUCT_ID = "productId"
    const val PRODUCT_NAME = "productName"
    const val QUANTITY = "quantity"
    const val PROVIDER = "provider"
    const val UNIT = "unit"
    const val REASON = "reason"
    const val REGISTERED_AT = "registeredAt"
    const val USER_ID = "userId"
    const val STATUS = "status"
    const val COMPLETED_AT = "completedAt"
}

object UserFields {
    const val UID = "uid"
    const val EMAIL = "email"
    const val NAME = "name"
    const val ROLE = "role"
    const val IS_ACCOUNT_ACTIVE = "isAccountActive"
    const val FCM_TOKEN = "fcmToken"
}
