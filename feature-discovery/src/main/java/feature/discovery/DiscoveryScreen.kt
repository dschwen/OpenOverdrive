package feature.discovery

import android.Manifest
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import core.ble.BleClient
import core.ble.AndroidBleClient
import core.ble.BleDevice
import data.CarRepository
import data.CarProfile
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onConnected: (String) -> Unit,
    bleClient: BleClient? = null,
    carRepositoryProvider: (android.content.Context, BleClient) -> CarRepository = { ctx, ble -> CarRepository(ctx, ble) },
    onOpenDiagnostics: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = bleClient ?: remember { AndroidBleClient(context) }
    val carRepo = remember { carRepositoryProvider(context, client) }
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
    var confirmForgetFor by remember { mutableStateOf<String?>(null) }
    // Profiles are stored for color/name, but we don't render a separate list here.

    LaunchedEffect(permissions.allPermissionsGranted) {
        if (!permissions.allPermissionsGranted) return@LaunchedEffect
        carRepo.visibleWithProfiles(client.scanForAnkiDevices())
            .collectLatest { list -> items = list }
    }

    // Removed auto-connect for a simpler, more explicit UX.

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover & Connect") },
                actions = {
                    TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
                }
            )
        },
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

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Nearby Devices", style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(items) { (dev, profile) ->
                    DeviceRow(
                        device = dev,
                        profile = profile,
                        menuExpanded = menuForAddress == dev.address,
                        onOpenMenu = { addr -> menuForAddress = addr },
                        onDismissMenu = { menuForAddress = null },
                        onConnect = {
                            scope.launch {
                                val updated = (profile ?: CarProfile(deviceAddress = dev.address)).copy(
                                    lastSeenName = dev.name,
                                    lastConnected = System.currentTimeMillis()
                                )
                                carRepo.upsertProfile(updated)
                                client.connect(dev.address)
                                onConnected(dev.address)
                            }
                        },
                        onPickColor = {
                            colorPickerFor = dev.address to profile
                            menuForAddress = null
                        },
                        onEditName = {
                            nameEditorFor = dev.address to profile
                            menuForAddress = null
                        },
                        onForget = {
                            confirmForgetFor = dev.address
                            menuForAddress = null
                        }
                    )
                    HorizontalDivider()
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
            confirmForgetFor?.let { addr ->
                AlertDialog(
                    onDismissRequest = { confirmForgetFor = null },
                    title = { Text("Forget car?") },
                    text = { Text("Remove saved name and color for $addr?") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                carRepo.removeProfile(addr)
                                confirmForgetFor = null
                            }
                        }) { Text("Forget") }
                    },
                    dismissButton = { TextButton(onClick = { confirmForgetFor = null }) { Text("Cancel") } }
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BleDevice,
    profile: CarProfile?,
    menuExpanded: Boolean,
    onConnect: () -> Unit,
    onOpenMenu: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onPickColor: () -> Unit,
    onEditName: () -> Unit,
    onForget: () -> Unit
) {
    ListItem(
        leadingContent = { profile?.colorArgb?.let { ColorSwatch(it) } },
        headlineContent = { Text(profile?.displayName ?: device.name ?: device.address) },
        supportingContent = { Text(device.address) },
        trailingContent = {
            Box {
                IconButton(onClick = { onOpenMenu(device.address) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(
                        text = { Text("Set Color") },
                        leadingIcon = { Icon(Icons.Filled.ColorLens, contentDescription = null) },
                        onClick = onPickColor
                    )
                    DropdownMenuItem(
                        text = { Text("Edit Name") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = onEditName
                    )
                    DropdownMenuItem(
                        text = { Text("Forget") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = onForget
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable { onConnect() }
    )
}

// Known cars and auto-connect removed for a cleaner, explicit flow.

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
        0xFFC0C0C0.toInt(), // Silver
        0xFFFFFFFF.toInt(), 0xFF000000.toInt()
    )
    var customHex by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var value by remember { mutableFloatStateOf(1f) }
    val hsvColor by remember(hue, sat, value) {
        mutableStateOf(Color.hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
    }

    fun parseHex(input: String): Int? {
        val raw = input.trim().removePrefix("#").uppercase()
        return try {
            when (raw.length) {
                6 -> (0xFF000000.toInt() or raw.toInt(16))
                8 -> raw.toLong(16).toInt()
                else -> null
            }
        } catch (_: Throwable) { null }
    }

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
                HorizontalDivider()
                Text("Custom (interactive)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(hsvColor, shape = MaterialTheme.shapes.small))
                    Text("Preview")
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onPick(hsvColor.toArgb()) }) { Text("Use") }
                }
                Text("Hue")
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text("Saturation")
                Slider(value = sat, onValueChange = { sat = it }, valueRange = 0f..1f)
                Text("Value")
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)

                HorizontalDivider()
                Text("Custom (hex #RRGGBB or #AARRGGBB)")
                OutlinedTextField(
                    value = customHex,
                    onValueChange = {
                        customHex = it
                        inputError = null
                    },
                    singleLine = true,
                    isError = inputError != null,
                    supportingText = { inputError?.let { Text(it) } },
                    placeholder = { Text("#C0C0C0") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val parsed = parseHex(customHex)
                        if (parsed != null) onPick(parsed) else inputError = "Invalid hex"
                    }) { Text("Apply") }
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
