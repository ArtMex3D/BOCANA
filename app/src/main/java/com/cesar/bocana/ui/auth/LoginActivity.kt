package com.cesar.bocana.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View // Verifica que este import esté presente
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
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)!!
                    firebaseAuthWithGoogle(account.idToken!!)
                } catch (e: ApiException) {
                    Log.w(TAG, "Google sign in failed", e)
                    showError("Error al iniciar sesión con Google: ${e.statusCode}")
                    showLoading(false)
                }
            } else {
                Log.w(TAG, "Google sign in cancelled or failed. Result code: ${result.resultCode}")
                showLoading(false)
            }
        }
        binding.signInButton.setOnClickListener { signIn() }
    }

    override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            showLoading(true)
            lifecycleScope.launch { checkUserIsAdminAndActive(currentUser) }
        } else {
            showLoading(false)
        }
    }

    private fun signIn() {
        showLoading(true)
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val firebaseUser = auth.currentUser
                    if (firebaseUser != null) {
                        lifecycleScope.launch { checkUserIsAdminAndActive(firebaseUser) }
                    } else {
                        showError("Error inesperado al obtener usuario.")
                        showLoading(false)
                    }
                } else {
                    Log.w(TAG, "Firebase Auth Failed", task.exception)
                    showError("Error al autenticar con Firebase.")
                    showLoading(false)
                    signOutCleanup()
                }
            }
    }

    private suspend fun checkUserIsAdminAndActive(firebaseUser: FirebaseUser) {
        var shouldHideLoadingFinally = true
        try {
            Log.d(TAG, "Verificando si ${firebaseUser.email} es ADMIN y está activo.")
            val userDocRef = db.collection("users").document(firebaseUser.uid)
            val userDocSnapshot = userDocRef.get().await()

            if (userDocSnapshot.exists()) {
                val existingUser = userDocSnapshot.toObject<User>()
                if (existingUser != null && existingUser.role == UserRole.ADMIN && existingUser.isAccountActive) {
                    Log.d(TAG, "Usuario ${existingUser.email} es ADMIN y está activo. Navegando...")
                    shouldHideLoadingFinally = false
                    withContext(Dispatchers.Main) { navigateToMain() }
                } else {
                    Log.w(TAG, "Acceso denegado para ${firebaseUser.email}. Rol: ${existingUser?.role}, Activo: ${existingUser?.isAccountActive}")
                    withContext(Dispatchers.Main) {
                        showError("Acceso denegado. Verifica tu cuenta o contacta al administrador.")
                        signOutCleanup()
                    }
                }
            } else {
                Log.w(TAG, "Acceso denegado: Usuario ${firebaseUser.email} no encontrado en la base de datos de usuarios.")
                withContext(Dispatchers.Main) {
                    showError("Acceso denegado. Usuario no registrado.")
                    signOutCleanup()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando estado del usuario", e)
            withContext(Dispatchers.Main) {
                showError("Error de conexión al verificar usuario.")
                signOutCleanup()
            }
        } finally {
            if (shouldHideLoadingFinally) {
                withContext(Dispatchers.Main) { showLoading(false) }
            }
        }
    }

    private fun navigateToMain() {
        Log.d(TAG, "navigateToMain: Navegando a MainActivity")
        val intent = Intent(this@LoginActivity, MainActivity::class.java)
        startActivity(intent)
        finishAffinity()
    }

    private fun showError(message: String) {
        Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
    }

    // --- FUNCIÓN showLoading CORREGIDA ---
    private fun showLoading(isLoading: Boolean) {
        // Usamos .root para acceder a la vista raíz del layout incluido
        binding.loadingViewContainer.root.visibility = if (isLoading) View.VISIBLE else View.GONE

        // signInButton es una vista directa, así que el acceso es normal
        binding.signInButton.visibility = if (!isLoading) View.VISIBLE else View.GONE
        binding.signInButton.isEnabled = !isLoading
    }
    // --- FIN FUNCIÓN showLoading CORREGIDA ---

    private fun signOutCleanup() {
        lifecycleScope.launch {
            try {
                auth.signOut()
                googleSignInClient.signOut().await()
                Log.d(TAG, "Google Sign Out successful")
            } catch (e: Exception) {
                Log.w(TAG, "Error durante Google Sign Out cleanup", e)
            } finally {
                withContext(Dispatchers.Main) { showLoading(false) }
            }
        }
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}