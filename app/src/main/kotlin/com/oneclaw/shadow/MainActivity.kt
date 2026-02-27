package com.oneclaw.shadow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.oneclaw.shadow.navigation.AppNavGraph
import com.oneclaw.shadow.tool.engine.PermissionChecker
import com.oneclaw.shadow.ui.theme.OneClawShadowTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val permissionChecker: PermissionChecker by inject()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionChecker.onPermissionResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionChecker.bindToActivity(permissionLauncher)
        enableEdgeToEdge()
        setContent {
            OneClawShadowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)
                }
            }
        }
    }

    override fun onDestroy() {
        permissionChecker.unbind()
        super.onDestroy()
    }
}
