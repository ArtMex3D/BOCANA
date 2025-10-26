package com.cesar.bocana.ui.main

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cesar.bocana.R
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.DetalleTraspasoPlan
import com.cesar.bocana.data.model.DevolucionStatus
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.TraspasoEstado
import com.cesar.bocana.data.model.User
import com.cesar.bocana.data.repository.InventoryRepository
import com.cesar.bocana.databinding.ActivityMainBinding
import com.cesar.bocana.ui.auth.LoginActivity
import com.cesar.bocana.ui.devoluciones.DevolucionesFragment
import com.cesar.bocana.ui.masopciones.MoreOptionsFragment
import com.cesar.bocana.ui.packaging.PackagingFragment
import com.cesar.bocana.ui.printing.EtiquetasMenuFragment
import com.cesar.bocana.ui.products.ProductListFragment
import com.cesar.bocana.ui.quickmove.QuickMovementFragment
import com.cesar.bocana.ui.suppliers.SupplierListFragment
import com.cesar.bocana.ui.traspasos.config.TraspasosContainerFragment
import com.cesar.bocana.utils.ConnectivityObserver
import com.cesar.bocana.utils.FirestoreCollections
import com.cesar.bocana.utils.NetworkStatus
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private val db = Firebase.firestore
    private var currentUserName: String? = null
    private lateinit var repository: InventoryRepository
    private lateinit var connectivityObserver: ConnectivityObserver


    private val NOTIFICATION_CHANNEL_ID = "bocana_alerts_channel"
    private val LOW_STOCK_NOTIFICATION_ID = 1001
    private val DEVOLUCION_NOTIFICATION_ID = 1002
    private val PACKAGING_NOTIFICATION_ID = 1003


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            getAndSaveFcmToken()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = Firebase.auth
        setSupportActionBar(binding.toolbar)

        val database = AppDatabase.getDatabase(applicationContext)
        repository = InventoryRepository(database, db)

        createNotificationChannel()

        if (auth.currentUser == null) {
            goToLogin()
            return
        }

        // --- LLAMADA A LA NUEVA LÓGICA DE LIMPIEZA ---
        lifecycleScope.launch {
            cleanupStaleReservedLots()
        }
        // --- FIN DE LA LLAMADA ---


        // Iniciar observador de conectividad
        connectivityObserver = ConnectivityObserver(applicationContext)
        observeNetworkStatus()

        // Iniciar listeners y sincronización automática
        lifecycleScope.launch {
            repository.syncNewMovements() // Sincronización inteligente al inicio
        }
        repository.startFirestoreListeners()

        setupBottomNavigation()
        fetchUserInfoAndLoadInitialFragment(savedInstanceState)
        askNotificationPermission()
        handleDeepLink(intent)
    }

    private fun observeNetworkStatus() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectivityObserver.observe().collect { isOnline ->
                    NetworkStatus.isOnline = isOnline // Actualizar el estado global
                    runOnUiThread {
                        binding.textViewOfflineBanner.visibility = if (isOnline) View.GONE else View.VISIBLE

                        // START MODIFICATION: Block UI elements when offline
                        val menu = binding.bottomNavigation.menu
                        menu.findItem(R.id.navigation_traspasos).isEnabled = isOnline
                        menu.findItem(R.id.navigation_empaque).isEnabled = isOnline
                        menu.findItem(R.id.navigation_mas_opciones).isEnabled = isOnline

                        // Functions that depend only on local data can remain enabled
                        menu.findItem(R.id.navigation_productos).isEnabled = true // Stocks (Room)
                        menu.findItem(R.id.navigation_etiquetas).isEnabled = true // Etiquetas (Local generation)
                        // END MODIFICATION

                        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                        if (currentFragment is ProductListFragment) {
                            currentFragment.onNetworkStatusChanged(isOnline)
                        }
                    }
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        repository.stopFirestoreListeners()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            lifecycleScope.launch { checkConditionsAndNotifyLocally() }
            fetchUserInfoOnly()
        }
    }
    private suspend fun cleanupStaleReservedLots() {
        Log.d(TAG, "Ejecutando limpieza de lotes reservados antiguos...")
        try {
            // Establecemos el límite a 2 días atrás.
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DATE, -2) // Resta 2 días a la fecha actual
            val twoDaysAgo = calendar.time

            // Buscamos planes pendientes que fueron creados antes de hace dos días.
            val stalePlansSnapshot = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS)
                .whereEqualTo("estado", TraspasoEstado.PENDIENTE.name)
                .whereLessThan("createdAt", twoDaysAgo)
                .get()
                .await()

            if (stalePlansSnapshot.isEmpty) {
                Log.d(TAG, "No se encontraron planes antiguos para limpiar.")
                return
            }

            Log.d(TAG, "Se encontraron ${stalePlansSnapshot.size()} planes antiguos para cancelar y limpiar lotes.")

            // Usaremos un batch para ejecutar todas las operaciones de una sola vez.
            val batch = db.batch()

            for (planDoc in stalePlansSnapshot.documents) {
                val planRef = planDoc.reference
                // Marcamos el plan como cancelado automáticamente
                batch.update(planRef, "estado", TraspasoEstado.CANCELADO.name)

                // Buscamos los detalles del plan para encontrar los lotes a liberar
                val detallesSnapshot = planRef.collection("detalles").get().await()
                for (detalleDoc in detallesSnapshot.documents) {
                    val detalle = detalleDoc.toObject(DetalleTraspasoPlan::class.java)
                    detalle?.lotesSugeridos?.forEach { desglose ->
                        // Nos aseguramos de que el loteId no esté vacío
                        if (desglose.loteId.isNotBlank()) {
                            val loteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId)
                            // Liberamos el "candado" del lote eliminando el campo 'estadoTraspaso'
                            batch.update(loteRef, "estadoTraspaso", FieldValue.delete())
                        }
                    }
                }
            }

            // Ejecutamos todas las actualizaciones en la base de datos
            batch.commit().await()
            Log.d(TAG, "Limpieza de lotes antiguos completada con éxito.")

        } catch (e: Exception) {
            Log.e(TAG, "Error durante la limpieza automática de lotes reservados.", e)
            // No mostramos un error al usuario, ya que es un proceso de fondo y no crítico para la UI.
        }
    }
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return

        val uri = intent.data
        if (uri != null && uri.scheme == "https" && uri.host == "bocana.netlify.app") {
            val pathSegments = uri.pathSegments
            if (pathSegments.size == 2 && pathSegments[0] == "movimiento") {
                val productId = pathSegments[1]
                setIntent(Intent())
                navigateToQuickMovementPanel(productId)
            }
        }
    }

    private fun navigateToQuickMovementPanel(productId: String) {
        val fragment = QuickMovementFragment.newInstance(productId)
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            var selectedFragment: Fragment? = null
            var title = getString(R.string.app_name)

            when (item.itemId) {
                R.id.navigation_productos -> {
                    selectedFragment = ProductListFragment()
                    title = "Inventario de Stocks"
                }
                R.id.navigation_traspasos -> {
                    selectedFragment = TraspasosContainerFragment()
                    title = "Gestión de Traspasos"
                }R.id.navigation_empaque -> {
                selectedFragment = PackagingFragment()
                title = "Pendiente Empacar"
            }
                R.id.navigation_etiquetas -> {
                    selectedFragment = EtiquetasMenuFragment()
                    title = "Etiquetas"
                }
                R.id.navigation_mas_opciones -> {
                    selectedFragment = MoreOptionsFragment()
                    title = "Más Opciones"
                }
            }

            if (selectedFragment != null) {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                if (currentFragment?.javaClass != selectedFragment.javaClass) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_fragment_content_main, selectedFragment)
                        .commit()
                }
                supportActionBar?.title = title
                updateToolbarSubtitle()
                return@setOnItemSelectedListener true
            }
            // Si el fragmento es null (como en el placeholder de traspasos), no hacemos nada y retornamos false
            return@setOnItemSelectedListener false
        }
    }

    private fun fetchUserInfoAndLoadInitialFragment(savedInstanceState: Bundle?) {
        val user = auth.currentUser ?: run { goToLogin(); return }
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (!isDestroyed && !isFinishing) {
                    if (document != null && document.exists()) {
                        val userData = document.toObject(User::class.java)
                        if (userData?.isAccountActive != true) {
                            showErrorAndLogout("Cuenta inactiva.")
                            return@addOnSuccessListener
                        }
                        currentUserName = userData.name ?: "Usuario"
                        updateToolbarSubtitle()

                        if (savedInstanceState == null && supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) == null) {
                            binding.bottomNavigation.selectedItemId = R.id.navigation_productos
                        }
                        invalidateOptionsMenu()

                    } else {
                        showErrorAndLogout("Error: Usuario no registrado.")
                    }
                }
            }
            .addOnFailureListener {
                if (!isDestroyed && !isFinishing) {
                    showErrorAndLogout("Error conexión datos user.")
                }
            }
    }

    private fun fetchUserInfoOnly() {
        val user = auth.currentUser ?: return
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (!isDestroyed && !isFinishing && document != null && document.exists()) {
                    val userData = document.toObject(User::class.java)
                    if (userData?.isAccountActive != true) {
                        signOut()
                        return@addOnSuccessListener
                    }
                    val fetchedName = userData?.name ?: "Usuario"
                    if (currentUserName != fetchedName) {
                        currentUserName = fetchedName
                        updateToolbarSubtitle()
                    }
                }
            }
    }

    private fun updateToolbarSubtitle() {
        supportActionBar?.subtitle = currentUserName ?: ""
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                signOut()
                true
            }
            android.R.id.home -> {
                supportFragmentManager.popBackStack()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun signOut() {
        repository.stopFirestoreListeners()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)

        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener {
            goToLogin()
        }
    }

    private fun goToLogin() {
        if (!isFinishing) {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finishAffinity()
        }
    }

    private fun showErrorAndLogout(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        signOut()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name) + " Alertas"
            val descriptionText = "Notificaciones sobre stock y tareas pendientes"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                getAndSaveFcmToken()
            }
        } else {
            getAndSaveFcmToken()
        }
    }

    private fun getAndSaveFcmToken() {
        val userId = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                db.collection("users").document(userId).set(mapOf("fcmToken" to token), SetOptions.merge()).await()
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "Error obteniendo/guardando token FCM", e)
            }
        }
    }

    private suspend fun checkConditionsAndNotifyLocally() {
        try {
            val lowStockSnapshot = db.collection("products")
                .whereEqualTo("isActive", true)
                .get()
                .await()

            val lowStockProducts = lowStockSnapshot.documents.mapNotNull { doc ->
                val product = doc.toObject(Product::class.java)
                if (product != null && product.minStock > 0.0 && product.totalStock <= product.minStock) {
                    product
                } else {
                    null
                }
            }
            if (lowStockProducts.isNotEmpty()) {
                val title = "🚨 Alerta de Stock Bajo"
                val content = if (lowStockProducts.size == 1) {
                    "El producto '${lowStockProducts.first().name}' tiene stock bajo."
                } else {
                    "${lowStockProducts.size} productos tienen stock bajo."
                }
                showLocalNotification(title, content, LOW_STOCK_NOTIFICATION_ID)
            }

            val pendingDevoSnapshot = db.collection("pendingDevoluciones")
                .whereEqualTo("status", DevolucionStatus.PENDIENTE.name)
                .limit(1)
                .get()
                .await()
            if (!pendingDevoSnapshot.isEmpty) {
                showLocalNotification(
                    "📝 Tienes Devoluciones Pendientes",
                    "Hay devoluciones que requieren terminar el proceso.",
                    DEVOLUCION_NOTIFICATION_ID
                )
            }

            val threeDaysAgoMillis = Date().time - (3 * 24 * 60 * 60 * 1000)
            val threeDaysAgoTimestamp = Timestamp(Date(threeDaysAgoMillis))

            val overduePackSnapshot = db.collection("pendingPackaging")
                .whereLessThanOrEqualTo("receivedAt", threeDaysAgoTimestamp)
                .limit(1)
                .get()
                .await()
            if (!overduePackSnapshot.isEmpty) {
                showLocalNotification(
                    "📦 ¡Empaque Atrasado!",
                    "Hay productos recibidos hace más de 3 días que aún no se han empacado.",
                    PACKAGING_NOTIFICATION_ID
                )
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "checkConditions - Error durante verificaciones", e)
        }
    }


    private fun showLocalNotification(title: String, content: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ray)
            .setColor(ContextCompat.getColor(this, R.color.brand_purple))
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException mostrando notificación.", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

