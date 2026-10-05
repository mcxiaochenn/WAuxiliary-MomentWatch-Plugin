package de.robv.android.xposed;
public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public Object[] args;
        public java.lang.reflect.Member method;
        private Object result;
        public Object getResult() { return result; }
        public void setResult(Object o) { result = o; }
    }
    public static class Unhook {
        public boolean unhooked = false;
        public void unhook() { unhooked = true; }
    }
    protected void beforeHookedMethod(MethodHookParam p) throws Throwable { }
    protected void afterHookedMethod(MethodHookParam p) throws Throwable { }
}
