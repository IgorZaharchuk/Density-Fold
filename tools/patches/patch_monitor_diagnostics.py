from pathlib import Path
from datetime import datetime

files = {
    "monitor": Path("app/src/main/java/com/densityfold/FoldMonitorService.kt"),
    "activity": Path("app/src/main/java/com/densityfold/BaseActionActivity.kt"),
    "service": Path("app/src/main/java/com/densityfold/DensityActionService.kt"),
}

original = {key: path.read_text() for key, path in files.items()}
updated = dict(original)

def replace_once(key, old, new, description):
    count = updated[key].count(old)
    if count != 1:
        raise RuntimeError(
            f"{description}: ожидалось одно совпадение, найдено {count}"
        )
    updated[key] = updated[key].replace(old, new, 1)
    print("OK:", description)

replace_once(
    "monitor",
    """            val success = intent.getBooleanExtra(
                DensityActionService.EXTRA_SUCCESS,
                false
            )
""",
    """            val success = intent.getBooleanExtra(
                DensityActionService.EXTRA_SUCCESS,
                false
            )
            Log.i(TAG, "Получен результат действия от сервиса: success=$success")
""",
    "лог получения результата"
)

replace_once(
    "monitor",
    """        val isExternal = active.isExternal

        if (actionInFlight) {
""",
    """        val isExternal = active.isExternal

        Log.i(
            TAG,
            "Текущее состояние дисплея: ${if (isExternal) "Внешний" else "Внутренний"}, " +
                "${active.width}x${active.height}, DPI=${active.density}"
        )
        updateMonitorNotification(
            formatDisplayStatus(isExternal, active.shortPx, active.density)
        )

        if (actionInFlight) {
""",
    "немедленное обновление уведомления"
)

replace_once(
    "monitor",
    """            startActivity(
                Intent(this, targetActivity).apply {
""",
    """            Log.i(
                TAG,
                "Запуск Activity монитором: ${targetActivity.simpleName}, " +
                    "экран=${if (isExternal) "Внешний" else "Внутренний"}"
            )
            startActivity(
                Intent(this, targetActivity).apply {
""",
    "лог запуска Activity"
)

replace_once(
    "activity",
    """        val fromMonitor = intent.getBooleanExtra(
            DensityActionService.EXTRA_MONITOR_REQUEST,
            false
        )

        startService""",
    """        val fromMonitor = intent.getBooleanExtra(
            DensityActionService.EXTRA_MONITOR_REQUEST,
            false
        )
        Log.i(TAG, "Activity получила запрос: monitor=$fromMonitor action=$action")

        startService""",
    "лог получения запроса Activity"
)

replace_once(
    "service",
    """        sendBroadcast(intent)
    }

    private fun showNotification(""",
    """        Log.i(
            TAG,
            "Отправка результата монитору: monitor=$fromMonitor success=$success " +
                "external=${applied?.isExternal ?: expectedExternal} error=${error ?: "none"}"
        )
        sendBroadcast(intent)
    }

    private fun showNotification(""",
    "лог отправки результата сервисом"
)

for key, path in files.items():
    backup = path.with_name(
        path.name + ".pre_monitor_diagnostics_" +
        datetime.now().strftime("%Y%m%d_%H%M%S")
    )
    backup.write_text(original[key])
    path.write_text(updated[key])
    print("Обновлён:", path)
    print("Резервная копия:", backup)

print("\nГотово. Изменены только три исходных файла.")
