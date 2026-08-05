@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.johnson.fitness.ui.bluetooth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.johnson.fitness.FitnessApp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.fitness.device.model.ConnectionState
import com.fitness.device.model.DeviceBrand
import com.fitness.device.model.DeviceType
import com.fitness.device.model.SignalQuality
import com.johnson.fitness.ui.theme.JohnsonColors

@Composable
fun BluetoothScreen(
    onBack: () -> Unit,
    viewModel: BluetoothViewModel = viewModel {
        val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as FitnessApp
        BluetoothViewModel(app, app.deviceManager)
    }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) viewModel.onIntent(BluetoothIntent.StartScan)
    }

    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) viewModel.onIntent(BluetoothIntent.StartScan)
        else permissionLauncher.launch(requiredPermissions)
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                BluetoothEffect.RequestPermission ->
                    permissionLauncher.launch(requiredPermissions)
                is BluetoothEffect.ShowConnectionError ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.BgApp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp, vertical = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "配對手環",
                        color = JohnsonColors.TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "選擇你的裝置",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = JohnsonColors.TextPrimary,
                        letterSpacing = (-0.3).sp
                    )
                }

                // Scanning indicator
                if (state.isScanning) {
                    Spacer(Modifier.width(20.dp))
                    CircularProgressIndicator(
                        color = JohnsonColors.Brand,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "搜尋中…",
                        color = JohnsonColors.TextTertiary,
                        fontSize = 14.sp
                    )
                }

                // Connected status
                when (val cs = state.connectionState) {
                    is ConnectionState.Connected -> {
                        Spacer(Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(JohnsonColors.AccentScore, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "已連線：${cs.device.name ?: "裝置"}",
                            color = JohnsonColors.AccentScore,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        state.batteryLevel?.let { pct ->
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "電量 $pct%",
                                color = JohnsonColors.AccentScore,
                                fontSize = 13.sp
                            )
                        }
                        if (cs.device.brand == DeviceBrand.B20) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = state.b20DeviceInfo?.let { "韌體 ${it.version ?: "-"}" } ?: "讀取韌體資訊中…",
                                color = JohnsonColors.TextTertiary,
                                fontSize = 13.sp
                            )
                        }
                    }
                    is ConnectionState.Reconnecting -> {
                        Spacer(Modifier.width(16.dp))
                        CircularProgressIndicator(
                            color = JohnsonColors.Amber500,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "重連中（${cs.attempt}/${cs.maxAttempts}）",
                            color = JohnsonColors.Amber500,
                            fontSize = 14.sp
                        )
                    }
                    else -> {}
                }

                Spacer(Modifier.weight(1f))

                if (state.connectionState is ConnectionState.Connected) {
                    Button(onClick = { viewModel.onIntent(BluetoothIntent.Disconnect) }) {
                        Text("斷開連線")
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Button(
                    onClick = { viewModel.onIntent(BluetoothIntent.StartScan) },
                    enabled = !state.isScanning
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("重新搜尋")
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp)
                    .height(1.dp)
                    .background(JohnsonColors.BorderSubtle)
            )
            Spacer(Modifier.height(24.dp))

            // Device list content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 56.dp)
            ) {
                when {
                    state.bluetoothDisabled -> EmptyHint("請先開啟藍牙")
                    state.devices.isEmpty() && !state.isScanning -> EmptyHint("未找到藍牙裝置")
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(state.devices, key = { it.address }) { device ->
                            DeviceItem(
                                device = device,
                                connectionState = state.connectionState,
                                onConnect = { viewModel.onIntent(BluetoothIntent.ConnectDevice(device)) },
                                onDisconnect = { viewModel.onIntent(BluetoothIntent.Disconnect) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = JohnsonColors.TextTertiary,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DeviceItem(
    device: BtDevice,
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected &&
        connectionState.device.address == device.address
    val isConnecting = connectionState is ConnectionState.Connecting &&
        connectionState.device.address == device.address
    val reconnecting = connectionState as? ConnectionState.Reconnecting
    val isReconnecting = reconnecting?.device?.address == device.address

    val borderColor = when {
        isConnected -> JohnsonColors.AccentScore
        isConnecting || isReconnecting -> JohnsonColors.Amber500
        else -> JohnsonColors.BorderSubtle
    }

    Card(
        onClick = {
            when {
                isConnected -> onDisconnect()
                !isConnecting && !isReconnecting -> onConnect()
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(JohnsonColors.SurfaceCard)
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bluetooth icon placeholder
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(JohnsonColors.SurfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "BT",
                    color = JohnsonColors.Brand,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "未知裝置",
                    color = JohnsonColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${device.address}  ·  ${device.brand.brandName}  ·  ${device.deviceType.label()}  ·  ${device.rssi} dBm  ·  ${device.signalQuality.label()}",
                    color = JohnsonColors.TextTertiary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            when {
                isConnecting -> {
                    CircularProgressIndicator(
                        color = JohnsonColors.Amber500,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("連線中…", color = JohnsonColors.Amber500, fontSize = 13.sp)
                }
                isReconnecting -> {
                    CircularProgressIndicator(
                        color = JohnsonColors.Amber500,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "重連中（${reconnecting!!.attempt}/${reconnecting.maxAttempts}）",
                        color = JohnsonColors.Amber500,
                        fontSize = 13.sp
                    )
                }
                isConnected -> {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(JohnsonColors.AccentScore, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("已連線", color = JohnsonColors.AccentScore, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                else -> Text("點擊連線", color = JohnsonColors.TextTertiary, fontSize = 13.sp)
            }
        }
    }
}

private fun SignalQuality.label() = when (this) {
    SignalQuality.GOOD -> "訊號佳"
    SignalQuality.WEAK -> "訊號弱"
    SignalQuality.LOST -> "訊號差"
}

private fun DeviceType.label() = when (this) {
    DeviceType.GARMIN -> "Garmin"
    DeviceType.WEAROS -> "WearOS"
    DeviceType.OTHER  -> "Other"
}
