// Archivo: main/java/com/cesar/bocana/ui/auth/SplashActivity.kt
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
        window.setBackgroundDrawable(null)

        auth = Firebase.auth

        lifecycleScope.launch {
            // Un pequeño delay para que el usuario perciba la imagen estática
            delay(500)

            performTransition()

            // Un delay más largo para disfrutar la animación mientras se validan las credenciales
            val animationWaitTime = 2500L
            val currentUser = auth.currentUser

            if (currentUser != null) {
                val isValid = withContext(Dispatchers.IO) { checkUserIsValid() }
                delay(animationWaitTime)
                if (isValid) {
                    navigateTo(MainActivity::class.java)
                } else {
                    navigateTo(LoginActivity::class.java)
                }
            } else {
                delay(animationWaitTime)
                navigateTo(LoginActivity::class.java)
            }
        }
    }

    private fun performTransition() {
        // 1. Hacemos visible el Lottie (está en pausa en su primer frame)
        binding.lottieAnimationView.visibility = View.VISIBLE

        // 2. Ocultamos la imagen estática. Como están perfectamente superpuestas,
        //    el cambio es invisible para el ojo humano.
        binding.staticImageView.visibility = View.GONE

        // 3. ¡Iniciamos la animación!
        binding.lottieAnimationView.playAnimation()
    }

    private suspend fun checkUserIsValid(): Boolean {
        return try {
            val userDoc = db.collection("users").document(auth.currentUser!!.uid).get().await()
            val user = userDoc.toObject(User::class.java)
            user?.role == UserRole.ADMIN && user.isAccountActive
        } catch (e: Exception) {
            Log.e("SplashActivity", "Error verificando usuario", e)
            false
        }
    }

    private fun <T> navigateTo(activityClass: Class<T>) {
        if (!isFinishing) {
            val intent = Intent(this, activityClass)
            startActivity(intent)
            finish()
        }
    }
}