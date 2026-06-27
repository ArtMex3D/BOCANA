package com.cesar.bocana.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.User
import com.cesar.bocana.data.model.UserRole
import com.cesar.bocana.databinding.ActivitySplashBinding
import com.cesar.bocana.ui.main.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SplashActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val db = Firebase.firestore
    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth

        // Lanzamos la carga inteligente
        lifecycleScope.launch {
            // 1. Mostrar Robotín
            performTransition()
            val startTime = System.currentTimeMillis()
            val minAnimationTime = 2500L

            var destinationActivity: Class<*> = LoginActivity::class.java

            try {
                // 2. Tarea Pesada: Verificar conexión y precargar datos en Caché
                // Esto es lo que evita que las otras pantallas se queden "cargando"
                withContext(Dispatchers.IO) {
                    // Verificación de red
                    db.collection("app_config").document("update").get().await()

                    // Precarga de datos esenciales
                    db.collection("products").whereEqualTo("isActive", true).get().await()
                    db.collection("inventoryLots").whereEqualTo("isDepleted", false).get().await()
                }

                // 3. Verificación de usuario (Protección contra crasheo por usuario nulo)
                val currentUser = auth.currentUser
                if (currentUser != null) {
                    val isValid = withContext(Dispatchers.IO) { checkUserIsValid(currentUser.uid) }
                    if (isValid) {
                        destinationActivity = MainActivity::class.java
                    }
                }
            } catch (e: Exception) {
                Log.e("SplashActivity", "Error crítico en inicio: ${e.message}", e)
                // Si falla, el destino sigue siendo LoginActivity
            }

            // 4. Control de tiempo: Si el internet fue súper rápido, mantenemos a Robotín
            // el tiempo necesario para una transición fluida.
            val elapsedTime = System.currentTimeMillis() - startTime
            val remainingTime = minAnimationTime - elapsedTime
            if (remainingTime > 0) delay(remainingTime)

            // 5. Navegación Segura
            startActivity(Intent(this@SplashActivity, destinationActivity))
            finish()
        }
    }

    private fun performTransition() {
        binding.lottieAnimationView.visibility = View.VISIBLE
        binding.staticImageView.visibility = View.GONE
        binding.lottieAnimationView.playAnimation()
    }

    private suspend fun checkUserIsValid(uid: String): Boolean {
        return try {
            val userDoc = db.collection("users").document(uid).get().await()
            val user = userDoc.toObject(User::class.java)
            // Verificamos que el usuario tenga rol y esté activo
            user != null && user.role == UserRole.ADMIN && user.isAccountActive
        } catch (e: Exception) {
            Log.e("SplashActivity", "Error al validar usuario en Firestore", e)
            false
        }
    }
}