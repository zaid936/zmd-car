package com.zmd.carhub

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the hub when the car screen boots (or after the app is updated). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).startOnBoot) return
        HubService.start(context, HubService.ACTION_BOOT)
    }
}

/**
 * Bluetooth connect/disconnect. Declared in the manifest so a phone connecting can wake the hub
 * even if the system stopped it while the car was asleep.
 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dev: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        val running = HubService.instance
        if (running != null) {
            running.btEvent(dev, connected)
            return
        }
        if (!connected || !Prefs(context).startOnBoot) return
        val start = Intent(context, HubService::class.java)
            .setAction(HubService.ACTION_BT)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, dev)
        runCatching { context.startForegroundService(start) }
    }
}
