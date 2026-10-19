package com.example.smoke;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Child service JVM launched by DistributedSmoke, optionally without any agent. */
public final class DistributedService {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("tracelens-distributed-service-");
        Tomcat tomcat = new Tomcat();
        try {
            Path app = temp.resolve("app");
            Path classes = app.resolve("WEB-INF/classes");
            Files.createDirectories(classes);
            final Path source = Paths.get(args[0]);
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws java.io.IOException {
                    Files.createDirectories(classes.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    Files.copy(file, classes.resolve(source.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + (args[1].equals("javax")
                    ? "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\" version=\"3.0\" metadata-complete=\"true\">"
                    : "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\" metadata-complete=\"true\">")
                    + "<servlet><servlet-name>service</servlet-name><servlet-class>com.example.distributed.DistributedServlet</servlet-class>"
                    + "<load-on-startup>1</load-on-startup></servlet>"
                    + "<servlet-mapping><servlet-name>service</servlet-name><url-pattern>/</url-pattern></servlet-mapping></web-app>";
            Files.write(app.resolve("WEB-INF/web.xml"), xml.getBytes(StandardCharsets.UTF_8));
            tomcat.setBaseDir(temp.resolve("container").toString());
            tomcat.setPort(0);
            tomcat.getConnector().setProperty("address", "127.0.0.1");
            tomcat.getConnector().setProperty("maxThreads", "4");
            Context context = tomcat.addWebapp("", app.toString());
            for (org.apache.catalina.LifecycleListener listener : context.findLifecycleListeners()) {
                if (listener instanceof Tomcat.DefaultWebXmlListener) context.removeLifecycleListener(listener);
            }
            tomcat.start();
            int debugPort = -1;
            if (Boolean.parseBoolean(args[2])) {
                debugPort = ((Number) Class.forName("com.chargebee.tracelens.agent.StandaloneRuntime")
                        .getMethod("port").invoke(null)).intValue();
                if (debugPort <= 0) throw new AssertionError("child standalone debug server unavailable");
            }
            System.out.println("FIXTURE_READY " + tomcat.getConnector().getLocalPort() + " " + debugPort);
            System.out.flush();
            // Parent closing stdin or writing a newline shuts down only this test service.
            System.in.read();
        } finally {
            try { tomcat.stop(); tomcat.destroy(); }
            finally { deleteTree(temp); }
        }
    }

    static void deleteTree(Path temp) throws Exception {
        Files.walkFileTree(temp, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, java.io.IOException failure) throws java.io.IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
