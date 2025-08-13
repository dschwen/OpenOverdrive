package feature.discovery

import android.Manifest
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import core.ble.BleClient
import core.ble.AndroidBleClient
import core.ble.BleDevice
import data.CarRepository
import data.CarProfile
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DiscoveryScreen(
    onConnected: (String) -> Unit,
    bleClient: BleClient = run {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        remember { AndroidBleClient(ctx) }
    },
    carRepositoryProvider: (BleClient) -> CarRepository = { ble ->
        // In app we’ll provide a real instance via DI; here use a placeholder that won’t crash.
        CarRepository(androidx.compose.ui.platform.LocalContext.current, ble)
    }
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val carRepo = remember { carRepositoryProvider(bleClient) }
    val snackbarHostState = remember { SnackbarHostState() }

    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        rememberMultiplePermissionsState(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    } else {
        rememberMultiplePermissionsState(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    var items by remember { mutableStateOf<List<Pair<BleDevice, CarProfile?>>>(emptyList()) }
    var menuForAddress by remember { mutableStateOf<String?>(null) }
    var colorPickerFor by remember { mutableStateOf<Pair<String, CarProfile?>?>(null) }
    var nameEditorFor by remember { mutableStateOf<Pair<String, CarProfile?>?>(null) }
    val profiles by carRepo.profilesFlow.collectAsState(initial = emptyList())
    val connState by bleClient.connectionState.collectAsState()
    var autoConnectAttempted by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(permissions.allPermissionsGranted) {
        if (!permissions.allPermissionsGranted) return@LaunchedEffect
        carRepo.visibleWithProfiles(bleClient.scanForAnkiDevices())
            .collectLatest { list -> items = list }
    }

    // Auto-connect when a known car with autoConnect=true becomes visible and we're idle.
    LaunchedEffect(items, profiles, connState) {
        val isIdle = connState is core.ble.ConnectionState.Disconnected
        if (!isIdle) return@LaunchedEffect
        // Prefer most recently connected auto-connect profile that is visible
        val visibleProfiles = items.mapNotNull { (dev, prof) ->
            val p = prof ?: profiles.find { it.deviceAddress == dev.address }
            if (p?.autoConnect == true) p to dev.address else null
        }
        val target = visibleProfiles.maxByOrNull { it.first.lastConnected ?: 0L } ?: return@LaunchedEffect
        val addr = target.second
        if (!autoConnectAttempted.contains(addr)) {
            autoConnectAttempted = autoConnectAttempted + addr
            val label = target.first.displayName ?: target.first.lastSeenName ?: addr
            scope.launch { snackbarHostState.showSnackbar("Auto-connecting to $label") }
            bleClient.connect(addr)
            onConnected(addr)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Discover & Connect") }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!permissions.allPermissionsGranted) {
                Text(
                    text = "Bluetooth permissions required to scan.",
                    modifier = Modifier.padding(16.dp)
                )
                Button(onClick = { permissions.launchMultiplePermissionRequest() }, modifier = Modifier.padding(16.dp)) {
                    Text("Grant Permissions")
                }
            }

            val visibleAddresses = remember(items) { items.map { it.first.address }.toSet() }

            Text("Nearby Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(items) { (dev, profile) ->
                    DeviceRow(
                        device = dev,
                        profile = profile,
                        onConnect = {
                            scope.launch {
                                val updated = (profile ?: CarProfile(deviceAddress = dev.address)).copy(
                                    lastSeenName = dev.name,
                                    lastConnected = System.currentTimeMillis()
                                )
                                carRepo.upsertProfile(updated)
                                bleClient.connect(dev.address)
                                onConnected(dev.address)
                            }
                        },
                        onOpenMenu = { addr -> menuForAddress = addr }
                    )
                    DropdownMenu(expanded = menuForAddress == dev.address, onDismissRequest = { menuForAddress = null }) {
                        DropdownMenuItem(
                            text = { Text("Set Color") },
                            onClick = {
                                menuForAddress = null
                                colorPickerFor = dev.address to profile
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Name") },
                            onClick = {
                                menuForAddress = null
                                nameEditorFor = dev.address to profile
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Forget") },
                            onClick = {
                                menuForAddress = null
                                scope.launch { carRepo.removeProfile(dev.address) }
                            }
                        )
                    }
                    Divider()
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Known Cars", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(profiles) { prof ->
                    val isVisible = visibleAddresses.contains(prof.deviceAddress)
                    KnownCarRow(
                        profile = prof,
                        isVisible = isVisible,
                        onConnect = {
                            if (isVisible) {
                                scope.launch {
                                    bleClient.connect(prof.deviceAddress)
                                    onConnected(prof.deviceAddress)
                                }
                            }
                        },
                        onOpenMenu = { addr -> menuForAddress = addr },
                        onSetAutoConnect = { checked ->
                            scope.launch { carRepo.upsertProfile(prof.copy(autoConnect = checked)) }
                        }
                    )
                    DropdownMenu(expanded = menuForAddress == prof.deviceAddress, onDismissRequest = { menuForAddress = null }) {
                        DropdownMenuItem(text = { Text("Set Color") }, onClick = {
                            menuForAddress = null
                            colorPickerFor = prof.deviceAddress to prof
                        })
                        DropdownMenuItem(text = { Text("Edit Name") }, onClick = {
                            menuForAddress = null
                            nameEditorFor = prof.deviceAddress to prof
                        })
                        DropdownMenuItem(text = { Text("Forget") }, onClick = {
                            menuForAddress = null
                            scope.launch { carRepo.removeProfile(prof.deviceAddress) }
                        })
                    }
                    Divider()
                }
            }

            colorPickerFor?.let { (addr, prof) ->
                ColorPickerDialog(
                    onDismiss = { colorPickerFor = null },
                    onPick = { argb ->
                        scope.launch {
                            val next = (prof ?: CarProfile(deviceAddress = addr)).copy(colorArgb = argb)
                            carRepo.upsertProfile(next)
                            colorPickerFor = null
                        }
                    }
                )
            }

            nameEditorFor?.let { (addr, prof) ->
                NameEditorDialog(
                    initial = prof?.displayName ?: "",
                    onDismiss = { nameEditorFor = null },
                    onConfirm = { name ->
                        scope.launch {
                            val next = (prof ?: CarProfile(deviceAddress = addr)).copy(displayName = name.ifBlank { null })
                            carRepo.upsertProfile(next)
                            nameEditorFor = null
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BleDevice,
    profile: CarProfile?,
    onConnect: () -> Unit,
    onOpenMenu: (String) -> Unit
) {
    ListItem(
        leadingContent = {
            profile?.colorArgb?.let { ColorSwatch(it) }
        },
        headlineText = { Text(profile?.displayName ?: device.name ?: device.address) },
        supportingText = { Text(device.address) },
        trailingContent = {
            TextButton(onClick = { onOpenMenu(device.address) }) { Text("⋮") }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect() }
    )
}

@Composable
private fun KnownCarRow(
    profile: CarProfile,
    isVisible: Boolean,
    onConnect: () -> Unit,
    onOpenMenu: (String) -> Unit,
    onSetAutoConnect: (Boolean) -> Unit
) {
    val subtitle = buildString {
        append(profile.deviceAddress)
        if (!isVisible) append(" • Not visible") else append(" • Visible now")
    }
    ListItem(
        leadingContent = { profile.colorArgb?.let { ColorSwatch(it) } },
        headlineText = { Text(profile.displayName ?: profile.lastSeenName ?: profile.deviceAddress) },
        supportingText = { Text(subtitle) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-connect")
                Switch(checked = profile.autoConnect, onCheckedChange = onSetAutoConnect)
                OutlinedButton(onClick = onConnect, enabled = isVisible) { Text("Connect") }
                TextButton(onClick = { onOpenMenu(profile.deviceAddress) }) { Text("⋮") }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ColorSwatch(argb: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(argb), shape = MaterialTheme.shapes.small)
    )
}

@Composable
private fun ColorPickerDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val colors = listOf(
        0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFFFDD835.toInt(),
        0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00BCD4.toInt(), 0xFF9E9E9E.toInt(),
        0xFFFFFFFF.toInt(), 0xFF000000.toInt()
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    colors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(c), shape = MaterialTheme.shapes.small)
                                .clickable { onPick(c) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun NameEditorDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit name") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
