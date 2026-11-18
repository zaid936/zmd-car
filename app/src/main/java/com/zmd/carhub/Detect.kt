package com.zmd.carhub

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.ParcelUuid

/**
 * Works out what kind of phone just connected, using only what the system reports about the
 * device (USB ids and interface classes, Bluetooth class/services/name). Nothing is opened or read.
 */
object Detect {
    private const val VID_APPLE = 0x05AC
    private const val VID_GOOGLE = 0x18D1

    // Android Open Accessory mode: what an Android phone turns into once an Android Auto
    // receiver has started talking to it.
    private val AOA_PIDS = 0x2D00..0x2D05

    // USB vendor ids of common Android phone makers (Samsung, Google, Xiaomi, Huawei, Honor,
    // Oppo/Realme, OnePlus, Vivo, Motorola, Sony, LG, HTC, HMD/Nokia, Asus, Lenovo, ZTE,
    // MediaTek, Qualcomm, Unisoc, TCL, Meizu, Transsion).
    private val PHONE_VENDORS = setOf(
        0x04E8, 0x18D1, 0x2717, 0x12D1, 0x339B, 0x22D9, 0x2A70, 0x2D95, 0x22B8, 0x0FCE, 0x1004,
        0x0BB4, 0x2E04, 0x0B05, 0x17EF, 0x19D2, 0x0E8D, 0x05C6, 0x1782, 0x1BBB, 0x2A45, 0x1D5B
    )

    /** null = not a phone (flash drive, hub, keyboard, adapter...), so the hub ignores it. */
    fun usbType(dev: UsbDevice): PhoneType? {
        if (dev.deviceClass == UsbConstants.USB_CLASS_HUB) return null
        var storageOnly = dev.interfaceCount > 0
        var phoneLike = false
        var humanInputOnly = dev.interfaceCount > 0
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            val cls = itf.interfaceClass
            if (cls != UsbConstants.USB_CLASS_MASS_STORAGE) storageOnly = false
            if (cls != UsbConstants.USB_CLASS_HID && cls != UsbConstants.USB_CLASS_AUDIO) humanInputOnly = false
            // Photo/file transfer (PTP/MTP) or the Android debug interface.
            if (cls == UsbConstants.USB_CLASS_STILL_IMAGE) phoneLike = true
            if (cls == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.interfaceSubclass == 0x42) phoneLike = true
        }
        if (storageOnly || humanInputOnly) return null
        if (dev.vendorId == VID_APPLE) return PhoneType.IPHONE
        if (dev.vendorId == VID_GOOGLE && dev.productId in AOA_PIDS) return PhoneType.ANDROID
        if (dev.vendorId in PHONE_VENDORS || phoneLike) return PhoneType.ANDROID
        return null
    }

    // Bluetooth services that only one side offers: Apple's accessory protocol on iPhones,
    // the wireless Android Auto service on Android phones.
    private val UUID_APPLE_ACCESSORY = ParcelUuid.fromString("00000000-deca-fade-deca-deafdecacaff")
    private val UUID_WIRELESS_ANDROID_AUTO = ParcelUuid.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

    /** false for headsets, watches, speakers and the like. */
    fun btLooksLikePhone(dev: BluetoothDevice): Boolean {
        val major = runCatching { dev.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return true
        return major == BluetoothClass.Device.Major.PHONE ||
            major == BluetoothClass.Device.Major.COMPUTER ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED ||
            major == BluetoothClass.Device.Major.MISC
    }

    /** Best guess for a phone the user has not labelled yet; null = ask the user. */
    fun btGuess(dev: BluetoothDevice): PhoneType? {
        val uuids = runCatching { dev.uuids }.getOrNull()
        if (uuids != null) {
            if (uuids.contains(UUID_APPLE_ACCESSORY)) return PhoneType.IPHONE
            if (uuids.contains(UUID_WIRELESS_ANDROID_AUTO)) return PhoneType.ANDROID
        }
        val name = runCatching { dev.name }.getOrNull() ?: return null
        if (name.contains("iphone", ignoreCase = true)) return PhoneType.IPHONE
        return null
    }
}
