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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.DevolucionStatus
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.User
import com.cesar.bocana.databinding.ActivityMainBinding
import com.cesar.bocana.ui.ajustes.AjustesFragment
import com.cesar.bocana.ui.auth.LoginActivity
import com.cesar.bocana.ui.devoluciones.DevolucionesFragment
import com.cesar.bocana.ui.masopciones.MoreOptionsFragment
import com.cesar.bocana.ui.packaging.PackagingFragment
import com.cesar.bocana.ui.products.ProductListFragment
import com.cesar.bocana.ui.suppliers.SupplierListFragment
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private val db = Firebase.firestore
    private var currentUserName: String? = null

    private val NOTIFICATION_CHANNEL_ID = "bocana_alerts_channel"
    private val LOCAL_NOTIFICATION_ID = 1001

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Permiso de notificación concedido.")
            getAndSaveFcmToken()
        } else {
            Log.w(TAG, "Permiso de notificación denegado.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = Firebase.auth
        setSupportActionBar(binding.toolbar)

        createNotificationChannel()

        if (auth.currentUser == null) {
            goToLogin()
            return
        }

        setupBottomNavigation()
        fetchUserInfoAndLoadInitialFragment(savedInstanceState)
        askNotificationPermission()

        // Manejar el deep link si la actividad se crea desde cero
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Manejar el deep link si la actividad ya estaba abierta
        setIntent(intent) // Actualizar el intent de la actividad
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            lifecycleScope.launch { checkConditionsAndNotifyLocally() }
            fetchUserInfoOnly()
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return

        val uri = intent.data
        if (uri != null && uri.scheme == "bocana-app-movements" && uri.host == "movimiento") {
            val productId = uri.getQueryParameter("productoId")
            if (!productId.isNullOrEmpty()) {
                Log.d(TAG, "Deep Link recibido para producto ID: $productId")

                // Limpiar el intent para que no se procese de nuevo al girar la pantalla
                setIntent(Intent())

                // Asegurarnos de estar en el fragmento de productos
                binding.bottomNavigation.selectedItemId = R.id.navigation_productos

                // Esperamos un momento para que el fragmento se cargue y luego mostramos el diálogo
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(250) // Pequeña demora para asegurar la transición del fragmento
                    showProductActionsDialog(productId)
                }
            }
        }
    }

    private fun showProductActionsDialog(productId: String) {
        // Obtenemos los datos del producto para mostrar su nombre y pasarlo a las funciones de acción
        db.collection("products").document(productId).get()
            .addOnSuccessListener { document ->
                if (!isFinishing && document.exists()) {
                    val product = document.toObject(Product::class.java)
                    if (product == null) {
                        Toast.makeText(this, "Producto con ID $productId no encontrado.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    val actions = arrayOf("Salida por Consumo", "Traspaso a C-04")
                    AlertDialog.Builder(this)
                        .setTitle("Acción para: ${product.name}")
                        .setItems(actions) { dialog, which ->
                            val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                            if (currentFragment is ProductListFragment) {
                                // Usamos una vista raíz como 'anchor' para los popups
                                val anchorView: View = currentFragment.view ?: binding.root
                                when (which) {
                                    0 -> currentFragment.onSalidaClicked(product, anchorView)
                                    1 -> currentFragment.onTraspasoC04Clicked(product, anchorView)
                                }
                            } else {
                                Toast.makeText(this, "No se pudo realizar la acción. Intenta desde la lista de productos.", Toast.LENGTH_LONG).show()
                            }
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    Toast.makeText(this, "Producto no encontrado.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error al buscar producto: ${it.message}", Toast.LENGTH_SHORT).show()
            }
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
                R.id.navigation_proveedores -> {
                    selectedFragment = SupplierListFragment()
                    title = "Proveedores"
                }
                R.id.navigation_empaque -> {
                    selectedFragment = PackagingFragment()
                    title = "Pendiente Empacar"
                }
                R.id.navigation_devoluciones -> {
                    selectedFragment = DevolucionesFragment()
                    title = "Devoluciones"
                }
                R.id.navigation_mas_opciones -> {
                    selectedFragment = MoreOptionsFragment()
                    title = "Más Opciones"
                }
            }

            if (selectedFragment != null) {
                // Evitar recargar el mismo fragmento
                val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                if (currentFragment?.javaClass != selectedFragment.javaClass) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_fragment_content_main, selectedFragment)
                        .commit()
                }
                supportActionBar?.title = title
                updateToolbarSubtitle()
                true
            } else {
                false
            }
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
            .addOnFailureListener { exception ->
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
        val alertMessages = mutableListOf<String>()
        var lowStockCount = 0
        var foundPendingDevo = false
        var foundOverduePack = false

        try {
            val lowStockSnapshot = db.collection("products")
                .whereEqualTo("isActive", true)
                .get()
                .await()

            lowStockCount = lowStockSnapshot.documents.count { doc ->
                val product = doc.toObject(Product::class.java)
                product != null && product.minStock > 0.0 && product.totalStock <= product.minStock
            }
            if (lowStockCount > 0) {
                alertMessages.add("$lowStockCount prod. stock bajo")
            }

            val pendingDevoSnapshot = db.collection("pendingDevoluciones")
                .whereEqualTo("status", DevolucionStatus.PENDIENTE.name)
                .limit(1)
                .get()
                .await()
            foundPendingDevo = !pendingDevoSnapshot.isEmpty
            if (foundPendingDevo) {
                alertMessages.add("Devoluciones P.")
            }

            val threeDaysAgoMillis = Date().time - (3 * 24 * 60 * 60 * 1000)
            val threeDaysAgoTimestamp = Timestamp(Date(threeDaysAgoMillis))

            val overduePackSnapshot = db.collection("pendingPackaging")
                .whereLessThanOrEqualTo("receivedAt", threeDaysAgoTimestamp)
                .limit(1)
                .get()
                .await()
            foundOverduePack = !overduePackSnapshot.isEmpty
            if (foundOverduePack) {
                alertMessages.add("Empaque Atrasado")
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "checkConditions - Error durante verificaciones", e)
            alertMessages.add("Error verificando alertas")
        }

        if (alertMessages.isNotEmpty()) {
            val notificationText = alertMessages.joinToString(" / ")
            showLocalNotification("Alertas Pendientes Bocana", notificationText)
        }
    }

    private fun showLocalNotification(title: String, content: String) {
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
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(LOCAL_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException mostrando notificación.", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
