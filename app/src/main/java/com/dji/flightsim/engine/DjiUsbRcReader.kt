package com.dji.flightsim.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import kotlin.math.abs

/**
 * Reads joystick data directly from DJI RC controllers via USB Host API.
 *
 * DJI RC controllers (RC-N1, RC-N2, RC 2, RC Pro, DJI FPV Controller)
 * use USB vendor ID 0x2CA3 and communicate via HID interrupt transfers.
 *
 * Typical channel layout in the USB packet:
 *   Bytes 0-1:  Right stick horizontal (roll/aileron)
 *   Bytes 2-3:  Right stick vertical   (pitch/elevator)
 *   Bytes 4-5:  Left stick vertical    (throttle)
 *   Bytes 6-7:  Left stick horizontal  (yaw/rudder)
 *   Bytes 8-9:  Dial/wheel (if present)
 *   Byte 10+:   Buttons, switches
 *
 * Values are typically 16-bit unsigned, center ~1024, range ~364-1684.
 */
class DjiUsbRcReader(private val context: Context) {

    companion object {
        private const val TAG = "DjiUsbRC"
        private const val ACTION_USB_PERMISSION = "com.dji.flightsim.USB_PERMISSION"

        // DJI USB Vendor IDs
        private val DJI_VENDOR_IDS = setOf(0x2CA3, 0x2554)

        // Channel value range (typical DJI RC)
        private const val CH_MIN = 364
        private const val CH_MAX = 1684
        private const val CH_CENTER = 1024
        private const val CH_RANGE = (CH_MAX - CH_MIN) / 2

        // Dead zone
        private const val DEAD_ZONE = 0.05f

        // Minimum packet size for valid joystick data
        private const val MIN_PACKET_SIZE = 8
    }

    data class RcChannels(
        val rightX: Float = 0f,   // Roll:     -1 (left) to +1 (right)
        val rightY: Float = 0f,   // Pitch:    -1 (down/back) to +1 (up/forward)
        val leftY: Float = 0f,    // Throttle: -1 (down) to +1 (up)
        val leftX: Float = 0f,    // Yaw:      -1 (left) to +1 (right)
        val connected: Boolean = false,
        val deviceName: String = ""
    )

    // Public state
    var channels = RcChannels()
        private set

    var onChannelsUpdated: ((RcChannels) -> Unit)? = null

    private var usbManager: UsbManager? = null
    private var connection: UsbDeviceConnection? = null
    private var readEndpoint: UsbEndpoint? = null
    private var readThread: Thread? = null
    @Volatile private var isRunning = false

    // Track which data format we detect
    private var packetFormat: PacketFormat = PacketFormat.UNKNOWN

    private enum class PacketFormat {
        UNKNOWN,
        DJI_STANDARD,      // 16-bit LE channels starting at offset 0
        DJI_HID_REPORT,    // HID report with 1-byte header
        DJI_EXTENDED        // Extended format with header bytes
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectToDevice(it) }
                    } else {
                        Log.w(TAG, "USB permission denied for ${device?.deviceName}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    disconnect()
                }
            }
        }
    }

    fun start() {
        usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        // Check for already-connected DJI devices
        scanForDevices()
    }

    fun stop() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }

    fun scanForDevices() {
        val manager = usbManager ?: return
        for ((_, device) in manager.deviceList) {
            if (isDjiDevice(device)) {
                Log.i(TAG, "Found DJI device: ${device.deviceName} " +
                        "(VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)})")
                requestPermission(device)
                return
            }
        }
        Log.d(TAG, "No DJI USB device found (${manager.deviceList.size} devices total)")
    }

    private fun isDjiDevice(device: UsbDevice): Boolean {
        return device.vendorId in DJI_VENDOR_IDS
    }

    private fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, pi)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val manager = usbManager ?: return

        // Find an interrupt IN endpoint for reading joystick data
        var foundEndpoint: UsbEndpoint? = null
        var foundInterface: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    ep.direction == UsbConstants.USB_DIR_IN) {
                    foundEndpoint = ep
                    foundInterface = iface
                    break
                }
            }
            if (foundEndpoint != null) break
        }

        // Fall back to bulk IN if no interrupt endpoint found
        if (foundEndpoint == null) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN) {
                        foundEndpoint = ep
                        foundInterface = iface
                        break
                    }
                }
                if (foundEndpoint != null) break
            }
        }

        if (foundEndpoint == null || foundInterface == null) {
            Log.e(TAG, "No suitable endpoint found on device ${device.deviceName}")
            return
        }

        val conn = manager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open device ${device.deviceName}")
            return
        }

        if (!conn.claimInterface(foundInterface, true)) {
            Log.e(TAG, "Failed to claim interface")
            conn.close()
            return
        }

        connection = conn
        readEndpoint = foundEndpoint

        Log.i(TAG, "Connected to ${device.deviceName}, endpoint: ${foundEndpoint.address}, " +
                "maxPacketSize: ${foundEndpoint.maxPacketSize}")

        startReadThread(device.deviceName)
    }

    private fun startReadThread(deviceName: String) {
        isRunning = true
        readThread = Thread {
            val buffer = ByteArray(readEndpoint!!.maxPacketSize.coerceAtLeast(64))
            var consecutiveErrors = 0

            while (isRunning) {
                val conn = connection ?: break
                val ep = readEndpoint ?: break

                val bytesRead = conn.bulkTransfer(ep, buffer, buffer.size, 100)

                if (bytesRead > 0) {
                    consecutiveErrors = 0
                    parsePacket(buffer, bytesRead, deviceName)
                } else if (bytesRead < 0) {
                    consecutiveErrors++
                    if (consecutiveErrors > 50) {
                        Log.e(TAG, "Too many read errors, disconnecting")
                        break
                    }
                    // Timeout is normal, just retry
                    try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                }
            }

            // If we broke out of the loop unexpectedly, update state
            channels = RcChannels()
            onChannelsUpdated?.invoke(channels)
        }.apply {
            name = "DJI-RC-Reader"
            isDaemon = true
            start()
        }
    }

    private fun parsePacket(buffer: ByteArray, length: Int, deviceName: String) {
        if (length < MIN_PACKET_SIZE) return

        // Auto-detect packet format on first valid packet
        if (packetFormat == PacketFormat.UNKNOWN) {
            packetFormat = detectFormat(buffer, length)
            Log.i(TAG, "Detected packet format: $packetFormat (${length} bytes)")
        }

        val offset = when (packetFormat) {
            PacketFormat.DJI_HID_REPORT -> 1   // Skip HID report ID byte
            PacketFormat.DJI_EXTENDED -> 4      // Skip header
            else -> 0
        }

        if (length < offset + 8) return

        // Read 16-bit little-endian channel values
        val ch0 = readUInt16LE(buffer, offset)      // Right X (roll)
        val ch1 = readUInt16LE(buffer, offset + 2)  // Right Y (pitch)
        val ch2 = readUInt16LE(buffer, offset + 4)  // Left Y  (throttle)
        val ch3 = readUInt16LE(buffer, offset + 6)  // Left X  (yaw)

        channels = RcChannels(
            rightX = normalizeChannel(ch0),
            rightY = -normalizeChannel(ch1),   // Invert: stick up = positive (forward)
            leftY = -normalizeChannel(ch2),    // Invert: stick up = positive (climb)
            leftX = normalizeChannel(ch3),
            connected = true,
            deviceName = deviceName
        )

        onChannelsUpdated?.invoke(channels)
    }

    private fun detectFormat(buffer: ByteArray, length: Int): PacketFormat {
        // Check if first byte looks like a HID report ID (usually 0x01-0x04)
        if (length > 9 && buffer[0] in 1..4) {
            val ch0 = readUInt16LE(buffer, 1)
            if (ch0 in 200..2000) return PacketFormat.DJI_HID_REPORT
        }

        // Check if first 4 bytes look like a header (common: 0x55, length, ...)
        if (length > 12 && buffer[0].toInt() and 0xFF == 0x55) {
            return PacketFormat.DJI_EXTENDED
        }

        // Default: raw channel data starting at byte 0
        val ch0 = readUInt16LE(buffer, 0)
        if (ch0 in 200..2000) return PacketFormat.DJI_STANDARD

        return PacketFormat.DJI_STANDARD
    }

    private fun readUInt16LE(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
               ((buffer[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun normalizeChannel(raw: Int): Float {
        val centered = (raw - CH_CENTER).toFloat() / CH_RANGE
        val clamped = centered.coerceIn(-1f, 1f)
        return if (abs(clamped) < DEAD_ZONE) 0f else clamped
    }

    private fun disconnect() {
        isRunning = false
        readThread?.interrupt()
        readThread = null
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        readEndpoint = null
        packetFormat = PacketFormat.UNKNOWN
        channels = RcChannels()
        onChannelsUpdated?.invoke(channels)
    }
}
