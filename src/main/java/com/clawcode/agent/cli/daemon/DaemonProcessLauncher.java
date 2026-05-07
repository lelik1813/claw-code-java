package com.clawcode.agent.cli.daemon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

@FunctionalInterface
public interface DaemonProcessLauncher {

    StartedDaemonProcess start(String jar, int port) throws IOException;

    default StartedDaemonProcess start(DaemonLaunchRequest request) throws IOException {
        return start(request.jar(), request.port());
    }

    static DaemonProcessLauncher system() {
        return new DaemonProcessLauncher() {
            @Override
            public StartedDaemonProcess start(String jar, int port) throws IOException {
                return start(new DaemonLaunchRequest(jar, port, null, null));
            }

            @Override
            public StartedDaemonProcess start(DaemonLaunchRequest request) throws IOException {
                var command = new ArrayList<String>();
                command.add("java");
                command.add("-jar");
                command.add(request.jar());
                command.add("--server.port=" + request.port());
                command.addAll(request.appArgs());

                var pb = new ProcessBuilder(command);
                pb.environment().putAll(request.environment());
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                pb.directory(Path.of(System.getProperty("user.home")).toFile());
                var process = pb.start();
                return new StartedDaemonProcess(process.pid(), process::destroyForcibly);
            }
        };
    }
}
