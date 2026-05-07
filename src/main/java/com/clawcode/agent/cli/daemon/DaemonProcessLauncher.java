package com.clawcode.agent.cli.daemon;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface DaemonProcessLauncher {

    StartedDaemonProcess start(String jar, int port) throws IOException;

    static DaemonProcessLauncher system() {
        return (jar, port) -> {
            var pb = new ProcessBuilder(
                "java", "-jar", jar,
                "--server.port=" + port
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.directory(Path.of(System.getProperty("user.home")).toFile());
            var process = pb.start();
            return new StartedDaemonProcess(process.pid(), process::destroyForcibly);
        };
    }
}
