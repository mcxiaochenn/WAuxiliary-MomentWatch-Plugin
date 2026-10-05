package mwtest;
/**
 * 测试桩：等价于 Xposed 的 XC_MethodHook.MethodHookParam。
 * 插件完全通过反射读取 args / method / getResult()，因此不依赖任何 Xposed 类型，
 * 用任意具备同样成员的类都能驱动插件逻辑。
 */
public class FakeHookParam {
    public Object[] args;
    public java.lang.reflect.Member method;
    private Object result;
    public FakeHookParam(Object[] args, java.lang.reflect.Member method, Object result) {
        this.args = args;
        this.method = method;
        this.result = result;
    }
    public Object getResult() { return result; }
}
