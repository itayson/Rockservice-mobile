package org.rockservice.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidUsbHostPlatform(
    private val context: Context,
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager,
) : UsbHostPlatform {
    private val closed = AtomicBoolean(false)
    private val permissionMutex = Mutex()
    private val pendingPermissions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val permissionAction = "${context.packageName}.rockservice.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return

            val device = intent.usbDeviceExtra() ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            pendingPermissions.remove(device.deviceName)?.complete(granted)
        }
    }

    init {
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(permissionReceiver, filter)
        }
    }

    override suspend fun listDevices(): List<UsbHostDeviceSnapshot> {
        checkOpen()
        return usbManager.deviceList.values
            .map { device -> device.toSnapshot(usbManager.hasPermission(device)) }
            .sortedBy(UsbHostDeviceSnapshot::transportId)
    }

    override suspend fun requestPermission(transportId: String): Boolean =
        permissionMutex.withLock {
            checkOpen()
            val device = resolveDevice(transportId) ?: return@withLock false
            if (usbManager.hasPermission(device)) return@withLock true

            val result = CompletableDeferred<Boolean>()
            pendingPermissions[transportId] = result
            try {
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    transportId.hashCode(),
                    Intent(permissionAction).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                usbManager.requestPermission(device, permissionIntent)
                result.await()
            } finally {
                pendingPermissions.remove(transportId, result)
            }
        }

    override suspend fun readRawDescriptors(transportId: String): ByteArray {
        checkOpen()
        val device = requireNotNull(resolveDevice(transportId)) {
            "USB device is no longer attached."
        }
        check(usbManager.hasPermission(device)) {
            "USB permission is required before opening the selected device."
        }

        val connection = checkNotNull(usbManager.openDevice(device)) {
            "Android failed to open the selected USB device."
        }
        return try {
            connection.rawDescriptors?.clone() ?: ByteArray(0)
        } finally {
            connection.close()
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return

        pendingPermissions.values.forEach { deferred -> deferred.complete(false) }
        pendingPermissions.clear()
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already removed by the framework or host lifecycle.
        }
    }

    private fun resolveDevice(transportId: String): UsbDevice? =
        usbManager.deviceList[transportId]

    private fun UsbDevice.toSnapshot(hasPermission: Boolean): UsbHostDeviceSnapshot =
        UsbHostDeviceSnapshot(
            transportId = deviceName,
            vendorId = vendorId,
            productId = productId,
            manufacturer = safeMetadata { manufacturerName },
            product = safeMetadata { productName },
            serialNumber = if (hasPermission) safeMetadata { serialNumber } else null,
            deviceClass = deviceClass,
            deviceSubclass = deviceSubclass,
            deviceProtocol = deviceProtocol,
            hasPermission = hasPermission,
        )

    private fun safeMetadata(block: () -> String?): String? =
        try {
            block()?.takeIf(String::isNotBlank)
        } catch (_: SecurityException) {
            null
        }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun checkOpen() {
        check(!closed.get()) { "USB Host platform is closed." }
    }
}
