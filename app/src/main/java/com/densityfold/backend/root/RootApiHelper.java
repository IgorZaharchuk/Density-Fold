package com.densityfold.backend.root;

import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class RootApiHelper {

    private static final int USER_CURRENT = -2;

    private RootApiHelper() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable error) {
            Throwable cause = error;

            while (cause instanceof InvocationTargetException
                    && ((InvocationTargetException) cause).getCause() != null) {
                cause = ((InvocationTargetException) cause).getCause();
            }

            System.err.println(
                    "ERROR:" + cause.getClass().getSimpleName()
                            + ": " + String.valueOf(cause.getMessage())
            );
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Недостаточно аргументов");
        }

        String operation = args[0];
        int displayId = Integer.parseInt(args[1]);

        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "window");

        if (binder == null) {
            throw new IllegalStateException("Binder службы WindowManager недоступен");
        }

        Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        Object windowManager = asInterface.invoke(null, binder);

        Class<?> interfaceClass = Class.forName("android.view.IWindowManager");

        Method getDensity = interfaceClass.getMethod(
                "getBaseDisplayDensity", int.class
        );

        if ("get".equals(operation)) {
            int density = (Integer) getDensity.invoke(windowManager, displayId);

            if (density <= 0) {
                throw new IllegalStateException(
                        "WindowManager вернул недопустимую плотность: " + density
                );
            }

            System.out.println("SUCCESS:Override density: " + density);
            return;
        }

        if ("set".equals(operation)) {
            if (args.length != 3) {
                throw new IllegalArgumentException("Не указано значение DPI");
            }

            int dpi = Integer.parseInt(args[2]);

            if (dpi < 1 || dpi > 10000) {
                throw new IllegalArgumentException("Недопустимое значение DPI: " + dpi);
            }

            Method setDensity = interfaceClass.getMethod(
                    "setForcedDisplayDensityForUser",
                    int.class, int.class, int.class
            );

            setDensity.invoke(windowManager, displayId, dpi, USER_CURRENT);

            int actual = (Integer) getDensity.invoke(windowManager, displayId);

            if (actual != dpi) {
                throw new IllegalStateException(
                        "Проверка не пройдена: задано " + dpi + ", получено " + actual
                );
            }

            System.out.println("SUCCESS:Override density: " + actual);
            return;
        }

        throw new IllegalArgumentException("Неизвестная операция: " + operation);
    }
}
