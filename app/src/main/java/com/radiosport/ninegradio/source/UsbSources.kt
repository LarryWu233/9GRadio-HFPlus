package com.radiosport.ninegradio.source

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.radiosport.ninegradio.usb.RtlSdrDevice

object UsbSources {
    fun isSupported(device: UsbDevice) = AirspyHfSource.isSupported(device) || RtlSdrDevice.isSupported(device)
    fun create(manager: UsbManager, device: UsbDevice): IqSource =
        if (AirspyHfSource.isSupported(device)) AirspyHfSource(manager, device) else RtlSdrDeviceSource(manager, device)
    fun name(device: UsbDevice) = if (AirspyHfSource.isSupported(device)) "Airspy HF+ Discovery" else RtlSdrDevice.getDeviceName(device)
}
