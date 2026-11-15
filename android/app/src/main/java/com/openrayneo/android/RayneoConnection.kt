// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

@SuppressLint("MissingPermission") // Activity grants SCAN and CONNECT before invoking this controller.
internal class RayneoConnection(private val context: Context, private val listener: Listener) {
    data class Device(val name: String, val bluetooth: BluetoothDevice, val classicAddress: String?)
    interface Listener {
        fun onDevice(device: Device)
        fun onState(text: String, ready: Boolean, busy: Boolean)
        fun onLog(text: String)
        fun onFrame(frame: ProtocolCodec.Frame, source: String) {}
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var gatt: BluetoothGatt? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var scanning = false
    private var ready = false
    private var expectedAddress: String? = null
    val resolvedClassicAddress: String? get() = expectedAddress
    private var classicAddress = byteArrayOf()
    private var identity = byteArrayOf()
    private var send: BluetoothGattCharacteristic? = null
    private var receive: BluetoothGattCharacteristic? = null
    private var pairInfo: BluetoothGattCharacteristic? = null
    private val bleSetup = BleSetupSequence()
    private var pairingSequence = 0
    private var notificationSequence = 0
    private var bleDecoder = ProtocolCodec.StreamDecoder()
    private var authenticationHandled = false
    private var bleOnly = false
    private var blePayloadSize = 20
    private data class BleWrite(val bytes: ByteArray, val completion: String?)
    private val bleWrites = ArrayDeque<BleWrite>()
    private var bleWriting = false
    private var generation = 0
    private var bondReceiver: BroadcastReceiver? = null
    private var connectionStage = "Waiting to connect"
    private val timeout = Runnable { fail("Connection timed out: $connectionStage") }
    private val stopScanTask = Runnable { stopScan(); state("Scan finished. Select your glasses.") }

    fun showBonded() {
        check(adapter.isEnabled) { "Turn on phone Bluetooth first" }
        adapter.bondedDevices.filter { it.name?.contains("RayNeo", true) == true }.forEach {
            listener.onDevice(Device(it.name ?: "RayNeo iO", it, it.address))
        }
    }

    fun scan() {
        check(adapter.isEnabled) { "Turn on phone Bluetooth first" }
        check(gatt == null) { "Disconnect the current connection first" }
        stopScan()
        state("Scanning for glasses…", busy = true)
        scanning = true
        adapter.bluetoothLeScanner.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        main.postDelayed(stopScanTask, 12_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name ?: ""
                val manufacturer = record?.getManufacturerSpecificData(0x5716)
                val matches = name.contains("RayNeo iO", true) ||
                    record?.serviceUuids?.any { it.uuid == SERVICE } == true ||
                    (manufacturer != null && manufacturer.size >= 7 && manufacturer[0] == 6.toByte())
                if (!matches) return@post
                val address = manufacturer?.takeIf { it.size >= 7 && it[0] == 6.toByte() }
                    ?.copyOfRange(1, 7)?.let(::addressString)
                listener.onDevice(Device(name.ifEmpty { "RayNeo iO" }, result.device, address))
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post { stopScan(); state("BLE scan failed: $errorCode") }
        }
    }

    fun connect(device: Device, bleOnly: Boolean = false) {
        disconnect()
        this.bleOnly = bleOnly
        expectedAddress = device.classicAddress
        pairingSequence = 0
        bleDecoder = ProtocolCodec.StreamDecoder()
        // Match the verified Mac sequence: classic bond first, then BLE application auth.
        val address = device.classicAddress
        if (!bleOnly && address != null) {
            ensureBond(adapter.getRemoteDevice(address)) { startBle(device) }
        } else {
            startBle(device)
        }
    }

    private fun startBle(device: Device) {
        main.removeCallbacks(timeout)
        state("Connecting BLE…", busy = true)
        main.postDelayed(timeout, 25_000)
        gatt = device.bluetooth.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, main)
        check(gatt != null) { "Cannot create GATT connection" }
    }

    private fun ensureBond(target: BluetoothDevice, continuation: () -> Unit) {
        if (target.bondState == BluetoothDevice.BOND_BONDED) {
            log("Classic Bluetooth paired")
            continuation()
            return
        }
        state("Confirm Bluetooth pairing on your phone…", busy = true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (bondReceiver !== this || intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device?.address != target.address) return
                val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                log("Classic Bluetooth bond state=$bond")
                when (bond) {
                    BluetoothDevice.BOND_BONDED -> {
                        stopObservingBond()
                        main.removeCallbacks(timeout)
                        guarded(continuation)
                    }
                    BluetoothDevice.BOND_NONE -> fail("Bluetooth pairing incomplete. Keep the glasses discoverable and reconnect.")
                }
            }
        }
        bondReceiver = receiver
        // Bluetooth broadcasts can originate from the privileged Bluetooth process.
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 45_000)
        if (target.bondState == BluetoothDevice.BOND_BONDED) {
            stopObservingBond()
            main.removeCallbacks(timeout)
            continuation()
        } else if (target.bondState == BluetoothDevice.BOND_NONE) {
            if (!target.createBond()) fail("Android did not accept the Bluetooth pairing request")
        }
    }

    private fun stopObservingBond() {
        val receiver = bondReceiver ?: return
        bondReceiver = null
        context.unregisterReceiver(receiver)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            if (connection !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("BLE connection ended: status=$status, state=$newState")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) guarded {
                state("BLE connected. Discovering services…", busy = true)
                check(connection.discoverServices()) { "Cannot start service discovery" }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            if (connection !== gatt) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS) { "Service discovery failed: $status" }
                if (bleSetup.servicesReady) return@guarded
                log("Discovered BLE services: ${connection.services.joinToString { it.uuid.toString() }}")
                val service = checkNotNull(connection.getService(SERVICE)) {
                    "Device has no B81D service. Wake the glasses, disconnect the official app and reconnect."
                }
                send = checkNotNull(service.getCharacteristic(SEND)) { "Send characteristic missing" }
                receive = checkNotNull(service.getCharacteristic(RECEIVE)) { "Receive characteristic missing" }
                pairInfo = checkNotNull(service.getCharacteristic(PAIR_INFO)) { "Identity characteristic missing" }
                if (bleSetup.servicesDiscovered()) {
                    log("B81D found; using previously reported MTU=${bleSetup.mtu}")
                    readIdentity(connection)
                } else {
                    state("B81D found. Negotiating BLE MTU…", busy = true)
                    check(connection.requestMtu(512)) { "Cannot start MTU negotiation" }
                }
            }
        }

        override fun onMtuChanged(connection: BluetoothGatt, mtu: Int, status: Int) {
            if (connection !== gatt) return
            guarded {
                log("BLE MTU callback: mtu=$mtu, status=$status, servicesReady=${bleSetup.servicesReady}")
                if (status != BluetoothGatt.GATT_SUCCESS || mtu < 23) {
                    check(!bleSetup.servicesReady || bleSetup.identityReadStarted) {
                        "BLE MTU negotiation failed: mtu=$mtu, status=$status"
                    }
                    return@guarded
                }
                blePayloadSize = mtu - 3
                check(!bleSetup.servicesReady || bleSetup.identityReadStarted || mtu >= 160) {
                    "BLE MTU too small: mtu=$mtu, status=$status"
                }
                if (bleSetup.mtuChanged(mtu)) readIdentity(connection)
                else if (!bleSetup.servicesReady) log("MTU recorded; waiting for service discovery before reading identity")
            }
        }

        override fun onServiceChanged(connection: BluetoothGatt) {
            if (connection !== gatt) return
            fail("Glasses BLE services changed. Reconnect to discover them again.")
        }

        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                          value: ByteArray, status: Int) {
            if (connection !== gatt || characteristic.uuid != PAIR_INFO) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS && value.size >= 13) { "Failed to read glasses identity: $status" }
                classicAddress = value.copyOfRange(0, 6)
                val address = addressString(classicAddress)
                check(expectedAddress == null || expectedAddress.equals(address, true)) { "BLE identity does not match the selected Bluetooth device" }
                expectedAddress = address
                val bound = value.copyOfRange(7, 13)
                // Preserve the Mac bridge's bound-identity baseline; unbound devices use the
                // official Android client's six-byte Android ID convention (last 12 hex digits).
                identity = if (bound.any { it != 0.toByte() }) bound else androidIdentity()
                log("Identity read; state=${value[6].toInt() and 255}; Identity source=${if (bound.any { it != 0.toByte() }) "Glasses binding" else "This app Android ID"}")
                val receive = checkNotNull(receive) { "Receive characteristic unavailable. Reconnect the glasses." }
                check(connection.setCharacteristicNotification(receive, true)) { "Cannot enable BLE notifications" }
                val descriptor = checkNotNull(receive.getDescriptor(CCCD)) { "CCCD missing" }
                state("Subscribing to BLE notifications…", busy = true)
                check(connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS) {
                    "Cannot write CCCD"
                }
            }
        }

        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (connection !== gatt || descriptor.uuid != CCCD) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS) { "BLE notification subscription failed: $status" }
                state("Exchanging device information…", busy = true)
                writePairing(17, listOf(
                    16 to byteArrayOf(1), 17 to identity,
                    18 to (adapter.name ?: "OpenRayneo Android").toByteArray(),
                    19 to byteArrayOf(2), 22 to Build.MANUFACTURER.toByteArray(),
                    23 to Build.MODEL.toByteArray(), 27 to byteArrayOf(1)
                ))
            }
        }

        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (connection !== gatt || characteristic.uuid != SEND) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS) { "BLE write failed: $status" }
                bleWriting = false
                bleWrites.removeFirstOrNull()?.completion?.let(::log)
                writeNextBleChunk()
            }
        }

        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (connection !== gatt || characteristic.uuid != RECEIVE) return
            guarded {
                bleDecoder.append(value).forEach { frame ->
                    if (frame.acknowledgement) {
                        if (bleOnly) log("BLE transfer acknowledged: seq=${frame.sequence}")
                    } else {
                        if (frame.flags and 1 != 0) {
                            enqueueBleFrame(ProtocolCodec.acknowledgement(frame), null)
                        }
                        if (frame.protocol == 0x10) processPairing(frame)
                        else listener.onFrame(frame, "BLE")
                    }
                }
            }
        }
    }

    private fun readIdentity(connection: BluetoothGatt) {
        val characteristic = checkNotNull(pairInfo) { "Identity characteristic unavailable. Reconnect the glasses." }
        state("Reading glasses identity…", busy = true)
        check(connection.readCharacteristic(characteristic)) { "Cannot read identity characteristic" }
    }

    private fun processPairing(frame: ProtocolCodec.Frame) {
        check(frame.payload.size >= 3) { "Incomplete authentication response" }
        val command = frame.payload[0].toInt() and 255
        log("Received BLE command $command")
        when (command) {
            17 -> {
                val fields = ProtocolCodec.pairingFields(frame.payload)
                check(25 !in fields) { "The glasses require account-bound ECDH, which this build does not support" }
                val random = ByteArray(4).also { SecureRandom().nextBytes(it) }
                state("Authenticating…", busy = true)
                writePairing(24, listOf(16 to random, 17 to authentication(random)))
            }
            25 -> {
                val fields = ProtocolCodec.pairingFields(frame.payload)
                val random = checkNotNull(fields[16]) { "Authentication response is missing the challenge" }
                check(random.size == 4) { "Invalid authentication challenge length" }
                check(fields[17]?.contentEquals(authentication(random)) == true) { "Application authentication rejected" }
                if (!authenticationHandled) {
                    authenticationHandled = true
                    log("BLE application authentication passed")
                    if (bleOnly) {
                        main.removeCallbacks(timeout)
                        ready = true
                        state("BLE authenticated. Notifications may work; full pairing is unverified.", ready = true)
                    } else openRfcomm()
                }
            }
            22, 23 -> log("Glasses state=${frame.payload.getOrNull(3)?.toInt()?.and(255)}")
        }
    }

    private fun writePairing(command: Int, fields: List<Pair<Int, ByteArray>>) {
        val bytes = ProtocolCodec.pairing(pairingSequence++, command, fields)
        enqueueBleFrame(bytes, "Sent BLE command $command(${bytes.size} bytes)")
    }

    private fun enqueueBleFrame(bytes: ByteArray, completion: String?) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + blePayloadSize, bytes.size)
            bleWrites.addLast(BleWrite(bytes.copyOfRange(offset, end), if (end == bytes.size) completion else null))
            offset = end
        }
        writeNextBleChunk()
    }

    private fun writeNextBleChunk() {
        if (bleWriting || bleWrites.isEmpty()) return
        val connection = checkNotNull(gatt)
        bleWriting = true
        check(connection.writeCharacteristic(checkNotNull(send), bleWrites.first().bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS) { "BLE send request rejected" }
    }

    private fun authentication(random: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(random + identity + classicAddress + AUTH_CONSTANT)

    private fun androidIdentity(): ByteArray {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        check(!id.isNullOrEmpty() && id.length >= 12) { "App Android ID unavailable" }
        return id.takeLast(12).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun openRfcomm() {
        val target = adapter.getRemoteDevice(checkNotNull(expectedAddress))
        ensureBond(target) { connectRfcomm(target) }
    }

    private fun connectRfcomm(target: BluetoothDevice) {
        state("BLE authenticated. Connecting SPP…", busy = true)
        val session = generation
        log("Classic Bluetooth bond state=${target.bondState}; RFCOMM service UUID=$SPP")
        val pending = target.createRfcommSocketToServiceRecord(SPP)
        socket = pending
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 20_000)
        io.execute {
            try {
                adapter.cancelDiscovery()
                pending.connect()
                main.post {
                    if (session != generation || socket !== pending) return@post
                    main.removeCallbacks(timeout)
                    ready = true
                    state("Connected: BLE authenticated and RFCOMM ready", ready = true)
                    startReader(pending, session)
                }
            } catch (error: Exception) {
                main.post { if (session == generation) fail("SPP connection failed: ${error.message}") }
            }
        }
    }

    private fun startReader(connection: BluetoothSocket, session: Int) {
        Thread({
            val decoder = ProtocolCodec.StreamDecoder()
            val buffer = ByteArray(4096)
            try {
                while (socket === connection) {
                    val count = connection.inputStream.read(buffer)
                    check(count >= 0) { "The glasses closed the data stream" }
                    val frames = decoder.append(buffer.copyOf(count))
                    main.post {
                        if (session == generation) frames.forEach {
                            if (!it.acknowledgement) listener.onFrame(it, "SPP")
                        }
                    }
                }
            } catch (error: Exception) {
                main.post { if (session == generation) fail("Data connection ended: ${error.message}") }
            }
        }, "rayneo-reader").start()
    }

    fun sendTestNotification() = sendNotification("OpenRayneo English", "Hello from OpenRayneo on Android!")

    fun sendNotification(title: String, body: String) {
        check(ready) { "Connect the glasses first" }
        val timestamp = System.currentTimeMillis().toString()
        val payload = JSONObject().apply {
            put("notificationUID", timestamp)
            put("appId", "com.openrayneo.bridge")
            put("appName", "OpenRayneo Android")
            put("title", title)
            put("subtitle", JSONObject.NULL)
            put("content", body)
            put("timestamp", timestamp)
            put("category", 0)
            put("reply", false)
            put("type", 1)
        }
        val frame = ProtocolCodec.notification(notificationSequence++, payload.toString())
        if (bleOnly) {
            enqueueBleFrame(frame, "BLE notification sent. Check the glasses display.")
            return
        }
        sendFrame(frame) { result ->
            result.onSuccess { log("Notification sent. Check the glasses display.") }.onFailure { log("Notification send failed: ${it.message}") }
        }
    }

    fun sendFrame(frame: ByteArray, completion: (Result<Unit>) -> Unit) {
        check(ready && !bleOnly) { "This feature requires RFCOMM. Turn off BLE-only mode." }
        val session = generation
        val connection = checkNotNull(socket)
        io.execute {
            try {
                check(socket === connection) { "Connection changed" }
                connection.outputStream.write(frame)
                connection.outputStream.flush()
                main.post { completion(if (session == generation) Result.success(Unit) else Result.failure(IllegalStateException("Connection changed"))) }
            } catch (error: Exception) {
                main.post {
                    completion(Result.failure(error))
                    if (session == generation) fail("Data send failed: ${error.message}")
                }
            }
        }
    }

    private fun stopScan() {
        main.removeCallbacks(stopScanTask)
        if (scanning) adapter.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    fun disconnect() {
        generation++
        main.removeCallbacks(timeout)
        stopObservingBond()
        stopScan()
        ready = false
        authenticationHandled = false
        bleWrites.clear()
        bleWriting = false
        blePayloadSize = 20
        bleSetup.reset()
        val oldGatt = gatt
        gatt = null
        oldGatt?.disconnect()
        oldGatt?.close()
        val oldSocket = socket
        socket = null
        runCatching { oldSocket?.close() }
        send = null
        receive = null
        pairInfo = null
        state("Disconnected")
    }

    fun close() { disconnect(); io.shutdownNow() }

    private fun fail(message: String) { disconnect(); state(message) }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (error: Exception) { fail(error.message ?: error.javaClass.simpleName) }
    }
    private fun state(text: String, ready: Boolean = false, busy: Boolean = false) {
        if (busy) connectionStage = text
        listener.onState(text, ready, busy)
        log(text)
    }
    private fun log(text: String) {
        Log.i("OpenRayneo", text)
        listener.onLog(text)
    }

    companion object {
        private val SERVICE = UUID.fromString("0000B81D-0000-1000-8000-00805F9B34FB")
        private val SEND = UUID.fromString("EA8B70D5-2BD3-49AB-9C31-9C38B2C3C4F9")
        private val RECEIVE = UUID.fromString("7DB3E235-3608-41F3-A03C-955FCBD2EA4B")
        private val PAIR_INFO = UUID.fromString("EA8B60C5-2BD3-49AB-9C31-9D38B1C5C5F9")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        // RayNeo business service (APK V3/p); standard SPP is used by RNLink log capture.
        private val SPP = UUID.fromString("99999999-9999-9999-9999-999999999999")
        private val AUTH_CONSTANT = intArrayOf(7, 18, 200, 173, 39, 116, 65, 149, 222, 95, 161, 234, 109, 2, 95, 184)
            .map { it.toByte() }.toByteArray()
        private fun addressString(bytes: ByteArray) = bytes.joinToString(":") { "%02X".format(it.toInt() and 255) }
    }
}
