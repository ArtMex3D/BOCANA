// plan oficial

Plan de Implementación Titánico v3.1: Guía de Ejecución
Misión: Evolucionar la aplicación hacia un sistema de inventario inteligente, ejecutando una migración de datos segura y mejorando la experiencia de usuario en puntos clave.

FASE 0: FUNDACIÓN Y MIGRACIÓN (El Cimiento)
Objetivo: Establecer el nuevo ADN de los productos y adaptar la base de datos existente para que sea 100% compatible.

Paso 0.1: Modelo de Datos Definitivo.

Acción: Reemplazar Product.kt con la versión final que hemos definido. Esta es la base de todo.

Paso 0.2: Construir el Script de Migración "Mágico".

Acción: Añadir un botón temporal en MoreOptionsFragment llamado "Mantenimiento de Datos".

Lógica del Script (Aclarada): El script NO adivinará pesos. Su única misión es añadir los nuevos campos con valores por defecto seguros (manejoDeStock = "POR_PESO_GRANEL", pesoEquivalenteKg = null, etc.) y corregir tipos de datos (como espacioExtraPDF de número a booleano).

Paso 0.3: Proceso de Despliegue Seguro.

Implementar y probar exhaustivamente el script en el entorno de desarrollo.

Lanzar la actualización a producción.

Ejecutar el script una sola vez desde el botón de mantenimiento.

Configurar manualmente los 2-3 productos especiales (los de "POR_UNIDAD_FIJA").

Eliminar el botón en una futura actualización.

FASE 1: MEJORAS DE UI Y EXPERIENCIA DE USUARIO (UX)
Objetivo: Adaptar la interfaz para la nueva lógica y hacerla más robusta y amigable.

Paso 1.1: Mejorar Pantalla "Añadir/Editar Producto".

Acción: Rediseñar fragment_add_edit_product.xml para incluir los nuevos campos de configuración con su lógica de visibilidad condicional.

Paso 1.2: Potenciar el Diálogo de Compra.

Acción: Modificar la lógica de compra para que, al registrar un producto "Empacado", se pueda configurar su pesoEquivalenteKg directamente, actualizando el "ADN" del producto.

Paso 1.3 (NUEVO): Implementar Edición por Pulsación Larga.

Acción: Modificar ProductAdapter.kt.

Cambiar el onItemClicked a un onItemLongClickListener para la navegación a la pantalla de edición.

Un clic normal ya no hará nada, previniendo ediciones accidentales.

Paso 1.4 (NUEVO): Añadir Mensaje de Ayuda.

Acción: Modificar AddEditProductFragment.kt.

Después de guardar un producto nuevo con éxito, mostrar un Toast o Snackbar informativo que diga: "Producto guardado. Para editarlo, mantén presionado el item en la lista."

FASE 2: EL MÓDULO DE TRASPASOS INTELIGENTE
Objetivo: Construir el núcleo del nuevo sistema de traspasos.

Paso 2.1: Pantalla de Configuración de Traspasos.

Acción: Crear el nuevo fragmento en "Más Opciones" con la lista de productos para configurar stockIdealC04enKg y las opciones de PDF. Implementar el Drag & Drop para ordenTraspaso.

Paso 2.2 (NUEVO): Crear Diálogo de Carga Reutilizable.

Acción: Diseñar un DialogFragment simple que muestre la animación Lottie de "cargando". Este diálogo se llamará antes de operaciones pesadas (como la confirmación del traspaso) y se cerrará al finalizar.

Paso 2.3: Pantalla de "Planificar Traspaso".

Acción: Construir la interfaz que calcula y muestra las sugerencias de traspaso en las unidades del usuario, permite la edición y la selección de lotes.

Paso 2.4: Generación del PDF de Trabajo.

Acción: Implementar la lógica en PdfGenerator.kt para crear el PDF horizontal basado en la planificación, guardando el plan en una nueva colección traspasos_planificados en Firestore con estado "PENDIENTE".

Paso 2.5: Pantalla de "Confirmar Traspaso".

Acción: Construir la interfaz que lee los planes "PENDIENTES" y permite al usuario ingresar las cantidades finales (en unidades o en Kg, según corresponda).

Paso 2.6: La Transacción Atómica de Confirmación.

Acción: Implementar la lógica del botón "Ejecutar Traspaso" dentro de una runTransaction de Firestore para garantizar la integridad de los datos. Mostrar el diálogo de carga Lottie durante esta operación.


//////////////////////
ideas anteriores pero detalladas para agregar a mi plan principales
///////////////////////


// idea 1

Paso 1: Evolucionar el Modelo Product.kt
Esta es la base de todo el sistema.

Archivo a modificar: main/java/com/cesar/bocana/data/model/Product.kt

Acción: Reemplazar la data class Product con esta estructura mejorada.

Kotlin

// main/java/com/cesar/bocana/data/model/Product.kt

@Parcelize
@Entity(...)
@TypeConverters(...)
data class Product(
    @PrimaryKey @DocumentId val id: String = "",
    val name: String = "",
    val unit: String = "Kg", // Unidad de inventario SIEMPRE será Kg.

    // --- NUEVOS CAMPOS PARA TRASPASOS INTELIGENTES ---
    val tipoDeEmpaque: String = "GRANEL", // Opciones: "GRANEL", "PESO_FIJO"
    val unidadDeEmpaque: String? = null, // "Caja", "Costal", "Bolsa", etc.
    val pesoPorUnidad: Double? = null, // Peso exacto para PESO_FIJO o PROMEDIO para GRANEL
    val stockIdealC04: Double = 0.0, // Stock objetivo en C-04, siempre en Kg
    val ordenTraspaso: Int = 999, // Orden en la lista de traspasos (menor = más arriba)
    val modoManualPDF: Boolean = false, // Imprime campos en blanco en el PDF
    val espacioExtraPDF: Boolean = false, // Da más altura a la fila en el PDF
    // --- FIN NUEVOS CAMPOS ---

    // --- CAMPOS EXISTENTES (se mantienen) ---
    val minStock: Double = 0.0, // Este es el stock mínimo GENERAL
    val stockMatriz: Double = 0.0,
    val stockCongelador04: Double = 0.0,
    val totalStock: Double = 0.0,
    // ...resto de campos existentes...
) : Parcelable {
    // ... constructor vacío ...
}
Paso 1.2: Potenciar la Pantalla de Compra y Empaque
Tu idea de conectar "Pendiente de Empacar" con la configuración del producto es excelente. Así es como funcionará:

Archivo a modificar (Lógica de Compra): main/java/com/cesar/bocana/ui/products/ProductListFragment.kt (método showAddCompraDialog).

Al seleccionar "Empacado" en el diálogo de compra: El diálogo se expandirá para preguntar:

"Unidad de Empaque" (ej. "Caja", usando un AutoCompleteTextView).

"Peso Fijo por Unidad" (ej. "4.54", usando un TextInputEditText).

"Cantidad en Unidades" (ej. "20").

Lógica: La "Cantidad NETA (Kg)" se autocalculará (20 * 4.54 = 90.8 Kg) y se bloqueará. Al guardar, esta información se usará para la transacción Y para actualizar los campos tipoDeEmpaque, unidadDeEmpaque y pesoPorUnidad del producto si es la primera vez que se define.

Al seleccionar "A Granel": El flujo sigue como hasta ahora. La magia ocurre después.

Archivo a modificar (Lógica de Empaque): main/java/com/cesar/bocana/ui/packaging/PackagingFragment.kt (método onMarkPackagedClicked).

Al hacer clic en "Empacado": Se abrirá un nuevo diálogo que preguntará:

"Unidad de Empaque Final" (ej. "Costal").

"Peso Promedio Estimado por Unidad" (ej. "28.5").

Acción al confirmar:

Se actualiza el documento del Producto en Firestore con los nuevos unidadDeEmpaque y pesoPorUnidad (promedio).

Se elimina la PendingPackagingTask.

Fase 2: El Nuevo Módulo de Traspasos
Objetivo: Construir la interfaz y la lógica para planificar, imprimir y confirmar traspasos.

Paso 2.1: La Nueva Interfaz de Configuración
Nuevo Fragmento: main/java/com/cesar/bocana/ui/configuracion/ConfiguracionTraspasoFragment.kt

Layout: main/res/layout/fragment_configuracion_traspaso.xml

Ruta en la App: Menú > Más Opciones > Ajustes de Sistema > Configuración de Traspasos.

Descripción de la Interfaz:

Tema: Fondo gris oscuro (#212121), texto blanco.

Toolbar: Título "Configuración de Traspasos".

Contenido: Un RecyclerView que mostrará la lista de todos los productos activos.

Cada Fila (Item del RecyclerView):

Un icono de "arrastrar" (ImageView con drag_handle).

El nombre del producto.

Un icono de flecha para expandir/colapsar (ImageView).

Vista Expandida (al tocar la fila):

TextInputLayout para "Stock Ideal en C-04 (Kg)".

SwitchMaterial para "Modo Manual en PDF".

SwitchMaterial para "Espacio Extra en PDF".

Funcionalidad:

ItemTouchHelper: Se adjuntará al RecyclerView para habilitar el drag-and-drop. Al soltar un item, se actualizará el campo ordenTraspaso de los productos afectados en Firestore.

Los cambios en los EditText y Switches se guardarán en Firestore al perder el foco o al cambiar de estado.

Paso 2.2: La Pantalla de Planificación de Traspasos
Nuevo Fragmento: main/java/com/cesar/bocana/ui/traspasos/PlanificarTraspasoFragment.kt

Layout: main/res/layout/fragment_planificar_traspaso.xml

Descripción de la Interfaz:

Fecha: Un Button en la parte superior que muestra la fecha actual y que, al tocarlo, abre un DatePicker.

Lista de Productos: Un RecyclerView que muestra los productos ordenados por ordenTraspaso.

Cada Fila (Item del RecyclerView):

CheckBox: Para incluir/excluir del PDF. Desactivado por defecto si no hay sugerencia.

TextView: Nombre del producto.

TextView: "Lotes Sugeridos: [lote1, lote2]". (Inicialmente sugerido por FIFO).

TextInputLayout con EditText: "Cantidad a Mover". Pre-llenado con la sugerencia en unidades de empaque (ej. "3 Cajas"). El usuario puede editarlo.

TextView (pequeño, debajo): "Impacto: Quedarán X Kg en Matriz".

Inteligencia en Acción:

El TraspasoViewModel llamará al AnalyticsManager para obtener la sugerencia en Kg.

Luego, usará la función convertirKgAUnidades para mostrar la sugerencia en el formato correcto (ej. 3 Cajas en lugar de 90 Kg).

El TextWatcher en el EditText de cantidad recalculará y mostrará el impacto en el stock de Matriz en tiempo real.

Paso 2.3: La Pantalla de Confirmación de Traspasos
Nuevo Fragmento: main/java/com/cesar/bocana/ui/traspasos/ConfirmarTraspasoFragment.kt

Layout: main/res/layout/fragment_confirmar_traspaso.xml

Descripción de la Interfaz:

Una lista de "Planes de Traspaso Pendientes" (los que se generaron desde la pantalla de planificación).

Al seleccionar un plan, se muestra una vista detallada con la lista de productos que se incluyeron en el PDF.

Cada Fila:

Nombre del producto.

Para PESO_FIJO: Un EditText que pide la cantidad real en unidadDeEmpaque (ej. Cajas).

Para GRANEL: Un EditText que pide la cantidad real en Kg.

Un botón para "Seleccionar Lotes" si los lotes reales fueron diferentes a los sugeridos.

La Magia Final:

El botón "Confirmar y Ejecutar Traspaso" recolectará toda la información final.

Internamente, convertirá todo a Kg.

Ejecutará una transacción de Firestore (runTransaction) que hará lo siguiente de forma atómica (o todo o nada):

Para cada producto en el plan:

Leerá los documentos de los lotes a afectar.

Leerá el documento del producto.

Calculará los nuevos stocks.

Actualizará los currentQuantity de los lotes.

Actualizará stockMatriz y stockCongelador04 del producto.

Creará un StockMovement único para ese traspaso, guardando los lotes afectados, la cantidad en Kg, etc.

Actualizará el estado del documento en traspasos_planificados a CONFIRMADO.

Este plan detallado te proporciona una guía clara para cada paso del desarrollo, integrando tus ideas en la estructura existente de la app y preparando el camino para futuras mejoras de IA.



// idea numero 2



el layout estaria en el menu principal y proveedores lo vamos a mover a more option fragment, ya que traspasos seria una actividad primordial,
 digamos actualizo c04 y doy clic en traspasos, entro al layout se abre a ventana con fecha de hoy, sugerente por si deseo que el traspaso se 
haga mañana, abajo comenzaria el  listado de productos basado en matriz stock y basado al historial inteligente de stock o traspaso continuo, 
podemos darle menos carga a la app, primero configuro ese layout, en moreopcion fragment deberia tener la opcion ajustes de sistema en donde 
configuramos primero algunos detalles minimos de ese layout traspaso, por ejemplo al entrar a ese layout de sistema, entro y hay mas opciones
 en donde selecciono traspasos, ahi se abre ese layout y me muestra una configuracion inicial, stock ideal de c04, me muestra todo los productos
 existentes creados basados en la coleccion products, de ahi tomaria todos los que esten en true /activos ejemplo me dice atun y alado hay un 
contenedor en donde pongo el stock minimo que tendra el c04 digamos ala semana  debe tener 100kg luego salmon etc etc  asi va la lista hasta 
llegar al ultimo producto

una vez asigno esa cantidad hay una seccion que diga dividir en (ratio seleccion unica) 1 2 3  esto significa que ese lote lo va a dividir
 en salidas ala semana, si pongo 100kg de atun en c04 es el stock minimo existente lo divide en 50 50 ese seria el sugerente basado en lo 
que hay en c04 , si hay 80kg en c 04 me sugeriria traspasos 20kg el dia actual que entre a la seccion traspasos, bueno continuando con 
sistema ajuste, deberia dejarme ordenar los productos manualmente para que asi se muestren digamos quiero que atun este arriba de macuil 
pero debajo de salmon, si le doy orden ascendente o descendente no va a encajar como deseo, poder seleccionar por medio de un ratio si 
quiero que el formato zebra cambie de colores tenues mensualmente, colores aleatorios pero que constratan con el negro de las letras pero
 visible la division de la zebra, no quiero un color melon que casi es blanco, colores melon pero mas fuerte quizas, poder seleccionar 
quizas el nivel del color si es amarillo no vaya a ser un amarillo intenso que lastime la vista pero no un amarillo casi blanco, un amarillo
 intermedio como mostaza, es ejemplo que contrasta con el negro de las letras, permitiria seleccionar celdas que en realidad serian espacios,
 habria un ratio alado o debajo del producto que al seleccionar edito espacios sin seleccionar se imprime normal con sus espacios normales, 
por ejemplo si selecciono marlin, este lo dejo en normal pero si elecciono lengua en este seleccionaria un espacio mas ancho, el doble de el
 normal para poder anotar mas cantidades manuales, ese seria la configuracion para ayudar a que funcione la seccion traspasos. ahora en 
traspasos en ese layout seria generar un pdf, pero me mostraria como dije fecha arriba y sugerente la de hoy y poderla cambiar, despues 
comienza la tabla, en donde de forma horizontal muestra fecha de lote, este seria el o los lotes que se deben sacar , por ejemplo quiero
 sacar 200 kilos y queda 50kilos de un lote y 150 los saca de otro, debe mostrar los dos lotes,  luego comienza en forma de lista vertical
 los productos, ordenados en la forma que los deje en ajustes con formato cebra, este productos debe ser color resaltado en negrita y 
ocupando la misma linea, digamos hay dos fecha se dividie en 2 lineas pero al estar en productos es una sola linea se unen para saber 
que es el mismo, la zebra debe tener el formato y asi secuente mente, entonces ese seria el pdf que se imprimia de forma horizontal  
la hoja similar al excel que te envio, por favor el excel estraelo y replicalo aqui en esta pagina para que me digas que entendiste, 
los comentarios ahi anotados son fundamentales para este proyecto... en layout se mostraia despues de la fecha sugerente, el listado 
de productos ordenados como los pedi en la configuracion, mostrar la cantidad sugerente para rellenar el c04 con productos faltantes 
en kilos editable por si quiero cambiarla, atun me sugiere que debo traspasar 30kg pero solo quiero traspasar 15 debe dejarme, aqui 
entro a un dilema y problema enorme con este plan el cual me gustaria me ayudaras a como soluiconarlo, tengo lengua, pacotilla, 
etc etc muchos pesos variables, los cuales aun no llego ala parte de ir ingresando por cajas , costales o piezas, mi idea era al
 momento de empacar el agranel uy le diera en pendiente de empacar en empacado ya listo me desplegara un spam que me dijera deseas
 ingresar cantidades nuevas? es decir no afectan al stock real, solo es como un comparitvo o algo asi , yo meteria o seleccionaria
 del desplegable, cajas, costales, bolsas, piezas, kilos etc  y pondria 32 cajas, pesos variables, y me da una ingreso de cajas, 
aparece un listado donde voy ingresando 32, 25, 28, 23, 22.5, 23.2, etc, etc, etc. ese listado me serviria para el traspaso ya 
que al momento que quiero sacar lengua me sugiere la cantidad necesaria que hace falta para llenar el stock en c04 pero ajjustando
 a costales variables, el problema de eso es que aun no tengo la logica para eso, seria complicado llevar ese control de anotar 
esas cajas costales, seria complicado buscarlos en una pila de 1 o dos toneladas, imagina que el sistema me diga saca 304.25kg 
costales 25.5, 28.3 ,29.6 etc y si no los tengo ala mano estan hasta abajo??? no movere todo para cuadrar el sistema, seria en 
un mundo sencillo super genial que me sugiera que sacar y yo sacara esos justo esos costales y asi va disminuyendo el lote, va 
sacando los costales digamos si son 30 costales y sacamos 10 pues quedan 20 con sus pesos ingresados, pero seria complejo, mucho
 trabajo y no se haria, asi que mi idea en ese caso seria que tuviera el peso sugerente pero en costales  es decir yo debo tener
 un stock de 15 costales en c04 estimado pesos de 25 a 30kg le digo que tengo 300kg en lengua en c04 y mi stock debe ser de 450kg
 basado en analisis deberi tener en matriz con los 300kg unos 10 a 12 costales me faltan 3 costales pero en kilos me faltan 150 
no tengo costales de 50kg asi que serian mas costales pero en este caso yo solo necesito cantidad de costales, pasa lo mismo con
 el HO el robalo la rubia etc .... entonces se me ocurre que tuviera traspasos el listado de productos pero me sugiera el peso y
 yo modifico si quiero, pero siempre haya un ratio el cual selecciono o se guarda en automatico en ajustes de sistema, para que 
ese sea variable es decir si selecciono el ratio y le doy en guardar en configuracion me va a salir todo en blanco, solo el 
nombre del producto, sin proveedor, sin fecha de lote solo los espacios en blanco, sin total sin nada solo el nombre para que
 yo manualmente ponga el peso la cantidad total, los demas que no les selecciono el ratio de variable en peso, me los deberia
 descontar de los lotes correspondientes segun el fifo que seleccione, segun el lote que seleccione, en ese caso me deberia 
dejar seleccionar lotes de atun del cual quiero sacar, de nuevo seria sugerente, muy abajo de esa tabla debe haber 
Verifico mercancia:______________ para la firma de quien verifico y mas a un costado Saco mercancia:____________ el
 nombre de quien saco en este caso ambas lineas son manuales solo se imprimen para ahi ponerle  los datos.
una vez le doy en miprimir pdf deberia, compartir el pdf como hace la seccion reportes y despues mostrar 
en traspasos las secciones traspaso nuevo y confirmar traspaso, entro a confirmar traspaso, me muestra 
esa seccion distinta, con lo que se imprimio en el pdf con todo lo sugerido, lotes fecha etc pero con 
la nueva opcion de yo modificar ciertas cosas, para confirmar traspaso me debe dejar editar todo, en 
este caso, yo me irira hasta abajo y buscaria lengua, seleccionaria el lote y pondria la cantidad que
 salio, aqui no interesa cajas, bolsas, costales etc , solo lo necesario para el inventario y que el 
sistema en automatico haga los traspasos una vez confirmo esos traspasos, le doy en lengua 300kg y me
 despliega lotes con checkbox de cuales salieron si saque 300kg y habian 150 en el lote 25/05/25 y los
 otros 150 los agarra de otro lote seleccionado  30/08/25 que yo seleccione en el checkbox y por orden 
fifo  marcaria deplete el de 150 viejo y agarraria el restante del nuevo justo como hace la seccion traspaso,
 todo y cada uno de los movimientos se debe reflejar como unicos para que aparezacan  en histirial de movimientos
 por lo cual parano enredar al sistema yo creo que deberia tener un boton aceptar cada linea, o aceptar todo y refleje
 todo los movimientos de cada producto como uno por uno para la busqueda avanzada este bien sincronizada.


////////////////////////////////
/////////////SOLUCION///////////
///////////////////////////////


Fase 1:::::

PASO 0
Agregar a pantalla de editar producto, donde esta el stock minimo general, otro boton que diga stockmin c04 editable y sugerente pero una vez se ponga el stock minimo este funciona,
para la configuracion de la fase dos ya que ayudaria con el plan, si no se pone nada, no obliga pero si es bastante necesario para poder ayudar ala fase 2
 

PASO 1.0: Modificar el Modelo Product.kt


 Esta es la piedra angular. Haremos que el producto sea consciente de su propia naturaleza.(hay que refactorizar en base al paso 1.1)

Archivo a modificar: main/java/com/cesar/bocana/data/model/Product.kt

Nuevos Campos Fundamentales:

unidadDeMedida: String - La unidad en la que operas este producto (Kg, Pzas, Cajas, Bolsas, Costales).

manejoDeStock: String - Un campo clave con dos opciones: "POR_PESO" (para productos como la Lengua, donde cada unidad es diferente)
 o "POR_UNIDAD_FIJA" (para productos como la Tilapia, donde cada caja pesa lo mismo).

pesoEquivalenteKg: Double? - Si manejoDeStock es "POR_UNIDAD_FIJA", este campo es obligatorio. Guarda cuántos 
Kg representa UNA unidad (Ej: para Tilapia, sería 4.54).

stockIdealC04enKg: Double - El stock objetivo en C-04, siempre en Kg.

ordenTraspaso: Int - Para el orden manual en la lista de traspasos.

EJEMPLO:

-nombre: String - Nombre del producto (ej. "Atún", "Lengua", "Pacotilla").
-tipoDeEmpaque: String - dos opciones
-"PESO_FIJO": Para productos con peso constante (ej. cajas de Tilapia).
-"GRANEL": Para productos con peso variable (ej. costales de Lengua).
-unidadDeEmpaque: String? - Nombre de la unidad (ej. "Caja", "Costal"). Obligatorio para PESO_FIJO y GRANEL
-pesoPorUnidad: Double? -
-Para PESO_FIJO: Peso exacto por unidad (ej. 4.54 kg por caja).
-Para GRANEL: Peso promedio estimado por unidad (ej. 28.5 kg por costal).
-stockIdealC04: Double - Cantidad objetivo en kg para el almacén C-04 (ej. 300 kg).
-ordenTraspaso: Int - Orden manual para la lista de traspasos y el PDF.
-modoManual: Boolean - Si true, el PDF imprime solo el nombre del producto y el lote sugerido (si aplica), dejando CANTIDAD, PESO C/U, y TOTAL en blanco.
-espacioExtra: Boolean - Si true, la fila del producto en el PDF tiene el doble de altura para muchos pesos en C/U

data class Product(
    val id: String = "",
    val name: String = "",
    val tipoDeEmpaque: String = "", // "GRANEL", "PESO_FIJO"
    val unidadDeEmpaque: String? = null, // "Caja", "Costal", "Bolsa" etc
    val pesoPorUnidad: Double? = null, // Fijo o promedio, según el tipo
    val stockIdealC04: Double = 0.0, // Siempre en Kg
    val ordenTraspaso: Int = 0,
    val modoManual: Boolean = false,
    val espacioExtraPDF: Boolean = false
)


Paso 1.1: Super-potenciar la Pantalla Compra

Paso 1.3: Implementar el Principio de Conversión Automática
Esta es la "magia" interna que hace todo posible.
Conversion inteligente para fines de inventario y mostrar panatalla

de "100 Cajas" de Tilapia, la app hace el cálculo 100 * 4.54 = 454 Kg y guarda 454.0 en los campos de stock en Firestore.
es este el resultado de mostrar ambos pero para inventario siempr sera la logica en kilos

Asi lo lotes se van a menejar por logica de cajas por peso
el lote actualmente muestra 
primeraa linea: proveedor y peso
segunda linea: Fecha de lote (cuando llego, no cuando se ingreso)
NUEVA tercera linea: Cajas, bolsas, costales  EJemplo si es agranel :aprox:28kg, si se selecciona fijo: 15kg

Esta pantalla se convierte en el centro de configuración de cada producto.
al ingresar compra aparece la pantalla
-Registrar compra: producto nombre
-Cantidad NETA comprada KG (este es el factor en kilos para inventario seguiria igual)
-Proveedor mima logica solo ponerlo en rojo para que la recomendacion sea mas prioritaria, sigue siendo opcional pero, mas visible para forzar porner algo.
-Tipo de recepcion:Empacado o granel: aqui comienza la magia depende el ratio que se seleccione 
--Empacado: deberia desplegar la logica de las cajas es un peso fijo siempre entonces sale un cuadro que dice Unidad para desplegar y seleccionar y peso le pongo 4.54 y sabra que son el peso de las cajas fijas,
  el cual  va a ser el determinante de cada caja su peso.
--Granel: este deberia seguir con la logica de pendiente de empacar y ahora pide Unidad igual desplegable, costales, cajas etc y en peso es Aproximado, no es el peso total,
esto es para fines del traspasos la funcion nueva que previamente debemos configurar aqui para que funcione, el aproximado no determina el total, como su nombre lo dice es aproximado,
esto determina la cantidad mas o menos que habra de costales, cajas etc, en peso fijo si debe ser ral ya que es fijo.
-Luego sigue fecha de recepcion (sigue igual no cambia nada)
-botones aceptar o cancelar.
---------------------------------
tipo de empaque todo sera obligatorio, todo sigue igual asi como en proveedor que es opcional pero solo ahora sera rojo para remarcar su importancia.

///////////////////////////////////////
/////////Nueva vantana traspaso///////
//////////////////////////////////////

FASE 2    Plan TRASPASO INTELIGENTE V1

Configuracion:
Ruta en APP
Menú principal > "Más Opciones" > "Ajustes de Sistema" > "Configuración de Traspasos".

Esta configuracion es la que deterimina como se  mostrara la pantalla de traspasos

Interfaz de Planificación y Exclusión:
-Formato Cebra (configuración global): Colores tenues que cambian mensualmente (ej. mostaza, azul suave, verde oliva), Intensidad ajustable: Colores intermedios que contrasten con el texto negro (evitar colores muy claros o intensos).
este formato zebra puede llegar preconfigurado con opciones para yo refactorizar o mas bien reconfigurar colores y tonalidades.
-Lista de productos activos (estado true en la colección products de Firestore).
-Cada producto tiene una fila expandible para configuración. muy visible el boton para desplegar configuraciones de productos.
-Ordenamiento: Drag-and-drop para definir el orden exacto en la lista de traspasos y el PDF siempre mvible de arrastarar y soltar. (ej. Atún arriba de Macuil, pero debajo de Salmón).

El desplegar expandible comienza Configuración por Producto:Mostrando

-Stock Ideal en C-04: [ 450 ] Kg // por si no se puso al inicio aqui se puede configurar o ajustar nuevamente si en dado caso aparece en 0 o ya predefinidio de inicio
-Tipo de Empaque: solo se muestra como afirmacion GRANEL O FIJO ya que esta determinado al inicio.
-Opciones de PDF: [ ✓ ] Modo Manual  [ ✓ ] Espacio Extra
   Acciones de cada boton: Modo manual : Si se marca, el PDF muestra el nombre del producto y el lote sugerid, Se permite aceptar las sugerencias  de lotes y cantidad,
pero al imprimir siempre imprimira en blanco C/U y TOTAL.
                           Espacio Extra: solo aplica un salto de linea es decir hace mas grande la linea en horizontal para poder poner mas pesos de manera manual por si son muchos costales, cajas etc, variables de peso
las lineas normales son las fijas y este espacio extra hace mas grande ese recuadro hablando en idioma excel serian dos celdas en expacio extra y una en normal sin espacio extra por linea de producto.


/////////////////
Fase 3
////////////////


Paso 1

Menú Principal: "Proveedores" se mueve a "Más Opciones". Un nuevo ícono de "Traspasos" toma su lugar como sección principal.

Nueva Pantalla: "Traspasos": Se divide en dos pestañas:

Pestaña 1: "Planificar Traspaso"

Pestaña 2: "Confirmar Traspaso"

Comenzamos con "Planificar Traspaso"
Esta configuracion es para imprimir traspaso en pdf pero debe conservar kilos y pasarlos a las pantalla confirmar traspaso, la configuracion que imprima en mi pdf.
Paso 1.1
Este paso queda claro que la sugerencia siempre sera para llenar el faltante de stock ajustando a lo justo en fijo multiplicado por la unidad o mas cercano si es agranel

Interfaz:

Fecha: Fecha actual por defecto (ej. 24/07/2025), editable (ej. para mañana).
Lista de Productos: Ordenada por ordenTraspaso. configurada en configuracion de traspaso, respetando ese orden
Cada fila muestra:
[✓] Checkbox "Incluir en PDF" (desactivado por defecto si la sugerencia es 0, es decir, si stockActualC04 >= stockIdealC04), o si no quiere moverlo ese día.
[✓] Sugerencia FIFO LOTE o LOTES a mover, poder editar de que lote quiero mover y es el que aparecera o apareceran en PDF puede creaar la doble linea en esta configuracion EJEMPLO:
si decido aceptar la sugerancia debe permanecer igual asi la linea y todo y mostrar el lote y sacar de ese lote, guardar configuracion para confirmar traspaso, pero
si yo elijo modificar lote y seleccionar 2 o mas lotes, debe crear una nueva funcion, lote 25/05/25 ahora pedira cuantas cajas , costales etc depende el tipo fijo o granel a sacar de ese lote,
y si elijo otro lote tambien preguntar y si es fijo solo hacer la conversion y mostrar en cantidad en una linea 5 cajas siguiendo la linea horizontal de ese lote,
y debajo el otro lote con 4 cajas pero el nombre del producto abarca toda las lineas y TOTAL solo los demas se dibiden en lineas conservando el color zebra que les toca,
esto igual se conserva tal cual para confirmar traspaso
[✓] [Producto]  nombre No editable fijo para respetar movimientos y seguimientos
[✓]Sugerencia de traspaso / unidad a mover (lo que aparecera en cantidad) Editable por si quiero mover mas costales cajas etc , esto aplica en todos pero si es producto fijo se ajusta Cantidad y total en base a conversion,
si elijo 5 cajas y es fijo en el pdf mostrar la conversion total pero si elijo 5 cajas y es granel pues no mostrara nada por que ese es variable y seria manual total y C/U.

boton de confirmar y se genera el pdf el cual se comparte para ser enviado y asi mandar a imprimir este formato contendra la siguiente forma:
//INICIO de formato
 _____________________________________________________________________________________________________________________________________
|  Negritas centrado: Fecha_____/______/____ (ya determinado desde inicio)                                                            |
|FECHA LOTE | PRODUCTO | CANTIDAD  | PROVEEDOR |                       PESO C/U                                     | TOTAL           |
|------------------------------------------------------------------------------------------------------------------------------       |
|25/05/25   | TILAPIA  | 20 cajas  | MARTEL    |                       4.54KG                                       | 90.8KG          |
|____________________________________________________________________________________________________________________________         |
|28/05/25   | ROBALO   | 5 costales| MAXIMAR   |                                                                    |                 |
|____________________________________________________________________________________________________________________________         |
|28/05/25   | LENGUA   | 5 costales| MAXIMAR   |                                                                    |                 |
|31/06/25   |          | 3 costales|           |                                                                    |                 |
|_____________________________________________________________________________________________________________________________        |
|                                                                                                                                     |
|------------------------------------------------------------------------------------------------------------------------------       |
|_____________________________________________________________________________________________________________________________________|

      Verifico mercancía: __________________                                    Saco mercancía: __________________

//FIN de formato verifica el excel pero debe ser asi con esos espacios para peso c/u grande y los demas ajustados.

Paso 1.2: Pantalla "Confirmar Traspaso" o "Cancelar traspaso"


muestra la lista de traspasos por confirmar o cancelar, podria mostrar botones alado de la fecha en verde con [✓] o con rojo [x] selecciono : confirmar

Interfaz de Confirmación:

Muestra la lista del último PDF generado, con las cantidades y lotes sugeridos ya cargados. los que estaban en el checkbox seleccionados, ese checkbox hace la magia para pasar a esta seccion lo que realmente se imprimio y se edito.

Proceso de Ajuste y Confirmación:

Para Tilapia (Peso Fijo): El campo de confirmación pide la cantidad en Cajas. El usuario confirma [ 20 ] Cajas, de tal Lote

Para Lengua (Granel): El campo pide la cantidad en Kg. El usuario confirma [ 415.5 ] Kg. de tal Lote

Si los lotes o cantidades reales fueron diferentes a la sugerencia, el usuario los edita aquí.
si hay mas lotes del mismo producto se muestra mas lineas de confirmacion del mismo producto


Ejecución de Movimientos (La Magia Final):

Al presionar "Confirmar y Ejecutar", la app:

Toma la cantidad confirmada por el usuario (ej. "20 Cajas").

Internamente la convierte a Kg (20 * 4.54 = 90.8 Kg).

Ejecuta la transacción en Firestore, descontando 90.8 Kg de los lotes de Matriz seleccionados y sumando 90.8 Kg al stock de C-04.

Crea un StockMovement individual para cada producto traspasado, garantizando un historial perfecto y auditable.

pero tambien deberia tener cancelar traspaso, imprimi el pdf pero no se realizo, este se destruye por completo sin conservar movimientos ni nada, debe confirmar dos veces antes de cancelar traspaso y confirmar ya que son movimientos importantes
confirmar/cancelar (depende la seleccion si deseo cancelar o confirmar)
se esta confirmando /cancelando traspaso 25/05/25 (SI) (NO)
Estas seguro de Confirmar/ cancelar traspaso 25/05/25 (SI) (NO)




////////////////////////////////
adiconales para configuracion interna de sistema
////////////////////////////////

no se aceptan numero negativos o mas que el stock disponible exitente

no me puede sugerir mas del stock en matriz:
Lógica de Sugerencia:

Fórmula Base:
necesidadKg = stockIdealC04 - stockActualC04
disponibleKg = stockActualMatriz
sugerenciaKg = max(0, min(necesidadKg, disponibleKg))

en logica
val necesidadKg = stockIdealC04 - stockActualC04
val disponibleKg = stockActualMatriz
val sugerenciaKg = max(0.0, min(necesidadKg, disponibleKg))


formato interno de sistema

Tipo	       Cantidad 	Peso c/u	Total
PESO_FIJO      "13 Cajas"	4.54 Kg	        59.02 Kg
GRANEL	       "15 Costales"	(en blanco)	(en blanco)

Al confirmar:

Se genera un StockMovement con la cantidad en Kg.

Se registra el origen (Matriz) y destino (C-04).

Se usan los lotes indicados o sugeridos.

Todo queda trazable por lote, fecha, peso y producto.

tal cual lo logica actual para el manejo de historial de busqueda avanzada

⚡ Ejemplo Granel Real: "Pacotilla"
Configuración:

Tipo: GRANEL

Unidad: Caja

Peso promedio: 30 Kg

Stock ideal: 240 Kg (8 cajas)

Stock actual en C-04: 148 Kg

Cálculo:

necesidadKg = 92 Kg

sugerencia = round(92 / 30) = 3 cajas

Resultado:

Te sugiere mover 3 cajas.

Si aceptas y esta en modo manual, se imprime:

Cantidad: 3 cajas

Peso c/u: (en blanco)

Total: (en blanco)


////////////////
1. Acoplarlo a Firestore
El sistema debe generar:

Documento traspasos con metadata.

Subcolección detalles con cada producto, lote, cantidades.

2. Modo Manual Avanzado
Podrías permitir que el modo manual tenga una plantilla o patrón prellenado para facilitar el llenado.

3. Mejoras UI/UX
Color Picker para zebra en configuración.

Drag & Drop para orden de productos es una excelente idea.

El botón para expandir/cerrar configuración de producto debería tener un ícono claro y respuesta visual.

4. Validaciones y Seguridad
No permitir sugerencias mayores al stock matriz.

No permitir confirmación si no hay lotes válidos seleccionados.

Prevenir inconsistencias si el stock fue alterado entre planificación y confirmación.

5. Performance
En productos con muchos lotes, cargar todo puede ser pesado. Usa limit, paginación o smart prefetch.

Usa índices compuestos en Firestore para consultas por lote, producto, fecha.





