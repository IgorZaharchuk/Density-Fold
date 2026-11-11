package com.densityfold.engine

data class ShizukuRuntimeState(
    val mainBinder: Boolean,
    val permission: Boolean,
    val userService: Boolean
) {
    val isAvailable: Boolean
        get() = mainBinder && permission

    val isReady: Boolean
        get() = isAvailable && userService

    val label: String
        get() = when {
            !mainBinder -> "MAIN_BINDER_DEAD"
            !permission -> "PERMISSION_DENIED"
            !userService -> "USER_SERVICE_DISCONNECTED"
            else -> "READY"
        }
}
