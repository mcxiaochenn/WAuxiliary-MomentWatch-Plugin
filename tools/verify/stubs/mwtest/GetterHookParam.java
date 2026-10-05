package mwtest;

/**
 * 测试桩：只通过 getter 暴露参数的另一种回调参数形态。
 * 用来验证插件在没有 args / method 字段时仍能通过 getArgs() / getMember() 取到值。
 */
public class GetterHookParam {
    private final Object[] a;
    private final java.lang.reflect.Member m;
    private final Object r;

    public GetterHookParam(Object[] args, java.lang.reflect.Member member, Object result) {
        this.a = args;
        this.m = member;
        this.r = result;
    }

    public Object[] getArgs() { return a; }
    public java.lang.reflect.Member getMember() { return m; }
    public Object getResult() { return r; }
}
