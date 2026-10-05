package android.content;
import java.util.*;
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public ComponentName component;
    public final List<Integer> flags = new ArrayList<Integer>();
    public final Map<String,Object> extras = new LinkedHashMap<String,Object>();
    public void setComponent(ComponentName c) { component = c; }
    public void addFlags(int f) { flags.add(Integer.valueOf(f)); }
    public void putExtra(String k, String v) { extras.put(k, v); }
    public void putExtra(String k, boolean v) { extras.put(k, Boolean.valueOf(v)); }
    public String toString() {
        return "Intent[" + component + " extras=" + extras + "]";
    }
}
