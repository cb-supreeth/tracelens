package com.example.smoke;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Uses a real conflicting socket before the child JVM's premain executes. */
public final class OccupiedPortSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "child".equals(args[0])) {
            int port = ((Number) Class.forName("com.chargebee.tracelens.agent.StandaloneRuntime")
                    .getMethod("port").invoke(null)).intValue();
            if (port != -1) throw new AssertionError("occupied port left a partially started debug server");
            Class<?> bridge = Class.forName("com.chargebee.tracelens.bridge.TraceRuntime", true, null);
            if ((Boolean) bridge.getMethod("hasSink").invoke(null)) {
                throw new AssertionError("failed standalone startup left its sink installed");
            }
            System.out.println("HOST_APPLICATION_STARTED");
            return;
        }
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(java, "-javaagent:" + args[0] + "=standalone",
                    "-Dtracelens.port=" + occupied.getLocalPort(), "-cp", System.getProperty("java.class.path"),
                    OccupiedPortSmoke.class.getName(), "child").redirectErrorStream(true).start();
            try {
                if (!child.waitFor(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("optional agent prevented host process from terminating");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream in = child.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int length;
                    while ((length = in.read(buffer)) != -1) output.write(buffer, 0, length);
                }
                String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
                if (child.exitValue() != 0 || !text.contains("HOST_APPLICATION_STARTED")) {
                    throw new AssertionError("host did not start with occupied debug port: " + text);
                }
                if (!text.contains("standalone runtime disabled") || !text.contains("BindException")) {
                    throw new AssertionError("expected explicit unavailable-port diagnostic: " + text);
                }
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
        }
        System.out.println("STANDALONE_OCCUPIED_PORT_SMOKE_OK");
    }
}
