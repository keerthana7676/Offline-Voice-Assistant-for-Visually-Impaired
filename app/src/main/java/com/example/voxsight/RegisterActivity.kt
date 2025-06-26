package com.example.voxsight

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class RegisterActivity : AppCompatActivity() {
    private val auth = Firebase.auth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        findViewById<Button>(R.id.btnRegisterSubmit).setOnClickListener { registerUser() }
    }

    private fun registerUser() {
        val email = findViewById<EditText>(R.id.etEmail).text.toString().trim()
        val password = findViewById<EditText>(R.id.etPassword).text.toString()
        val confirmPassword = findViewById<EditText>(R.id.etConfirmPassword).text.toString()

        when {
            email.isEmpty() || password.isEmpty() ->
                Toast.makeText(this, "Email and password required", Toast.LENGTH_SHORT).show()
            password != confirmPassword ->
                Toast.makeText(this, "Passwords don't match", Toast.LENGTH_SHORT).show()
            password.length < 6 ->
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            else -> {
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Registration successful", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Registration failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }
}