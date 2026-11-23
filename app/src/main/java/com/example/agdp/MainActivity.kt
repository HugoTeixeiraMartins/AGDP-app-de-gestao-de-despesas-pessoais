package com.agdp.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AgdpApp() } }
    }
}

@Composable
fun AgdpApp(vm: AppVM = viewModel()) {
    val loggedIn by vm.loggedIn.collectAsState()
    val msg by vm.message.collectAsState()
    val snack = remember { SnackbarHostState() }
    val nav = rememberNavController()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }

    val tabs = listOf(
        Triple("dashboard", "Início", Icons.Default.Home),
        Triple("add", "Recibo", Icons.Default.Add),
        Triple("history", "Histórico", Icons.Default.DateRange),
        Triple("family", "Família", Icons.Default.Person)
    )
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (loggedIn) NavigationBar {
                tabs.forEach { (r, label, icon) ->
                    NavigationBarItem(
                        selected = route == r, label = { Text(label) }, icon = { Icon(icon, label) },
                        onClick = { nav.navigate(r) { popUpTo("dashboard"); launchSingleTop = true } }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = if (loggedIn) "dashboard" else "login", modifier = Modifier.padding(pad)) {
            composable("login") { LoginScreen(vm) { nav.navigate("dashboard") { popUpTo("login") { inclusive = true } } } }
            composable("dashboard") { DashboardScreen(vm) }
            composable("add") { AddExpenseScreen(vm) { nav.navigate("history") } }
            composable("history") { HistoryScreen(vm) }
            composable("family") { FamilyScreen(vm) { nav.navigate("login") { popUpTo(0) } } }
        }
    }
}
