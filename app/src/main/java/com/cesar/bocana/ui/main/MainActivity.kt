package com.cesar.bocana.ui.main

import android.Manifest // Asegúrate que esté
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment // Import Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.DevolucionStatus
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.User
import com.cesar.bocana.databinding.ActivityMainBinding
import com.cesar.bocana.ui.ajustes.AjustesFragment
import com.cesar.bocana.ui.auth.LoginActivity
import com.cesar.bocana.ui.devoluciones.DevolucionesFragment
import com.cesar.bocana.ui.packaging.PackagingFragment
import com.cesar.bocana.ui.products.ProductListFragment
import com.cesar.bocana.ui.suppliers.SupplierListFragment
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.navigation.NavigationBarView // Import para el listener
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

// Ya no implementa OnBackStackChangedListener aquí directamente,
// la lógica de toolbar se simplifica.
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
            Log.d(TAG, "Notification permission granted.")
            getAndSaveFcmToken()
        } else {
            Log.w(TAG, "Notification permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = Firebase.auth
        setSupportActionBar(binding.toolbar) // Seguimos usando la Toolbar superior

        createNotificationChannel()

        if (auth.currentUser == null) {
            goToLogin()
            return
        }

        setupBottomNavigation() // Configurar la navegación inferior
        fetchUserInfoAndLoadInitialFragment(savedInstanceState) // Carga datos y el primer fragmento
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            lifecycleScope.launch { checkConditionsAndNotifyLocally() }
            fetchUserInfoOnly() // Solo para actualizar nombre si cambia
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            var selectedFragment: Fragment? = null
            when (item.itemId) {
                R.id.navigation_productos -> {
                    selectedFragment = ProductListFragment()
                    supportActionBar?.title = getString(R.string.app_name) // O "Inventario"
                }
                R.id.navigation_empaque -> {
                    selectedFragment = PackagingFragment()
                    supportActionBar?.title = "Pendiente Empacar"
                }
                R.id.navigation_devoluciones -> {
                    selectedFragment = DevolucionesFragment()
                    supportActionBar?.title = "Devoluciones"
                }
                R.id.navigation_proveedores -> {
                    selectedFragment = SupplierListFragment()
                    supportActionBar?.title = "Proveedores"
                }
                R.id.navigation_ajustes -> {
                    selectedFragment = AjustesFragment()
                    supportActionBar?.title = "Ajustes Manuales"
                }
            }

            if (selectedFragment != null) {
                // Reemplaza el fragmento actual sin añadir a back stack
                // para que el botón atrás del sistema cierre la app desde estas pantallas
                supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, selectedFragment)
                    .commit() // Commit normal aquí está bien
                updateToolbarSubtitle() // Asegura que el subtítulo (nombre user) se muestre
                true // Indicar que el evento fue manejado
            } else {
                false // No manejado si el item no coincide
            }
        }
        // Seleccionar el item inicial por defecto (Productos)
        binding.bottomNavigation.selectedItemId = R.id.navigation_productos
    }

    private fun loadFragment(fragment: Fragment) {
        // Función auxiliar para cargar fragmentos (usada por el listener de abajo)
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            // No añadimos a backstack para navegación inferior principal
            .commit()
        updateToolbarSubtitle() // Mantener subtítulo actualizado
    }


    // --- Código de Notificaciones y Permisos (sin cambios respecto a respuesta #49) ---
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
            Log.d(TAG, "Notification channel created.")
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted.")
                    getAndSaveFcmToken()
                }
                else -> {
                    Log.d(TAG, "Requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            Log.d(TAG, "Notification permission not required for this API level.")
            getAndSaveFcmToken()
        }
    }


    private fun getAndSaveFcmToken() {
        val userId = auth.currentUser?.uid ?: return
        Log.d(TAG, "Attempting to get FCM token for user $userId")
        lifecycleScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                if (token != null) {
                    Log.d(TAG, "FCM Token obtained: $token. Saving to Firestore.")
                    val userDocRef = db.collection("users").document(userId)
                    userDocRef.set(mapOf("fcmToken" to token), SetOptions.merge())
                        .await()
                    Log.d(TAG, "FCM Token potentially updated in Firestore.")
                } else {
                    Log.w(TAG, "FCM Token was null.")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error getting/saving FCM token", e)
            }
        }
    }


    private suspend fun checkConditionsAndNotifyLocally() {
        Log.d(TAG, "checkConditions START ---->")
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
            Log.d(TAG, "checkConditions - Low Stock Check: Found $lowStockCount item(s).")
            if (lowStockCount > 0) {
                alertMessages.add("$lowStockCount prod. stock bajo")
            }

            val pendingDevoSnapshot = db.collection("pendingDevoluciones")
                .whereEqualTo("status", DevolucionStatus.PENDIENTE.name)
                .limit(1)
                .get()
                .await()
            foundPendingDevo = !pendingDevoSnapshot.isEmpty
            Log.d(TAG, "checkConditions - Pending Devo Check: Found = $foundPendingDevo")
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
            Log.d(TAG, "checkConditions - Overdue Pack Check: Found = $foundOverduePack")
            if (foundOverduePack) {
                alertMessages.add("Empaque Atrasado")
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "checkConditions - Error during checks", e)
            alertMessages.add("Error verificando alertas")
        }

        if (alertMessages.isNotEmpty()) {
            val notificationText = alertMessages.joinToString(" / ")
            Log.d(TAG, "checkConditions - Conditions MET. Showing notification: '$notificationText'")
            showLocalNotification("Alertas Pendientes Bocana", notificationText)
        } else {
            Log.d(TAG, "checkConditions - Conditions NOT MET. No notification shown.")
        }
        Log.d(TAG, "checkConditions END ---->")
    }

    private fun showLocalNotification(title: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot show local notification, permission not granted.")
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
            Log.d(TAG, "Local notification shown.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException showing notification.", e)
        }
    }
    // --- Fin Código Notificaciones ---


    // Modificado para cargar solo el fragmento inicial si es necesario
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

                        // Solo carga el fragmento inicial si savedInstanceState es null
                        // y si no hay ya un fragmento en el contenedor
                        if (savedInstanceState == null && supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) == null) {
                            Log.d(TAG, "Loading initial fragment (ProductListFragment).")
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.nav_host_fragment_content_main, ProductListFragment())
                                .commitNow() // Usar commitNow si es en onCreate
                            // Asegurar que el item correcto esté seleccionado en la barra inferior
                            binding.bottomNavigation.selectedItemId = R.id.navigation_productos
                        }
                        invalidateOptionsMenu() // Redibuja menú superior (solo Logout)

                    } else {
                        Log.w(TAG,"User document not found for UID: ${user.uid}")
                        showErrorAndLogout("Error: Usuario no registrado.")
                    }
                }
            }
            .addOnFailureListener { exception ->
                if (!isDestroyed && !isFinishing) {
                    Log.e(TAG, "Error fetching user document", exception)
                    showErrorAndLogout("Error conexión datos user.")
                }
            }
    }

    private fun fetchUserInfoOnly() {
        val user = auth.currentUser ?: return
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (!isDestroyed && !isFinishing) {
                    if (document != null && document.exists()) {
                        val userData = document.toObject(User::class.java)

                        if (userData?.isAccountActive != true) {
                            Log.w(TAG,"User status changed (inactive), logging out.")
                            signOut()
                            return@addOnSuccessListener
                        }

                        val fetchedName = userData?.name ?: "Usuario"
                        if (currentUserName != fetchedName) {
                            currentUserName = fetchedName
                            updateToolbarSubtitle()
                            // No es necesario invalidar menú aquí usualmente, ya que solo tiene logout
                        }

                    } else {
                        Log.w(TAG,"User document not found during re-fetch, logging out.")
                        signOut()
                    }
                }
            }.addOnFailureListener { e ->
                if (!isDestroyed && !isFinishing) {
                    Log.e(TAG, "Error re-fetching user info", e)
                }
            }
    }

    private fun updateToolbarSubtitle() {
        supportActionBar?.subtitle = currentUserName ?: ""
    }

    // onBackStackChanged ya no es necesaria porque no usamos addToBackStack para bottom nav

    // --- Manejo Menú Superior (Toolbar) ---
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Infla el menú; esto añade items a la action bar si está presente.
        menuInflater.inflate(R.menu.main_menu, menu) // Usa el menú simplificado
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // Aquí podríamos ocultar/mostrar Logout si fuera necesario, pero usualmente siempre está
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Manejar clics en items de la action bar
        return when (item.itemId) {
            R.id.action_logout -> {
                signOut()
                true
            }
            // El botón Home/Up (flecha atrás en toolbar) ya NO se maneja aquí
            // porque no estamos usando addToBackStack normalmente con BottomNav.
            // Se maneja por el sistema o el Navigation Component si se usara.
            else -> super.onOptionsItemSelected(item)
        }
    }
    // --- Fin Manejo Menú Superior ---

    // Las funciones navigateTo... ya no se usan desde el menú superior
    // private fun navigateToAjustes() { ... }
    // private fun navigateToDevoluciones() { ... }
    // private fun navigateToPackaging() { ... }
    // private fun navigateToSuppliers() { ... }


    private fun signOut() {
        Log.d(TAG, "signOut: Initiating sign out...")
        currentUserName = null
        updateToolbarSubtitle() // Limpiar subtítulo

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)

        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener {
            Log.d(TAG, "Google sign out complete, navigating to Login.")
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
        Log.e(TAG, "Error causing logout: $message")
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        signOut()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}