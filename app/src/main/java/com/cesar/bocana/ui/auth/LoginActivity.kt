package com.cesar.bocana.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.User
import com.cesar.bocana.data.model.UserRole
import com.cesar.bocana.databinding.ActivityLoginBinding
import com.cesar.bocana.ui.main.MainActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>
    private val db = Firebase.firestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        googleSignInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                try {
                    val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)!!
                    firebaseAuthWithGoogle(account.idToken!!)
                } catch (e: ApiException) {
                    showLoading(false)
                    showError("Error al iniciar sesión con Google: ${e.statusCode}")
                }
            } else {
                showLoading(false)
            }
        }
        binding.signInButton.setOnClickListener { signIn() }
        // Al iniciar, no mostramos la carga, sino el botón de login
        showLoading(false)
    }

    private fun signIn() {
        showLoading(true)
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                lifecycleScope.launch {
                    val isValid = checkUserIsValid()
                    if (isValid) {
                        navigateToMain()
                    } else {
                        showError("Acceso denegado.")
                        signOutCleanup()
                        showLoading(false)
                    }
                }
            } else {
                showLoading(false)
                showError("Error al autenticar con Firebase.")
                signOutCleanup()
            }
        }
    }

    private suspend fun checkUserIsValid(): Boolean {
        return try {
            val userDoc = db.collection("users").document(auth.currentUser!!.uid).get().await()
            val user = userDoc.toObject<User>()
            user?.role == UserRole.ADMIN && user.isAccountActive
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al verificar la validez del usuario", e)
            false
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finishAffinity()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.loadingViewContainer.root.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.signInButton.visibility = if (!isLoading) View.VISIBLE else View.GONE
        binding.signInButton.isEnabled = !isLoading
    }

    private fun signOutCleanup() {
        lifecycleScope.launch {
            try {
                auth.signOut()
                googleSignInClient.signOut().await()
            } catch (e: Exception) {
                Log.w(TAG, "Error durante la limpieza de sesión de Google", e)
            }
        }
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}