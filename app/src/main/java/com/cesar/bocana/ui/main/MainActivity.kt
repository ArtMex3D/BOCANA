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
            Log.d(TAG, "Notification permission granted.")
            getAndSaveFcmToken()
        } else {
            Log.w(TAG, "Notification permission denied.")
            // Consider showing a message explaining why notifications are useful
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
            fetchUserInfoOnly() // Re-fetch user info in case role/status changed
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
            Log.d(TAG, "Notification channel created.")
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted.")
                    getAndSaveFcmToken() // Get token if permission already granted
                }
                // Consider adding shouldShowRequestPermissionRationale if you want to explain first
                else -> {
                    Log.d(TAG, "Requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // No runtime permission needed for older versions, just get token
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
                    // Use merge=true to avoid overwriting other fields if doc exists but token wasn't set
                    userDocRef.set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
                        .await() // Wait for the update to complete
                    Log.d(TAG, "FCM Token potentially updated in Firestore.")
                } else {
                    Log.w(TAG, "FCM Token was null.")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error getting/saving FCM token", e)
                // Handle error appropriately, maybe retry later
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
                // Cannot query where totalStock <= minStock directly if minStock > 0 in Firestore efficiently without specific structure/indexes
                // Fetch active products and check condition in code
                .get()
                .await()

            lowStockCount = lowStockSnapshot.documents.count { doc ->
                val product = doc.toObject(Product::class.java)
                // Compare Doubles, check minStock > 0.0
                product != null && product.minStock > 0.0 && product.totalStock <= product.minStock
            }
            Log.d(TAG, "checkConditions - Low Stock Check: Found $lowStockCount item(s).")
            if (lowStockCount > 0) {
                alertMessages.add("$lowStockCount prod. stock bajo")
            }

            val pendingDevoSnapshot = db.collection("pendingDevoluciones")
                .whereEqualTo("status", DevolucionStatus.PENDIENTE.name) // Filter by PENDING status
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
                return // Exit if permission not granted on Android 13+
            }
        }
        // Permission ok or not needed

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        // Use FLAG_IMMUTABLE or FLAG_MUTABLE as required by target SDK
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round) // Use round icon perhaps
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Notification disappears when clicked

        try {
            NotificationManagerCompat.from(this).notify(LOCAL_NOTIFICATION_ID, builder.build())
            Log.d(TAG, "Local notification shown.")
        } catch (e: SecurityException) {
            // This might happen on some devices if permissions change unexpectedly
            Log.e(TAG, "SecurityException showing notification.", e)
        }
    }


    private fun fetchUserInfoAndLoadFragment(savedInstanceState: Bundle?) {
        val user = auth.currentUser ?: run { goToLogin(); return } // Go to login if user is null
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (!isDestroyed && !isFinishing) { // Check activity state
                    if (document != null && document.exists()) {
                        val userData = document.toObject(User::class.java)
                        currentUserRole = if(userData?.role == UserRole.ADMIN) UserRole.ADMIN else null
                        currentUserName = userData?.name ?: "Usuario"

                        if (userData?.isAccountActive != true || currentUserRole != UserRole.ADMIN) {
                            showErrorAndLogout("Acceso denegado o cuenta inactiva.")
                            return@addOnSuccessListener
                        }
                        updateToolbarSubtitle()
                        invalidateOptionsMenu() // Redraw menu based on role
                        if (savedInstanceState == null) { // Only load fragment if not recreating
                            loadInitialFragment()
                        }
                    } else {
                        Log.w(TAG,"User document not found for UID: ${user.uid}")
                        showErrorAndLogout("Error: Usuario no registrado como ADMIN.")
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
        val user = auth.currentUser ?: return // Don't proceed if user is null
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (!isDestroyed && !isFinishing) {
                    if (document != null && document.exists()) {
                        val userData = document.toObject(User::class.java)
                        val fetchedRole = if(userData?.role == UserRole.ADMIN) UserRole.ADMIN else null

                        // If user is no longer active or admin, log them out
                        if (userData?.isAccountActive != true || fetchedRole != UserRole.ADMIN) {
                            Log.w(TAG,"User status changed (inactive or not admin), logging out.")
                            signOut()
                            return@addOnSuccessListener
                        }

                        // Update local state if changed
                        var invalidateNeeded = false
                        if (currentUserName != (userData?.name ?: "Usuario")) {
                            currentUserName = userData?.name ?: "Usuario"
                            invalidateNeeded = true
                        }
                        if (currentUserRole != fetchedRole) {
                            currentUserRole = fetchedRole
                            invalidateNeeded = true
                        }

                        if(invalidateNeeded){
                            updateToolbarSubtitle()
                            invalidateOptionsMenu() // Update menu if role/name changed
                        }

                    } else {
                        // User document disappeared? Log out.
                        Log.w(TAG,"User document not found during re-fetch, logging out.")
                        signOut()
                    }
                }
            }.addOnFailureListener { e ->
                if (!isDestroyed && !isFinishing) {
                    Log.e(TAG, "Error re-fetching user info", e)
                    // Decide if logout is needed on connection error during re-fetch
                    // Maybe just log it for now?
                }
            }
    }

    private fun updateToolbarSubtitle() {
        if (currentUserName != null && currentUserRole == UserRole.ADMIN) {
            supportActionBar?.subtitle = "$currentUserName - ${currentUserRole?.name}"
        } else {
            supportActionBar?.subtitle = currentUserName ?: "" // Handle null case
        }
    }

    private fun loadInitialFragment() {
        if (supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) == null) {
            try {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, ProductListFragment())
                    .commitAllowingStateLoss() // Use allowingStateLoss cautiously if needed, otherwise commit()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error loading initial fragment, activity state invalid?", e)
                // Potentially handle this by trying again later or showing an error
            }
        }
    }

    override fun onBackStackChanged() {
        // Re-evaluate toolbar state based on current fragment
        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
        val isAtHome = currentFragment is ProductListFragment || supportFragmentManager.backStackEntryCount == 0 // Check backStack too

        supportActionBar?.setDisplayHomeAsUpEnabled(!isAtHome)
        supportActionBar?.setDisplayShowHomeEnabled(!isAtHome)

        // Restore subtitle only if we are truly back at the home fragment
        if (isAtHome && currentUserName != null ) { // Removed role check, name is enough
            updateToolbarSubtitle()
        } else if (!isAtHome) {
            // Title might have been set by the specific fragment (like AjustesFragment)
            // Subtitle is usually cleared by fragments, handled by restoreToolbar in fragments
        }
        invalidateOptionsMenu() // Update menu visibility based on current fragment
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // This ensures the menu reflects the current state and fragment
        if (menu != null) {
            val isAdmin = currentUserRole == UserRole.ADMIN
            val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
            // Only show admin items if user is admin AND is on the ProductListFragment
            val showAdminItems = isAdmin && (currentFragment is ProductListFragment)

            menu.findItem(R.id.action_ajustes)?.isVisible = showAdminItems
            menu.findItem(R.id.action_devoluciones)?.isVisible = showAdminItems
            menu.findItem(R.id.action_packaging)?.isVisible = showAdminItems
        }
        return super.onPrepareOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle Up navigation specifically
        if (item.itemId == android.R.id.home) {
            supportFragmentManager.popBackStack()
            return true
        }

        // Handle other menu items only if user is Admin and on the ProductListFragment
        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
        if (!(currentFragment is ProductListFragment && isAdmin())) {
            // If not admin or not on product list, only allow logout
            if (item.itemId == R.id.action_logout) {
                signOut()
                return true
            }
            return super.onOptionsItemSelected(item) // Let system handle if not logout
        }

        // If we are here, user is Admin and on ProductListFragment
        return when (item.itemId) {
            R.id.action_logout -> { signOut(); true }
            R.id.action_ajustes -> { navigateToAjustes(); true }
            R.id.action_devoluciones -> { navigateToDevoluciones(); true }
            R.id.action_packaging -> { navigateToPackaging(); true }
            else -> super.onOptionsItemSelected(item)
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
        Log.d(TAG, "signOut: Initiating sign out...")
        // Clear local user state immediately
        currentUserRole = null
        currentUserName = null
        invalidateOptionsMenu()
        updateToolbarSubtitle()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // Use your web client ID
            .requestEmail()
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Sign out from Firebase
        auth.signOut()

        // Sign out from Google and then navigate to Login
        googleSignInClient.signOut().addOnCompleteListener {
            Log.d(TAG, "Google sign out complete, navigating to Login.")
            goToLogin()
        }
    }

    private fun goToLogin() {
        if (!isFinishing) { // Prevent crash if activity is already finishing
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finishAffinity() // Close all activities in the task
        }
    }

    private fun showErrorAndLogout(message: String) {
        Log.e(TAG, "Error causing logout: $message")
        // Ensure Toast runs on main thread, although typically called from listeners already on main
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        signOut() // Initiate sign out process
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}