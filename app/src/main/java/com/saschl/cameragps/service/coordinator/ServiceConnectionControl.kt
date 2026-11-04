package com.saschl.cameragps.service.coordinator

/** Connection ownership needed by shutdown policy, independent of Android GATT objects. */
interface ServiceConnectionControl {
    fun connect(mac: String): Boolean
    fun hasConnections(): Boolean
    fun pauseDevice(mac: String)
    fun connectedCount(): Int
    fun disconnectAll()
}
