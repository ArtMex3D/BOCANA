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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.cesar.bocana.ui.ajustes.AjustesFragment // Asegúrate que esté importado
import com.cesar.bocana.ui.auth.LoginActivity
import com.cesar.bocana.ui.devoluciones.DevolucionesFragment
import com.cesar.bocana.ui.masopciones.MoreOptionsFragment // Importar el nuevo fragmento
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
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            lifecycleScope.launch { checkConditionsAndNotifyLocally() }
            fetchUserInfoOnly()
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            var selectedFragment: Fragment? = null
            var title = getString(R.string.app_name) // Título por defecto

            when (item.itemId) {
                R.id.navigation_productos -> {
                    selectedFragment = ProductListFragment()
                    title = "Inventario de Stocks" // Título específico
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
                R.id.navigation_mas_opciones -> { // Nueva opción
                    selectedFragment = MoreOptionsFragment()
                    title = "Más Opciones"
                }
            }

            if (selectedFragment != null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, selectedFragment)
                    .commit()
                supportActionBar?.title = title
                updateToolbarSubtitle()
                true
            } else {
                false
            }
        }
        if (supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) == null) {
            binding.bottomNavigation.selectedItemId = R.id.navigation_productos
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
                            Log.d(TAG, "Cargando fragmento inicial (ProductListFragment).")
                            binding.bottomNavigation.selectedItemId = R.id.navigation_productos
                        }
                        invalidateOptionsMenu()

                    } else {
                        Log.w(TAG,"Documento de usuario no encontrado para UID: ${user.uid}")
                        showErrorAndLogout("Error: Usuario no registrado.")
                    }
                }
            }
            .addOnFailureListener { exception ->
                if (!isDestroyed && !isFinishing) {
                    Log.e(TAG, "Error obteniendo documento de usuario", exception)
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
                            Log.w(TAG,"Estado de usuario cambiado (inactivo), cerrando sesión.")
                            signOut()
                            return@addOnSuccessListener
                        }

                        val fetchedName = userData?.name ?: "Usuario"
                        if (currentUserName != fetchedName) {
                            currentUserName = fetchedName
                            updateToolbarSubtitle()
                        }

                    } else {
                        Log.w(TAG,"Documento de usuario no encontrado durante re-fetch, cerrando sesión.")
                        signOut()
                    }
                }
            }.addOnFailureListener { e ->
                if (!isDestroyed && !isFinishing) {
                    Log.e(TAG, "Error re-obteniendo info de usuario", e)
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
                // Si el fragmento actual es uno de los "principales" del bottom nav,
                // y no es el de "productos", ir a "productos".
                // Si es "productos" o un fragmento "interno" (ej. AddEdit), dejar que el sistema maneje el Up.
                val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                if (currentFragment !is ProductListFragment &&
                    (currentFragment is SupplierListFragment ||
                            currentFragment is PackagingFragment ||
                            currentFragment is DevolucionesFragment ||
                            currentFragment is MoreOptionsFragment ||
                            currentFragment is AjustesFragment)) {
                    binding.bottomNavigation.selectedItemId = R.id.navigation_productos
                    return true
                }
                // Para otros fragmentos (como AddEditProductFragment), el comportamiento Up (popBackStack) es el deseado.
                // O si ya estamos en ProductListFragment, el Up no debería hacer nada especial aquí.
                super.onBackPressed() // O supportFragmentManager.popBackStack() si es más apropiado
                return true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun signOut() {
        Log.d(TAG, "signOut: Iniciando cierre de sesión...")
        currentUserName = null
        updateToolbarSubtitle()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)

        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener {
            Log.d(TAG, "Cierre de sesión de Google completado, navegando a Login.")
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
        Log.e(TAG, "Error causando cierre de sesión: $message")
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
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    getAndSaveFcmToken()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
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
                if (token != null) {
                    val userDocRef = db.collection("users").document(userId)
                    userDocRef.set(mapOf("fcmToken" to token), SetOptions.merge()).await()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error obteniendo/guardando token FCM", e)
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
