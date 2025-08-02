package com.example.callanalytics

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.callanalytics.fragments.DashboardFragment
import com.example.callanalytics.fragments.CallLogFragment
import com.example.callanalytics.fragments.SettingsFragment
import com.example.callanalytics.utils.WebSocketManager

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 1001
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var webSocketManager: WebSocketManager

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.MANAGE_OWN_CALLS,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupBottomNavigation()
        checkAndRequestPermissions()

        // Initialize WebSocket Manager
        webSocketManager = WebSocketManager.getInstance(this)

        // Load default fragment
        if (savedInstanceState == null) {
            loadFragment(DashboardFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        // Connect WebSocket when app comes to foreground
        webSocketManager.connect()
    }

    override fun onPause() {
        super.onPause()
        // Keep WebSocket connected in background for call monitoring
        // Only disconnect on app destroy
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disconnect WebSocket when app is destroyed
        webSocketManager.disconnect()
    }

    private fun setupBottomNavigation() {
        bottomNavigation = findViewById(R.id.bottomNavigation)

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    loadFragment(DashboardFragment())
                    true
                }
                R.id.nav_calls -> {
                    loadFragment(CallLogFragment())
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Toast.makeText(this, "✅ All permissions granted! Call monitoring active.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Toast.makeText(this, "✅ All permissions granted! Call monitoring active.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "❌ Some permissions denied. App may not work properly.", Toast.LENGTH_LONG).show()
            }
        }
    }
}