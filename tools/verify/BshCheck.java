import bsh.Parser;
import bsh.ParseException;
import java.io.*;

public class BshCheck {
    public static void main(String[] args) throws Exception {
        int fail = 0;
        for (String p : args) {
            Reader r = new InputStreamReader(new FileInputStream(p), "UTF-8");
            Parser parser = new Parser(r);
            int n = 0;
            try {
                while (!parser.Line()) { n++; }
                System.out.println("OK    " + p + "   nodes=" + n);
            } catch (ParseException e) {
                fail++;
                System.out.println("FAIL  " + p + "   line=" + e.getErrorLineNumber() + "  " + e.getMessage());
            } catch (Throwable t) {
                fail++;
                System.out.println("ERR   " + p + "   " + t);
            } finally {
                try { r.close(); } catch (Throwable ignored) {}
            }
        }
        System.out.println(fail == 0 ? "ALL PARSE OK" : (fail + " FILE(S) FAILED"));
    }
}
