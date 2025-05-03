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
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.DevolucionStatus
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.User
import com.cesar.bocana.data.model.UserRole
import com.cesar.bocana.databinding.ActivityMainBinding
import com.cesar.bocana.ui.ajustes.AjustesFragment
import com.cesar.bocana.ui.auth.LoginActivity
import com.cesar.bocana.ui.devoluciones.DevolucionesFragment
import com.cesar.bocana.ui.packaging.PackagingFragment
import com.cesar.bocana.ui.products.ProductListFragment
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private val db = Firebase.firestore
    private var currentUserRole: UserRole? = null
    private var currentUserName: String? = null

    private val NOTIFICATION_CHANNEL_ID = "bocana_alerts_channel"
    private val LOCAL_NOTIFICATION_ID = 1001

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
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
        setSupportActionBar(binding.toolbar)
        createNotificationChannel()
        if (auth.currentUser == null) { goToLogin(); return }
        supportFragmentManager.addOnBackStackChangedListener(this)
        fetchUserInfoAndLoadFragment(savedInstanceState)
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            lifecycleScope.launch { checkConditionsAndNotifyLocally() }
            fetchUserInfoOnly()
        }
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
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    userDocRef.update("fcmToken", token)
                } else { Log.w(TAG, "FCM Token was null.") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error getting FCM token", e)
            }
        }
    }

    // Dentro de MainActivity.kt

    private suspend fun checkConditionsAndNotifyLocally() {
        Log.d(TAG, "checkConditions START ---->") // Log: Inicio de la verificación
        val alertMessages = mutableListOf<String>()
        var lowStockCount = 0
        var foundPendingDevo = false
        var foundOverduePack = false

        try {
            // 1. Verificar Stock Bajo
            val lowStockSnapshot = db.collection("products")
                .whereEqualTo("isActive", true)
                .whereGreaterThan("minStock", 0)
                .get()
                .await()

            lowStockCount = lowStockSnapshot.documents.count {
                val product = it.toObject(Product::class.java)
                product != null && product.totalStock <= product.minStock
            }
            Log.d(TAG, "checkConditions - Low Stock Check: Found $lowStockCount item(s).") // Log: Resultado Stock Bajo
            if (lowStockCount > 0) {
                alertMessages.add("$lowStockCount prod. stock bajo") // Mensaje más corto
            }

            // 2. Verificar Devoluciones Pendientes
            val pendingDevoSnapshot = db.collection("pendingDevoluciones")
                .whereEqualTo("status", DevolucionStatus.PENDIENTE.name)
                .limit(1) // Solo necesitamos saber si hay *alguna*
                .get()
                .await()
            foundPendingDevo = !pendingDevoSnapshot.isEmpty
            Log.d(TAG, "checkConditions - Pending Devo Check: Found = $foundPendingDevo") // Log: Resultado Devoluciones
            if (foundPendingDevo) {
                alertMessages.add("Devoluciones P.") // Mensaje más corto
            }

            // 3. Verificar Empaque Atrasado
            val threeDaysAgoMillis = Date().time - (3 * 24 * 60 * 60 * 1000)
            val threeDaysAgoTimestamp = Timestamp(Date(threeDaysAgoMillis))

            val overduePackSnapshot = db.collection("pendingPackaging")
                .whereLessThanOrEqualTo("receivedAt", threeDaysAgoTimestamp)
                .limit(1) // Solo necesitamos saber si hay *alguna*
                .get()
                .await()
            foundOverduePack = !overduePackSnapshot.isEmpty
            Log.d(TAG, "checkConditions - Overdue Pack Check: Found = $foundOverduePack") // Log: Resultado Empaque
            if (foundOverduePack) {
                alertMessages.add("Empaque Atrasado") // Mensaje más corto
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "checkConditions - Error during checks", e) // Log: Error
            alertMessages.add("Error verificando alertas") // Añadir mensaje de error
        }

        // Construir y mostrar notificación si hay mensajes
        if (alertMessages.isNotEmpty()) {
            val notificationText = alertMessages.joinToString(" / ")
            Log.d(TAG, "checkConditions - Conditions MET. Showing notification: '$notificationText'") // Log: Se muestra Notificación
            showLocalNotification("Alertas Pendientes Bocana", notificationText)
        } else {
            Log.d(TAG, "checkConditions - Conditions NOT MET. No notification shown.") // Log: No se muestra Notificación
        }
        Log.d(TAG, "checkConditions END ---->") // Log: Fin de la verificación
    }

    private fun showLocalNotification(title: String, content: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { return }
        }
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent).setAutoCancel(true)
        try {
            NotificationManagerCompat.from(this).notify(LOCAL_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) { Log.e(TAG, "SecurityException showing notification.", e) }
    }

    private fun fetchUserInfoAndLoadFragment(savedInstanceState: Bundle?) {
        val user = auth.currentUser ?: return
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (!isDestroyed && !isFinishing) {
                    if (document != null && document.exists()) {
                        val userData = document.toObject(User::class.java)
                        currentUserRole = if(userData?.role == UserRole.ADMIN) UserRole.ADMIN else null
                        currentUserName = userData?.name ?: "Usuario"

                        if (userData?.isAccountActive != true || currentUserRole != UserRole.ADMIN) {
                            showErrorAndLogout("Acceso denegado o cuenta inactiva.")
                            return@addOnSuccessListener
                        }
                        updateToolbarSubtitle()
                        invalidateOptionsMenu()
                        if (savedInstanceState == null) { loadInitialFragment() }
                    } else {
                        showErrorAndLogout("Error: Usuario no registrado como ADMIN.")
                    }
                }
            }
            .addOnFailureListener { exception ->
                if (!isDestroyed && !isFinishing) { showErrorAndLogout("Error conexión datos user.") }
            }
    }

    private fun fetchUserInfoOnly() {
        val user = auth.currentUser ?: return
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (!isDestroyed && !isFinishing) {
                    if (document != null && document.exists()) {
                        val userData = document.toObject(User::class.java)
                        val fetchedRole = if(userData?.role == UserRole.ADMIN) UserRole.ADMIN else null
                        if (userData?.isAccountActive != true || fetchedRole != UserRole.ADMIN) {
                            signOut()
                            return@addOnSuccessListener
                        }
                        currentUserName = userData?.name ?: "Usuario"
                        currentUserRole = fetchedRole
                        updateToolbarSubtitle()
                        invalidateOptionsMenu()
                    } else {
                        signOut()
                    }
                }
            }.addOnFailureListener { e ->
                if (!isDestroyed && !isFinishing) { Log.e(TAG, "Error re-fetch user info", e) }
            }
    }

    private fun updateToolbarSubtitle() {
        if (currentUserName != null && currentUserRole == UserRole.ADMIN) {
            supportActionBar?.subtitle = "$currentUserName - ${currentUserRole?.name}"
        } else {
            supportActionBar?.subtitle = currentUserName
        }
    }

    private fun loadInitialFragment() {
        if (supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) == null) {
            try {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, ProductListFragment())
                    .commitAllowingStateLoss()
            } catch (e: IllegalStateException) { Log.e(TAG, "Error al cargar fragmento inicial", e) }
        }
    }

    override fun onBackStackChanged() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
        val isAtHome = currentFragment is ProductListFragment || currentFragment == null
        supportActionBar?.setDisplayHomeAsUpEnabled(!isAtHome)
        supportActionBar?.setDisplayShowHomeEnabled(!isAtHome)
        if (isAtHome && currentUserName != null && currentUserRole != null) { updateToolbarSubtitle() }
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (menu != null) {
            val isAdmin = currentUserRole == UserRole.ADMIN
            val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
            val showAdminItems = isAdmin && (currentFragment is ProductListFragment)
            menu.findItem(R.id.action_ajustes)?.isVisible = showAdminItems
            menu.findItem(R.id.action_devoluciones)?.isVisible = showAdminItems
            menu.findItem(R.id.action_packaging)?.isVisible = showAdminItems

        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { supportFragmentManager.popBackStack(); return true }
            R.id.action_logout -> { signOut(); return true }
            R.id.action_ajustes -> { if (isAdmin()) navigateToAjustes(); return true }
            R.id.action_devoluciones -> { if (isAdmin()) navigateToDevoluciones(); return true }
            R.id.action_packaging -> { if (isAdmin()) navigateToPackaging(); return true }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun isAdmin(): Boolean = currentUserRole == UserRole.ADMIN

    private fun navigateToAjustes() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, AjustesFragment())
            .addToBackStack("AjustesFragment").commit()
    }
    private fun navigateToDevoluciones() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, DevolucionesFragment())
            .addToBackStack("DevolucionesFragment").commit()
    }
    private fun navigateToPackaging() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, PackagingFragment())
            .addToBackStack("PackagingFragment").commit()
    }

    private fun signOut() {
        Log.d(TAG, "signOut: Iniciando cierre de sesión...")
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        auth.signOut()
        googleSignInClient.signOut().addOnCompleteListener { goToLogin() }
    }

    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent); finish()
    }

    private fun showErrorAndLogout(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        signOut()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}