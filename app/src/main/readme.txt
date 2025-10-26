Plan de Desarrollo Actualizado errores
Este documento describe las próximas fases de desarrollo, organizadas por prioridad. Incluye tareas críticas pendientes, nuevas funcionalidades y mejoras de usabilidad.

PRIORIDADES MÁXIMAS (Tareas Críticas y Nuevas Funcionalidades)
Paso 1 (Pendiente): Reparar Interacción en Diálogo de Traspasos
Objetivo: Solucionar el problema de clic en el diálogo "Seleccionar Lotes" cuando el modo "Desglose Manual" está activado.

Problema Actual: El diálogo para introducir la cantidad solo aparece si se presiona el texto del lote, pero no si se presiona el recuadro del input.

Acción Requerida: Modificar el adaptador LoteCheckboxAdapter.kt para que toda el área de la fila (incluyendo el recuadro de cantidad) sea un único objetivo de clic, garantizando que la acción de editar se dispare sin importar dónde se presione.

Paso 2 (Nuevo): Implementar Notificaciones Avanzadas (En Segundo Plano)
Objetivo: Crear un sistema de notificaciones que alerte al usuario sobre tareas pendientes importantes, incluso si la aplicación está cerrada.

Problema Actual: Las notificaciones solo se generan cuando la app está abierta y activa.

Plan de Implementación:

Tecnología: Se utilizará WorkManager, la solución recomendada por Android para tareas programadas y garantizadas en segundo plano.

Lógica: Se programará una tarea para que se ejecute periódicamente (por ejemplo, una o dos veces al día).

Verificaciones: Durante su ejecución, la tarea consultará directamente a Firestore para verificar condiciones críticas como:

Productos con stock por debajo del mínimo.

Devoluciones que sigan en estado "PENDIENTE".

Items en "Pendiente de Empacar" que hayan superado un tiempo límite (ej. 3 días).

Acción: Si se cumple alguna de estas condiciones, el sistema generará una notificación local en el dispositivo, alertando al usuario para que abra la app y tome acción.

Paso 3 (Pendiente y Detallado): Sistema de Trazabilidad para Lotes a Granel
Objetivo: Diferenciar visualmente en toda la aplicación los lotes que fueron recibidos a granel y están pendientes de ser empacados.

Problema Actual: No hay una forma clara de identificar y dar seguimiento a estos lotes, lo que puede causar confusiones en traspasos o salidas.

Plan de Implementación:

Modelo de Datos: Se utilizará el campo booleano isPackaged que ya existe en el modelo StockLot.

Registro de Compra: Al registrar una compra "A Granel", el nuevo lote se guardará en Firestore con el campo isPackaged establecido en false.

Distinción Visual: Se modificarán los adaptadores (LotSelectionAdapter, ProductAdapter, etc.) para que, si un lote tiene isPackaged = false, se muestre con un color de fondo distinto o un ícono de advertencia (ej. 📦).

Proceso de Empaque: En la pantalla "Pendiente de Empacar", al marcar un item como "Empacado", el sistema actualizará el documento del lote original en Firestore, cambiando isPackaged a true.

Sincronización Automática: Gracias a los listeners en tiempo real que ya tiene la app, en cuanto el lote se actualice, todas las pantallas reflejarán su nuevo estado "empacado" sin necesidad de una Cloud Function.

PRIORIDADES SECUNDARIAS (Mejoras de Usabilidad y Correcciones)
Tarea A: Corregir Bug en Impresión de Etiquetas con "Pzas"
Problema: Al generar una etiqueta, si la unidad es "Pzas" (o cualquier texto más largo que "Kg"), el texto no se imprime o se corta.

Causa Probable: El TextView en el archivo de layout de la etiqueta (layout_label_detailed_v2.xml) tiene un tamaño fijo que no se ajusta automáticamente a textos más largos.

Solución: Se revisará el layout de la etiqueta y se aplicarán propiedades de autoajuste de texto (app:autoSizeTextType="uniform") para asegurar que el tamaño de la fuente se reduzca dinámicamente si el texto es muy largo, garantizando que siempre sea visible.

Tarea B: Mejorar Diálogo de Compras con Selector de Unidades
Problema: El campo "Tipo de Empaque" es un campo de texto libre, lo que puede llevar a inconsistencias.

Solución:

Modificar Layout: Se cambiará el TextInputEditText por un AutoCompleteTextView en dialog_add_compra.xml, similar al campo de "Proveedor".

Añadir Lógica: En AddCompraDialogFragment.kt, se creará una lista predefinida con las unidades más comunes ("Cajas", "Costales", "Bolsas", "Piezas", "Kg", "Litros") y se usará para poblar el nuevo menú desplegable.

Este plan nos da una hoja de ruta clara para las próximas mejoras. Las tareas de Prioridad Máxima son las más complejas y con mayor impacto, mientras que las de Prioridad Secundaria son mejoras de calidad de vida más rápidas de implementar.




///////////////////////////////////////////////////////////
plan nuevo octubre

Plan Maestro de Desarrollo: Titán v1

Filosofía Central: Sugerencia Inteligente, Control Manual

Este plan representa una evolución estratégica de la aplicación, transformándola de una herramienta de registro a un asistente de inventario proactivo. La nueva filosofía se basa en dos pilares:

Sugerencia Inteligente: La aplicación utilizará reglas de negocio (categorías, fechas, stock ideal) para sugerir las acciones más lógicas y eficientes.

Control Manual Total: El usuario siempre tendrá la última palabra. La flexibilidad para editar cantidades, seleccionar lotes específicos y ajustar la planificación a la realidad física del almacén es la máxima prioridad.

Fase 1: El ADN de la Inteligencia - Categorización de Productos

Objetivo: Crear la estructura de datos fundamental que permitirá a la app "entender" las reglas de tu negocio y actuar en consecuencia.

Paso 1.1: Evolución del Modelo de Datos Product

Archivo a Modificar: main/java/com/cesar/bocana/data/model/Product.kt

Acción Detallada: Se añadirán dos nuevos campos al data class Product para almacenar la categoría y la jerarquía de reglas.

// Dentro de la clase Product
val categoria: String = "FIJO", // Valores por defecto: "FIJO", "PESCADO_GRANDE", "PESCADO_CHICO"
val productoRectorId: String? = null // Almacena el ID del producto que rige a esta categoría (ej. el ID de 'H.O.')


Paso 1.2: Integración en la Interfaz de Creación y Edición

Archivos a Modificar:

main/java/com/cesar/bocana/ui/products/AddEditProductFragment.kt

main/res/layout/fragment_add_edit_product.xml

Acción Detallada:

En el layout de edición/creación, se añadirán dos menús desplegables (AutoCompleteTextView dentro de TextInputLayout).

Selector de Categoría: Un menú para asignar la categoría: "Fijo", "Pescado Grande" o "Pescado Chico".

Selector de Producto Rector: (Tu pregunta respondida aquí) Este menú se habilitará solo si la categoría es "Pescado Chico". Se poblará con tu lista completa de productos para que puedas seleccionar libremente cuál es el producto que manda (ej. "H.O.", "Huachinango O.", o cualquier otro que definas). La app guardará su ID.

La lógica en el Fragment se actualizará para guardar estos nuevos campos en Firestore.

Paso 1.3: Script de Migración de Datos Existentes (Ejecución Única)

Nuevos Archivos:

main/java/com/cesar/bocana/ui/migration/CategoryMigrationFragment.kt

main/res/layout/fragment_category_migration.xml

Archivo a Modificar: main/java/com/cesar/bocana/ui/masopciones/MoreOptionsFragment.kt

Acción Detallada:

Se agregará un nuevo botón en "Más Opciones" llamado "Mantenimiento de Categorías".

Este llevará al nuevo CategoryMigrationFragment, que mostrará una lista de todos tus productos actuales.

Cada fila permitirá asignar rápidamente la categoría y, si aplica, el productoRectorId a través de menús desplegables.

Un botón "Guardar Cambios" utilizará una escritura por lotes (WriteBatch) de Firestore para actualizar todos tus productos de forma masiva y segura.

Fase 2: Limpieza y Simplificación del Flujo "A Granel"

Objetivo: Eliminar la complejidad obsoleta y alinear la funcionalidad de empaque con la nueva filosofía de flexibilidad.

Paso 2.1: Limpieza de Código Obsoleto (Empaque Mixto)

Acción Detallada: Se procederá a la eliminación completa de la funcionalidad de "empaque mixto" para simplificar la base del código.

Archivos a Eliminar:

main/java/com/cesar/bocana/ui/dialogs/EmpaqueMixtoDialogFragment.kt

main/res/layout/dialog_empaque_mixto.xml

main/java/com/cesar/bocana/ui/adapters/EmpaqueMixtoAdapter.kt

main/res/layout/item_empaque_mixto_seleccion.xml

main/java/com/cesar/bocana/ui/dialogs/ConversionMixtaDialogFragment.kt

main/res/layout/dialog_conversion_mixta.xml

main/java/com/cesar/bocana/ui/adapters/ConversionMixtaAdapter.kt

main/res/layout/item_conversion_mixta_seleccion.xml

Funciones a Eliminar/Refactorizar:

En LotMigrationViewModel.kt: Se eliminará la función convertMixtoVirtualLots.

En StockLot.kt: El campo subLotesMixtos quedará obsoleto y podrá ser eliminado del modelo.

En AppDatabase.kt y Converters.kt: Se eliminarán las referencias y conversores para SubLoteMixto.

Paso 2.2: Refactorización del Diálogo de Empaque

Archivos Clave:

main/java/com/cesar/bocana/ui/dialogs/EmpaqueDialogFragment.kt

main/res/layout/dialog_empaque.xml

Acción Detallada:

En el layout, el RadioGroup se simplificará a solo dos opciones:

"Redondear a Peso Fijo": Mantiene su funcionalidad actual sin cambios. Es para crear lotes de unidades con peso definido (ej. 20 cajas de 4.54 kg).

"Variable (Solo KG)": Esta reemplaza a "Promediar". Ocultará todos los campos de unidades y pesos. Al confirmar, su única función será marcar el lote a granel como isPackaged = true. El lote se convierte en una sola unidad empacada con su peso total en KG.

Fase 3: Traspaso Inteligente v2.1 - El Cerebro en Acción

Objetivo: Implementar la nueva lógica de sugerencias y la flexibilidad manual en el módulo de traspasos.

Paso 3.1: Pantalla "Planificar Traspaso"

ViewModel (PlanificarTraspasoViewModel.kt):

Lógica de Sugerencia Jerárquica: La función cargarPlanDeTraspaso se reescribirá para:

Identificar el Rector: Buscará productos productoRectorId (ej. "H.O.") y calculará su sugerencia de traspaso.

Sugerencia por Rango de Fechas: Si se sugiere mover un lote de H.O. de Mayo 2025, el sistema buscará automáticamente otros productos "PESCADO_CHICO" con lotes en el mismo mes y año y los añadirá a las sugerencias.

Actualización en Tiempo Real: Si el usuario edita el lote de H.O. a uno de Agosto, el ViewModel re-evaluará las sugerencias para los otros "Pescados Chicos" para que coincidan con el nuevo mes.

Interfaz (item_plan_traspaso.xml y PlanTraspasoAdapter.kt):

Productos FIJOS y Redondeados: Se tratarán igual. La sugerencia será en unidades (ej. "20 Cajas") y el sistema calculará los KG. La selección de lotes seguirá siendo 100% editable por el usuario.

Productos GRANEL (Variables): La sugerencia principal será en KG. Se añadirán campos opcionales y manuales para que el usuario anote la cantidad de unidades y el tipo (ej. "5" "Costales") para que aparezca en el PDF.

Flexibilidad del PDF (PlanificarTraspasoFragment.kt y TraspasoPdfGenerator.kt):

Se añadirá un botón "+ Fila Vacía" en la pantalla de planificación.

El PdfGenerator interpretará este "item vacío" y dibujará una fila completamente en blanco en la tabla del PDF para anotaciones manuales.

Paso 3.2: Pantalla "Confirmar Traspaso"

Archivos Clave:

main/java/com/cesar/bocana/ui/traspasos/confirmar/AjusteFinalTraspasoFragment.kt y su ViewModel.

main/res/layout/item_ajuste_final_producto.xml

Acción Detallada:

La interfaz se simplifica: para TODOS los tipos de productos (Fijos, Granel, etc.), el campo principal para la confirmación será la cantidad exacta en KILOGRAMOS que se movió físicamente.

El botón "Seleccionar Lotes" mantendrá su funcionalidad completa, permitiendo al usuario especificar de qué lotes se descontará esa cantidad de KG, anulando cualquier sugerencia FIFO si es necesario.

La lógica de la transacción final solo operará con KG, eliminando cualquier posible error de conversión en la etapa final.

Fase 4: Arquitectura Robusta y Profesional (Sugerencias Integradas)

Objetivo: Blindar la aplicación contra errores de red, inconsistencias de datos y facilitar el mantenimiento futuro.

Paso 4.1: Centralización de Lógica de Escritura (LoteRepository)

Nuevos Archivos: main/java/com/cesar/bocana/data/repository/LoteRepository.kt (nombre sugerido).

Acción Detallada: Se creará esta clase que actuará como el único guardián del inventario. Toda la lógica de runTransaction que modifica StockLot o Product (compras, traspasos, consumos, ajustes) se moverá de los ViewModels a métodos claros en este repositorio (ej. loteRepository.ejecutarTraspasoConfirmado(...)). Esto reduce drásticamente el código duplicado y centraliza la lógica de negocio crítica en un solo lugar seguro.

Paso 4.2: Chequeos de Integridad Previos a la Transacción ("Health Checks")

Archivo a Modificar: main/java/com/cesar/bocana/ui/traspasos/confirmar/AjusteFinalTraspasoViewModel.kt

Acción Detallada: Antes de que el botón "Ejecutar Traspaso" se active, una función verificarIntegridadPlan() consultará silenciosamente a Firestore para confirmar que el plan sigue "PENDIENTE" y que los lotes seleccionados aún tienen stock. Si algo cambió, el botón se deshabilitará y se mostrará un Snackbar informativo, evitando que el usuario inicie una transacción fallida.

Paso 4.3: Gestor de Reintentos Automáticos (RetryManager)

Nuevos Archivos: main/java/com/cesar/bocana/utils/RetryManager.kt (nombre sugerido).

Acción Detallada: Se creará esta utilidad que envolverá las llamadas al LoteRepository. Si una transacción falla por un error de red, reintentará la operación automáticamente con un retardo creciente (1s, 2s, 4s). Esto es 100% seguro gracias a la atomicidad de las transacciones de Firestore y hará que la app se sienta mucho más estable ante micro-cortes de conexión.