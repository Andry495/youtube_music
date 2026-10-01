package com.youtubevoice.app.dpi

object ByeDpiNative {
    init {
        System.loadLibrary("byedpi")
    }

    external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    external fun jniStartProxy(fd: Int): Int
    external fun jniStopProxy(fd: Int): Int
}
