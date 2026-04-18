/*
 * Copyright 2025 Blocker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.merxury.blocker.core.controllers.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.pm.IPackageManager
import android.os.Build
import com.merxury.blocker.core.controllers.IController
import com.merxury.blocker.core.controllers.utils.ContextUtils.userId
import com.merxury.blocker.core.model.ComponentState
import com.merxury.blocker.core.model.data.ComponentInfo
import com.merxury.blocker.core.utils.PackageInfoDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ShizukuController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageInfoDataSource: PackageInfoDataSource,
) : IController {
    private val pm: IPackageManager by lazy {
        IPackageManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("package"),
            ),
        )
    }

    override suspend fun switchComponent(
        component: ComponentInfo,
        state: ComponentState,
    ): Boolean {
        val packageName = component.packageName
        val componentName = component.name
        // 0 means kill the application
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pm.setComponentEnabledSetting(
                    ComponentName(packageName, componentName),
                    state.pmValue,
                    0,
                    context.userId,
                    context.packageName,
                )
            } else {
                pm.setComponentEnabledSetting(
                    ComponentName(packageName, componentName),
                    state.pmValue,
                    0,
                    context.userId,
                )
            }
            true
        } catch (e: SecurityException) {
            // ADB-launched Shizuku runs as shell uid and cannot change components
            // of normal (non test-only) apps. On MIUI/HyperOS we can route the call
            // through the system-uid `miui.mqsas.IMQSNative` service as a fallback.
            if (trySwitchViaMiuiMqsas(packageName, componentName, state.pmValue)) {
                true
            } else {
                throw e
            }
        }
    }

    /**
     * Bypass the SHELL_UID restriction on Xiaomi devices by asking the system-uid
     * `miui.mqsas.IMQSNative` service (transaction 21, "exec service call") to
     * invoke `IPackageManager.setComponentEnabledSetting` on our behalf.
     *
     * The transaction code of `setComponentEnabledSetting` differs per Android
     * release because the AIDL ordering changes. We resolve it at runtime via
     * reflection on the AIDL-generated `IPackageManager.Stub` class so this
     * works across versions instead of hard-coding per SDK.
     */
    private suspend fun trySwitchViaMiuiMqsas(
        packageName: String,
        componentName: String,
        pmValue: Int,
    ): Boolean {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) &&
            !Build.BRAND.equals("Xiaomi", ignoreCase = true) &&
            !Build.BRAND.equals("Redmi", ignoreCase = true) &&
            !Build.BRAND.equals("POCO", ignoreCase = true)
        ) {
            return false
        }
        val pkgTxn = resolveSetComponentEnabledTxn() ?: run {
            Timber.w("MIUI mqsas bypass: cannot resolve transaction code on SDK ${Build.VERSION.SDK_INT}")
            return false
        }
        // Reject anything that could break out of the single-quoted inner command.
        if (packageName.any { it == '\'' || it == '\\' } ||
            componentName.any { it == '\'' || it == '\\' }
        ) {
            Timber.w("MIUI mqsas bypass: refusing unsafe component name")
            return false
        }
        val cmd = "service call miui.mqsas.IMQSNative 21 i32 1 s16 \"service\" i32 1 " +
            "s16 'call package $pkgTxn i32 1 s16 $packageName s16 $componentName " +
            "i32 $pmValue i32 0 i32 0 s16 shell' " +
            "s16 '/data/mqsas/call.txt' i32 60"
        return try {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val process = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null)
                as Process
            val exit = process.waitFor()
            Timber.i("MIUI mqsas bypass exit=$exit pkg=$packageName comp=$componentName txn=$pkgTxn")
            // Verify the change actually took effect; mqsas always returns 0.
            exit == 0 && checkComponentEnableState(packageName, componentName) ==
                (pmValue != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        } catch (t: Throwable) {
            Timber.e(t, "MIUI mqsas bypass failed")
            false
        }
    }

    /**
     * Resolve the AIDL transaction code for
     * `IPackageManager.setComponentEnabledSetting` at runtime.
     *
     * AIDL generates a `static final int TRANSACTION_<method>` field on the
     * `Stub` class for each method, equal to `IBinder.FIRST_CALL_TRANSACTION + N`.
     * Reading it reflectively avoids per-version hard-coding.
     */
    private fun resolveSetComponentEnabledTxn(): Int? = try {
        val field = IPackageManager.Stub::class.java
            .getDeclaredField("TRANSACTION_setComponentEnabledSetting")
        field.isAccessible = true
        field.getInt(null)
    } catch (t: Throwable) {
        Timber.w(t, "Failed to resolve TRANSACTION_setComponentEnabledSetting")
        null
    }

    override suspend fun enable(component: ComponentInfo): Boolean = switchComponent(
        component,
        ComponentState.ENABLED,
    )

    override suspend fun disable(component: ComponentInfo): Boolean = switchComponent(
        component,
        ComponentState.DISABLED,
    )

    override suspend fun checkComponentEnableState(
        packageName: String,
        componentName: String,
    ): Boolean = packageInfoDataSource.checkComponentIsEnabled(
        ComponentName(packageName, componentName),
    )
}
