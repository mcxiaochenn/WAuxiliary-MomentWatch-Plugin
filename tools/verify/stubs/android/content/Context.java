package android.content;
import android.content.pm.PackageManager;
public class Context {
    public static final String NOTIFICATION_SERVICE = "notification";
    public String getPackageName() { return "com.tencent.mm"; }
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return null; }
    public ClassLoader getClassLoader() { return Context.class.getClassLoader(); }
    public PackageManager getPackageManager() { return new PackageManager(); }
}
