import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CommandService {
    public String getString(InputStream inputStream,
                            boolean stdErrIsFailure,
                            boolean throwOnFailure,
                            Map<String, String> env,
                            List<String> command) throws IOException, InterruptedException {

        var baos = new ByteArrayOutputStream();
        execute(inputStream,
                stdErrIsFailure,
                throwOnFailure,
                command,
                env,
                baos::writeBytes);
        return baos.toString(StandardCharsets.UTF_8);
    }

    public int execute(InputStream inputStream,
                       boolean stdErrIsFailure,
                       boolean throwOnFailure,
                       List<String> command,
                       Map<String, String> env,
                       CheckedConsumer<byte[]> consumer) {
        try {
            var pb = new ProcessBuilder(command);
            pb.environment().putAll(System.getenv());
            pb.environment().putAll(env);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            var p = pb.start();

            // Single-threaded I/O using polling approach
            // Note: Process streams are not SelectableChannels, so we use available() polling
            var processStdout = p.getInputStream();
            var processStderr = p.getErrorStream();
            var processStdin = p.getOutputStream();

            var buf = new byte[104800]; // Single reusable buffer

            boolean stdinOpen = true;
            boolean stdoutOpen = true;
            boolean stderrOpen = true;
            boolean inputEOF = false;

            while (stdoutOpen || stderrOpen || stdinOpen) {
                boolean activity = false;

                // Write to process stdin by streaming from inputStream
                if (stdinOpen && !inputEOF) {
                    try {
                        // Check if input data is available
                        int available = inputStream.available();
                        if (available > 0 || inputStream.getClass().getSimpleName().equals("ByteArrayInputStream")) {
                            // Read from input stream
                            int read = inputStream.read(buf, 0, buf.length);
                            if (read > 0) {
                                processStdin.write(buf, 0, read);
                                processStdin.flush();
                                activity = true;
                            } else if (read == -1) {
                                inputEOF = true;
                                activity = true;
                            }
                        }
                    } catch (IOException e) {
                        inputEOF = true;
                    }
                }

                // Close stdin when input stream is exhausted
                if (stdinOpen && inputEOF) {
                    try {
                        processStdin.close();
                    } catch (IOException ignored) {
                    }
                    stdinOpen = false;
                    activity = true;
                }

                // Read from process stdout
                if (stdoutOpen) {
                    try {
                        int available = processStdout.available();
                        if (available > 0) {
                            int read = processStdout.read(buf, 0, Math.min(buf.length, available));
                            if (read > 0) {
                                consumer.accept(Arrays.copyOf(buf, read));
                                activity = true;
                            } else if (read == -1) {
                                stdoutOpen = false;
                                activity = true;
                            }
                        }
                    } catch (IOException e) {
                        stdoutOpen = false;
                    }
                }

                // Read from process stderr
                if (stderrOpen) {
                    try {
                        int available = processStderr.available();
                        if (available > 0) {
                            int read = processStderr.read(buf, 0, Math.min(buf.length, available));
                            if (read > 0) {
                                consumer.accept(Arrays.copyOf(buf, read));
                                activity = true;
                            } else if (read == -1) {
                                stderrOpen = false;
                                activity = true;
                            }
                        }
                    } catch (IOException e) {
                        stderrOpen = false;
                    }
                }

                // If no activity, sleep briefly to avoid busy waiting
                if (!activity) {
                    if (p.waitFor(1, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                }
            }

            var exitCode = p.waitFor();
            if (exitCode != 0 && throwOnFailure) {
                throw new RuntimeException("Exit code " + exitCode + ".");
            }
            return exitCode;
        } catch (Exception e) {
            throw SneakyThrow.sneakyThrow(e);
        }
    }
}
