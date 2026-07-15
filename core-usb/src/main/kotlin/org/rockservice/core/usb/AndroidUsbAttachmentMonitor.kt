package org.rockservice.core.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Emits attach/detach hints while the host application is running.
 *
 * Broadcast payloads are not treated as trusted target state. Callers must re-enumerate through
 * [AndroidUsbHostBackend] before selecting or operating on a device.
 */
class AndroidUsbAttachmentMonitor(
    context: Context,
    private val onChanged: (UsbAttachmentEvent) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val registered = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val kind = when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> UsbAttachmentEventKind.ATTACHED
                UsbManager.ACTION_USB_DEVICE_DETACHED -> UsbAttachmentEventKind.DETACHED
                else -> return
            }
            onChanged(
                UsbAttachmentEvent(
                    kind = kind,
                    transportIdHint = intent.usbDeviceExtra()?.deviceName,
                )
            )
        }
    }

    fun start() {
        if (!registered.compareAndSet(false, true)) return

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, filter)
        }
    }

    fun close() {
        if (!registered.compareAndSet(true, false)) return
        try {
            applicationContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // The framework already removed the receiver.
        }
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}

enum class UsbAttachmentEventKind { ATTACHED, DETACHED }

data class UsbAttachmentEvent(
    val kind: UsbAttachmentEventKind,
    val transportIdHint: String?,
)
