package demo;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DemoApp {
    public static void main(String[] args) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(new File("target/tomcat").getAbsolutePath());
        tomcat.setPort(Integer.parseInt(System.getProperty("demo.port", "8085")));
        tomcat.getConnector();
        Context ctx = tomcat.addContext("", new File(".").getAbsolutePath());
        Tomcat.addServlet(ctx, "orders", new OrderServlet()).setAsyncSupported(false);
        ctx.addServletMappingDecoded("/orders/*", "orders");
        tomcat.start();

        Thread bg = new Thread(() -> {
            while (true) {
                new ReportJob().run();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "report-scheduler-1");
        bg.setDaemon(true);
        bg.start();
        tomcat.getServer().await();
    }

    public static class OrderServlet extends HttpServlet {
        private final OrderService service = new OrderService();

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String path = req.getRequestURI();
            if (path.endsWith("/fail")) throw new IllegalStateException("boom");
            int n = service.loadOrder(path);
            resp.setStatus(200);
            resp.getWriter().write("order " + n);
        }
    }

    public static class OrderService {
        private final Repository repo = new Repository();

        public int loadOrder(String id) {
            validate(id);
            List<byte[]> rows = repo.query(id);
            return render(rows);
        }

        void validate(String id) {
            busy(2);
        }

        int render(List<byte[]> rows) {
            int t = 0;
            for (byte[] r : rows) t += format(r);
            return t;
        }

        int format(byte[] r) {
            busy(0);
            return r.length;
        }

        static void busy(int ms) {
            long end = System.nanoTime() + ms * 1_000_000L;
            while (System.nanoTime() < end) Math.sqrt(System.nanoTime());
        }
    }

    public static class Repository {
        public List<byte[]> query(String id) {
            List<byte[]> out = new ArrayList<>();
            for (int i = 0; i < 20; i++) out.add(fetchRow(i));
            try {
                Thread.sleep(15);
            } catch (InterruptedException ignored) {
            }
            return out;
        }

        byte[] fetchRow(int i) {
            return new byte[1024 * (i + 1)];
        }
    }

    public static class ReportJob implements Runnable {
        @Override
        public void run() {
            List<long[]> keep = new ArrayList<>();
            for (int i = 0; i < 30; i++) keep.add(crunch(i));
            OrderService.busy(120);
        }

        long[] crunch(int i) {
            return new long[50_000];
        }
    }
}
