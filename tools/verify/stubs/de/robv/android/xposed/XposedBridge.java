package de.robv.android.xposed;
import java.util.*;
public class XposedBridge {
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c, String n, XC_MethodHook cb) {
        Set<XC_MethodHook.Unhook> s = new LinkedHashSet<XC_MethodHook.Unhook>();
        s.add(new XC_MethodHook.Unhook());
        return s;
    }
}
