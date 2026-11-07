package com.densityfold.engine;
import rikka.shizuku.Shizuku;
public class ShizukuExec {
    public static Process newProcess(String[] command, String[] env, String dir) {
        return Shizuku.newProcess(command, env, dir);
    }
}
