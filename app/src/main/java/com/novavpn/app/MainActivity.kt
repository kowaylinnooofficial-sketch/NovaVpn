package com.novavpn.app

import android.app.Activity
import android.net.VpnService
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Navy = Color(0xFF071326)
private val Panel = Color(0xFF101F38)
private val Blue = Color(0xFF168BFF)
private val Cyan = Color(0xFF20DDF2)
private val Muted = Color(0xFF9EB2CC)
private val Green = Color(0xFF24D69A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.rgb(7, 19, 38)
        window.navigationBarColor = android.graphics.Color.rgb(7, 19, 38)
        setContent { NovaVpnScreen(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovaVpnScreen(activity: Activity) {
    val context = activity.applicationContext
    val scope = rememberCoroutineScope()
    var selectedProtocol by remember { mutableStateOf("WireGuard") }
    var connected by remember { mutableStateOf(WireGuardController.isConnected(context)) }
    var hasConfig by remember { mutableStateOf(WireGuardController.hasConfig(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (hasConfig) "WireGuard profile ready" else "Import a WireGuard or V2Ray profile") }
    var v2rayText by remember { mutableStateOf("") }
    var hasV2Ray by remember { mutableStateOf(V2RayProfileManager.hasProfile(context)) }
    var showV2RayImport by remember { mutableStateOf(false) }

    // Gentle breathing animation for the disconnected Connect control.
    val connectPulse = rememberInfiniteTransition(label = "connect-pulse")
    val pulseScale by connectPulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connect-pulse-scale"
    )

    fun runConnect() {
        scope.launch {
            busy = true
            try {
                if (selectedProtocol == "Xray") {
                    status = "Starting Xray tunnel…"
                    XrayVpnService.start(context)
                } else {
                    status = "Starting WireGuard tunnel…"
                    withContext(Dispatchers.IO) { WireGuardController.connect(context) }
                    connected = withContext(Dispatchers.IO) { WireGuardController.isConnected(context) }
                    status = if (connected) "WireGuard tunnel is active" else "Tunnel did not report connected"
                }
            } catch (e: Exception) {
                connected = false
                status = e.message ?: "Connection failed. Check the profile and server."
            } finally { busy = false }
        }
    }

    val currentProtocol by rememberUpdatedState(selectedProtocol)
    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                if (intent?.action == XrayVpnService.ACTION_STATE) {
                    val message = intent.getStringExtra(XrayVpnService.EXTRA_MESSAGE) ?: "Xray state updated"
                    if (intent.hasExtra(XrayVpnService.EXTRA_IMPORTED)) {
                        hasV2Ray = intent.getBooleanExtra(XrayVpnService.EXTRA_IMPORTED, false)
                        status = message
                    } else if (currentProtocol == "Xray") {
                        connected = intent.getBooleanExtra(XrayVpnService.EXTRA_CONNECTED, false)
                        status = message
                    }
                }
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(XrayVpnService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { try { activity.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) runConnect()
        else status = "VPN permission was not granted"
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Could not read the selected file")
                    }
                    withContext(Dispatchers.IO) { WireGuardController.importConfig(context, bytes) }
                    hasConfig = true
                    status = "Profile imported and validated. Tap Connect."
                } catch (e: Exception) {
                    status = e.message ?: "Invalid WireGuard configuration"
                } finally { busy = false }
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue, secondary = Cyan, background = Navy, surface = Panel,
            onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Navy,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, null, tint = Cyan, modifier = Modifier.size(27.dp))
                            Spacer(Modifier.width(9.dp))
                            Text("NovaVPN", fontWeight = FontWeight.Bold, letterSpacing = .3.sp)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Navy, titleContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = Color.White) {
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = false, onClick = { showV2RayImport = true }, icon = { Icon(Icons.Default.Dns, null) }, label = { Text("V2Ray") })
                    NavigationBarItem(selected = false, onClick = { selectedProtocol = "Xray"; connected = XrayVpnService.isConnected(context); status = "Xray Core selected" }, icon = { Icon(Icons.Default.Bolt, null) }, label = { Text("Xray") })
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .background(Brush.verticalGradient(listOf(Color(0xFF0B2344), Navy)))
                    .padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(22.dp))
                Text("YOUR PRIVATE CONNECTION", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("WireGuard", "Xray").forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = selectedProtocol == item,
                            onClick = {
                                selectedProtocol = item
                                connected = if (item == "Xray") XrayVpnService.isConnected(context) else WireGuardController.isConnected(context)
                                status = if (item == "Xray") "Xray Core selected" else "WireGuard selected"
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)
                        ) { Text(item) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(7.dp))
                Text(
                    if (connected) "You are protected" else "Secure your connection",
                    color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = .96f))
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(if (connected) Green else Muted, CircleShape))
                            Spacer(Modifier.width(9.dp))
                            Text(
                                if (connected) "CONNECTED" else "NOT CONNECTED",
                                color = if (connected) Green else Muted,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp
                            )
                        }
                        Spacer(Modifier.height(25.dp))
                        Box(
                            Modifier.size(174.dp)
                                .graphicsLayer {
                                    val scale = if (!connected && !busy) pulseScale else 1f
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .background(
                                    Brush.radialGradient(listOf(Blue.copy(alpha = .34f), Cyan.copy(alpha = .07f))),
                                    CircleShape
                                ).padding(12.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF123C72), Color(0xFF0B1A31))),
                                    CircleShape
                                )
                                .clickable(enabled = !busy) {
                                    if (connected) {
                                        scope.launch {
                                            busy = true
                                            try {
                                                if (selectedProtocol == "Xray") {
                                                    XrayVpnService.stop(context)
                                                    connected = false
                                                    status = "Stopping Xray tunnel…"
                                                } else {
                                                    withContext(Dispatchers.IO) { WireGuardController.disconnect(context) }
                                                    connected = false
                                                    status = "Disconnected"
                                                }
                                            } catch (e: Exception) {
                                                status = e.message ?: "Disconnect failed"
                                            } finally { busy = false }
                                        }
                                    } else {
                                        if (selectedProtocol == "WireGuard" && !hasConfig) {
                                            status = "Import a valid WireGuard .conf file first"
                                        } else if (selectedProtocol == "Xray" && !hasV2Ray) {
                                            status = "Import a VMess/V2Ray profile first"
                                        } else {
                                            val intent = VpnService.prepare(activity)
                                            if (intent == null) runConnect() else permissionLauncher.launch(intent)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(42.dp),
                                    color = Cyan,
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    if (connected) Icons.Default.Lock else Icons.Default.PowerSettingsNew,
                                    contentDescription = if (connected) "Disconnect" else "Connect",
                                    tint = if (connected) Green else Color.White,
                                    modifier = Modifier.size(62.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (busy) "PLEASE WAIT" else if (connected) "TAP TO DISCONNECT" else "TAP TO CONNECT",
                            color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = .08f))
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, tint = Cyan, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("$selectedProtocol protocol", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (selectedProtocol == "Xray") if (hasV2Ray) "Xray profile imported" else "No Xray profile imported"
                                    else if (hasConfig) "WireGuard configuration imported" else "No WireGuard profile imported",
                                    color = Muted, fontSize = 12.sp
                                )
                            }
                            val profileReady = if (selectedProtocol == "Xray") hasV2Ray else hasConfig
                            Icon(if (profileReady) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                                null, tint = if (profileReady) Green else Muted)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    enabled = !busy && selectedProtocol == "WireGuard",
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(9.dp))
                    Text(if (hasConfig) "Import another .conf file" else "Import WireGuard .conf")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showV2RayImport = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (hasV2Ray) "V2Ray / VMess profile imported" else "Import VMess link / V2Ray JSON")
                }
                Spacer(Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1A30)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(status, color = Muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("WIREGUARD  •  VMESS  •  XRAY CORE", color = Muted, fontSize = 10.sp, letterSpacing = 1.1.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
        if (showV2RayImport) {
            AlertDialog(
                onDismissRequest = { showV2RayImport = false },
                containerColor = Panel,
                title = { Text("Import V2Ray / VMess", color = Color.White) },
                text = {
                    Column {
                        Text("Paste a vmess:// link or Xray JSON configuration.", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = v2rayText,
                            onValueChange = { v2rayText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 220.dp),
                            placeholder = { Text("vmess://... or Xray JSON") },
                            minLines = 4
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            XrayVpnService.importProfile(context, v2rayText)
                            status = "Checking share link / Xray profile…"
                            v2rayText = ""
                            showV2RayImport = false
                        } catch (e: Exception) {
                            status = e.message ?: "Could not import V2Ray profile"
                        }
                    }) { Text("IMPORT", color = Cyan) }
                },
                dismissButton = { TextButton(onClick = { showV2RayImport = false }) { Text("CANCEL", color = Muted) } }
            )
        }

    }
}
