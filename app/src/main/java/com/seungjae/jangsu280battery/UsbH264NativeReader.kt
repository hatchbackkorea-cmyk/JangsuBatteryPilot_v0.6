package com.seungjae.jangsu280battery

import java.util.concurrent.atomic.AtomicLong

class UsbH264NativeReader(
    private val onFrame: (ByteArray, Long, Int) -> Unit,
    private val onState: (Int, String) -> Unit,
) {
    private val handle = AtomicLong(0L)

    init {
        System.loadLibrary("timegate_uvc_h264")
    }

    fun start(
        fd: Int,
        endpointAddress: Int,
        endpointType: Int,
        packetSize: Int,
        transferBytes: Int,
        packetsPerUrb: Int = 32,
    ): Boolean {
        stop()
        val listener = Listener(onFrame, onState)
        val native = nativeStart(
            fd,
            endpointAddress,
            endpointType,
            packetSize,
            transferBytes,
            packetsPerUrb,
            listener,
        )
        if (native == 0L) return false
        retainedListener = listener
        handle.set(native)
        return true
    }

    fun stop() {
        val value = handle.getAndSet(0L)
        if (value != 0L) runCatching { nativeStop(value) }
        retainedListener = null
    }

    @Volatile private var retainedListener: Listener? = null

    private class Listener(
        private val frame: (ByteArray, Long, Int) -> Unit,
        private val state: (Int, String) -> Unit,
    ) {
        @Suppress("unused")
        fun onNativeFrame(data: ByteArray, ptsUs: Long, sequence: Int) {
            frame(data, ptsUs, sequence)
        }

        @Suppress("unused")
        fun onNativeState(code: Int, message: String) {
            state(code, message)
        }
    }

    private external fun nativeStart(
        fd: Int,
        endpointAddress: Int,
        endpointType: Int,
        packetSize: Int,
        transferBytes: Int,
        packetsPerUrb: Int,
        listener: Any,
    ): Long

    private external fun nativeStop(handle: Long)
}
