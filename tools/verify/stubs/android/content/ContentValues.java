package android.content;
import java.util.*;
public class ContentValues {
    private final Map<String,Object> m = new HashMap<String,Object>();
    public void put(String k, String v) { m.put(k, v); }
    public void put(String k, Long v) { m.put(k, v); }
    public void put(String k, Integer v) { m.put(k, v); }
    public Object get(String k) { return m.get(k); }
    public String getAsString(String k) { Object v = m.get(k); return v == null ? null : String.valueOf(v); }
    public Long getAsLong(String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number) return Long.valueOf(((Number) v).longValue());
        try { return Long.valueOf(String.valueOf(v)); } catch (Throwable t) { return null; }
    }
}
