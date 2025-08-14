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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import android.graphics.Color as AndroidColor
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
                title = { Text(stringResource(id = R.string.ood_discover_connect_title)) },
                actions = {
                    TextButton(onClick = onOpenDiagnostics) { Text(stringResource(id = R.string.ood_diagnostics)) }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!permissions.allPermissionsGranted) {
                Text(
                    text = stringResource(id = R.string.ood_bt_permissions_required),
                    modifier = Modifier.padding(16.dp)
                )
                Button(onClick = { permissions.launchMultiplePermissionRequest() }, modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(id = R.string.ood_grant_permissions))
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(id = R.string.ood_nearby_devices), style = MaterialTheme.typography.titleMedium)
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
                    onPick = { start, end ->
                        scope.launch {
                            val base = (prof ?: CarProfile(deviceAddress = addr))
                            val next = if (end == null) {
                                base.copy(colorArgb = start, colorStartArgb = null, colorEndArgb = null)
                            } else {
                                base.copy(colorArgb = null, colorStartArgb = start, colorEndArgb = end)
                            }
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
                    title = { Text(stringResource(id = R.string.ood_forget_title)) },
                    text = { Text(stringResource(id = R.string.ood_forget_message, addr)) },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                carRepo.removeProfile(addr)
                                confirmForgetFor = null
                            }
                        }) { Text(stringResource(id = R.string.ood_apply_forget)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmForgetFor = null }) { Text(stringResource(id = R.string.ood_cancel)) } }
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
        leadingContent = {
            val start = profile?.colorStartArgb
            val end = profile?.colorEndArgb
            val solid = profile?.colorArgb
            when {
                start != null && end != null -> GradientSwatch(start, end)
                solid != null -> ColorSwatch(solid)
                else -> {}
            }
        },
        headlineContent = { Text(profile?.displayName ?: device.name ?: device.address) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val start = profile?.colorStartArgb
                val end = profile?.colorEndArgb
                val solid = profile?.colorArgb
                when {
                    start != null && end != null -> GradientBar(start, end)
                    solid != null -> SolidBar(solid)
                }
                Text(device.address, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { onOpenMenu(device.address) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.ood_set_color)) },
                        leadingIcon = { Icon(Icons.Filled.ColorLens, contentDescription = null) },
                        onClick = onPickColor
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.ood_edit_name)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = onEditName
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.ood_forget)) },
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
private fun GradientSwatch(startArgb: Int, endArgb: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(
                Brush.linearGradient(listOf(Color(startArgb), Color(endArgb))),
                shape = MaterialTheme.shapes.small
            )
    )
}

@Composable
private fun GradientBar(startArgb: Int, endArgb: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .height(6.dp)
            .background(
                Brush.linearGradient(listOf(Color(startArgb), Color(endArgb))),
                shape = MaterialTheme.shapes.extraSmall
            )
    )
}

@Composable
private fun SolidBar(argb: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .height(6.dp)
            .background(Color(argb), shape = MaterialTheme.shapes.extraSmall)
    )
}

@Composable
private fun ColorPickerDialog(onDismiss: () -> Unit, onPick: (Int, Int?) -> Unit) {
    val colors = listOf(
        0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFFFDD835.toInt(),
        0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00BCD4.toInt(), 0xFF9E9E9E.toInt(),
        0xFFC0C0C0.toInt(), // Silver
        0xFFFFFFFF.toInt(), 0xFF000000.toInt()
    )
    var gradientEnabled by remember { mutableStateOf(false) }
    var activeStop by remember { mutableStateOf(0) } // 0 or 1
    var hue0 by remember { mutableFloatStateOf(0f) }
    var sat0 by remember { mutableFloatStateOf(1f) }
    var val0 by remember { mutableFloatStateOf(1f) }
    var hue1 by remember { mutableFloatStateOf(210f) }
    var sat1 by remember { mutableFloatStateOf(1f) }
    var val1 by remember { mutableFloatStateOf(1f) }
    val color0 by remember(hue0, sat0, val0) { mutableStateOf(Color.hsv(hue0.coerceIn(0f,360f), sat0.coerceIn(0f,1f), val0.coerceIn(0f,1f))) }
    val color1 by remember(hue1, sat1, val1) { mutableStateOf(Color.hsv(hue1.coerceIn(0f,360f), sat1.coerceIn(0f,1f), val1.coerceIn(0f,1f))) }

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
                                .clickable {
                                    val hsv = FloatArray(3)
                                    AndroidColor.colorToHSV(c, hsv)
                                    if (gradientEnabled && activeStop == 1) { hue1 = hsv[0]; sat1 = hsv[1]; val1 = hsv[2] }
                                    else { hue0 = hsv[0]; sat0 = hsv[1]; val0 = hsv[2] }
                                }
                        )
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Gradient")
                    Switch(checked = gradientEnabled, onCheckedChange = { gradientEnabled = it })
                    if (gradientEnabled) {
                        AssistChip(label = { Text("Stop A") }, onClick = { activeStop = 0 }, leadingIcon = { Box(Modifier.size(16.dp).background(color0, shape = MaterialTheme.shapes.extraSmall)) })
                        AssistChip(label = { Text("Stop B") }, onClick = { activeStop = 1 }, leadingIcon = { Box(Modifier.size(16.dp).background(color1, shape = MaterialTheme.shapes.extraSmall)) })
                    }
                    Spacer(Modifier.weight(1f))
                    val previewBase = Modifier.size(48.dp)
                    val previewMod = if (gradientEnabled) {
                        previewBase.background(
                            Brush.linearGradient(listOf(color0, color1)),
                            shape = MaterialTheme.shapes.small
                        )
                    } else {
                        previewBase.background(color0, shape = MaterialTheme.shapes.small)
                    }
                    Box(previewMod)
                }
                val (h, s, v) = if (activeStop == 0) Triple(hue0, sat0, val0) else Triple(hue1, sat1, val1)
                Text("Hue")
                Slider(value = h, onValueChange = { if (activeStop == 0) hue0 = it else hue1 = it }, valueRange = 0f..360f)
                Text("Saturation")
                Slider(value = s, onValueChange = { if (activeStop == 0) sat0 = it else sat1 = it }, valueRange = 0f..1f)
                Text("Value")
                Slider(value = v, onValueChange = { if (activeStop == 0) val0 = it else val1 = it }, valueRange = 0f..1f)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { if (gradientEnabled) onPick(color0.toArgb(), color1.toArgb()) else onPick(color0.toArgb(), null) }) { Text("Use") }
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
