package com.example.forcehdr

import android.annotation.SuppressLint
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    // Set global XposedInterface reference early, once per process.
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        xposedLog = this
    }

    // Ensure the HDR override runs only once per process
    private val hasExecuted = AtomicBoolean(false)

    @SuppressLint("PrivateApi")
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        try {
            val dmsClass = classLoader.loadClass("com.android.server.display.DisplayManagerService")
            val systemReadyMethod = dmsClass.getDeclaredMethod("systemReady", Boolean::class.javaPrimitiveType)

            deoptimize(systemReadyMethod)

            hook(systemReadyMethod)
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()

                    if (hasExecuted.compareAndSet(false, true)) {
                        logI("systemReady() finished. Scheduling HDR override in 5s...")

                        // Delay to ensure display subsystem is fully ready before spoofing
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching {
                                performSurfaceControlOverride(classLoader)
                            }.onFailure {
                                // Reset on failure to allow retry on next trigger
                                hasExecuted.set(false)
                                logE("HDR Override failed", it)
                            }
                        }, 5000)
                    }

                    result
                }

        } catch (e: Exception) {
            logE("Failed to hook DisplayManagerService.systemReady", e)
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun performSurfaceControlOverride(classLoader: ClassLoader) {
        try {
            val dcClass = classLoader.loadClass("com.android.server.display.DisplayControl")

            val ids = dcClass.getDeclaredMethod("getPhysicalDisplayIds").invoke(null) as? LongArray

            if (ids == null || ids.isEmpty()) {
                logW("No physical display IDs found.")
                return
            }

            val getTokenMethod = dcClass.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            val overrideMethod = dcClass.getDeclaredMethod("overrideHdrTypes", IBinder::class.java, IntArray::class.java)
            val getSupportedMethod = dcClass.getDeclaredMethod("getSupportedHdrOutputTypes")

            val overrideInvoker = getInvoker(overrideMethod)

            /*
             * HDR Type Constants:
             * - Dolby Vision (1): HDR_TYPE_DOLBY_VISION
             * - HDR10 (2):        HDR_TYPE_HDR10
             * - HLG (3):          HDR_TYPE_HLG
             * - HDR10+ (4):       HDR_TYPE_HDR10_PLUS
             *
             * https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/core/java/android/view/Display.java
             */

            // Spoof HDR10, HLG, HDR10+ (no Dolby Vision)
            val targetTypes = intArrayOf(2, 3, 4)

            ids.forEach { id ->
                val token = getTokenMethod.invoke(null, id) as? IBinder
                if (token != null) {
                    overrideInvoker.invoke(null, token, targetTypes)

                    val current = getSupportedMethod.invoke(null) as? IntArray
                    logI("Display ID $id: HDR spoofed to [${current?.joinToString() ?: "none"}]")
                }
            }

        } catch (t: Throwable) {
            logE("HDR Spoofing failed", t)
        }
    }
}