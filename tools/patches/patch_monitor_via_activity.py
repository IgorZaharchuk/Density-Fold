from pathlib import Path
import subprocess
import shutil
import sys

root = Path(".")
branch = subprocess.check_output(
    ["git", "branch", "--show-current"], text=True
).strip()

if branch != "diagnostics/shizuku-lifecycle":
    sys.exit(f"ОШИБКА: ожидалась ветка diagnostics/shizuku-lifecycle, сейчас {branch}")

files = {
    "app/src/main/java/com/densityfold/BaseActionActivity.kt": [
        (
'''        startService(Intent(this, DensityActionService::class.java).apply {
            this.action = this@BaseActionActivity.action
        })''',
'''        val fromMonitor = intent.getBooleanExtra(
            DensityActionService.EXTRA_MONITOR_REQUEST,
            false
        )

        startService(Intent(this, DensityActionService::class.java).apply {
            this.action = this@BaseActionActivity.action
            putExtra(DensityActionService.EXTRA_MONITOR_REQUEST, fromMonitor)
        })'''
        )
    ],
    "app/src/main/java/com/densityfold/FoldMonitorService.kt": [
        (
'''            if (isExternal) {
                DensityActionService.startClosed(applicationContext, fromMonitor = true)
            } else {
                DensityActionService.startOpen(applicationContext, fromMonitor = true)
            }''',
'''            val targetActivity = if (isExternal) {
                ClosedActionActivity::class.java
            } else {
                OpenActionActivity::class.java
            }

            startActivity(
                Intent(this, targetActivity).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(DensityActionService.EXTRA_MONITOR_REQUEST, true)
                }
            )'''
        )
    ],
    "app/src/main/java/com/densityfold/DensityActionService.kt": [
        (
'''                        showNotification(
                            applicationContext,
                            screenName,
                            value.targetDp,
                            true,
                            null
                        )''',
'''                        if (!fromMonitor) {
                            showNotification(
                                applicationContext,
                                screenName,
                                value.targetDp,
                                true,
                                null
                            )
                        }'''
        ),
        (
'''                        showNotification(
                            applicationContext,
                            if (profile == DensityProfile.OUTER) "Внешний" else "Внутренний",
                            0,
                            false,
                            failureMessage
                        )''',
'''                        if (!fromMonitor) {
                            showNotification(
                                applicationContext,
                                if (profile == DensityProfile.OUTER) "Внешний" else "Внутренний",
                                0,
                                false,
                                failureMessage
                            )
                        }'''
        ),
        (
'''                showNotification(
                    applicationContext,
                    if (profile == DensityProfile.OUTER) "Внешний" else "Внутренний",
                    0,
                    false,
                    failureMessage
                )''',
'''                if (!fromMonitor) {
                    showNotification(
                        applicationContext,
                        if (profile == DensityProfile.OUTER) "Внешний" else "Внутренний",
                        0,
                        false,
                        failureMessage
                    )
                }'''
        )
    ]
}

changes = []
for name, replacements in files.items():
    path = root / name
    source = path.read_text()
    updated = source

    for old, new in replacements:
        count = updated.count(old)
        if count != 1:
            sys.exit(
                f"ОШИБКА: {name}: ожидалось одно совпадение, найдено {count}. "
                "Ничего не изменено."
            )
        updated = updated.replace(old, new, 1)

    changes.append((path, source, updated))

for path, source, updated in changes:
    backup = path.with_name(path.name + ".pre_activity_monitor")
    if backup.exists():
        sys.exit(f"ОШИБКА: резервная копия уже существует: {backup}")
    shutil.copy2(path, backup)
    path.write_text(updated)
    print(f"OK: {path}")
    print(f"  backup: {backup}")

print("\nПатч применён.")
