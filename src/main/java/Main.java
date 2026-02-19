import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.io.InputStream.nullInputStream;

void main() throws Exception {
    var port = 8080;
    var yaml = new Yaml();
    var docker = new DockerService(yaml, "localhost", null, null, new CommandService());
    var server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/stacks", new StacksHandler(docker));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    IO.println("Starting server on port " + port);
    server.start();
}

interface DefaultHttpHandler extends HttpHandler {
    @Override
    default void handle(HttpExchange exchange) throws IOException {
        int code = 200;
        Object result;
        var contentType = "text/plain; charset=utf-8";
        try {
            result = switch (exchange.getRequestMethod().toUpperCase()) {
                case "GET" -> doGet(exchange.getRequestBody(), exchange.getRequestHeaders());
                case "POST" -> doPost(exchange.getRequestBody(), exchange.getRequestHeaders());
                case "PUT" -> doPut(exchange.getRequestBody(), exchange.getRequestHeaders());
                case "DELETE" -> doDelete(exchange.getRequestBody(), exchange.getRequestHeaders());
                case "PATCH" -> doPatch(exchange.getRequestBody(), exchange.getRequestHeaders());
                default -> {
                    code = 405;
                    yield "Method Not Allowed\n";
                }
            };
        } catch (SocketException e) {
            code = 504;
            result = e;
        } catch (IOException e) {
            code = 500;
            result = e;
        } catch (Exception e) {
            code = 400;
            result = e;
        }
        String message = null;
        try {
            switch (result) {
                case int c -> {
                    code = c;
                    message = switch (c) {
                        case 200 -> "OK\n";
                        case 400 -> "Bad Request\n";
                        case 404 -> "Not Found\n";
                        case 405 -> "Method Not Allowed\n";
                        case 500 -> "Internal Server Error\n";
                        case 501 -> "Not Implemented\n";
                        default -> "Status " + c + "\n";
                    };
                }
                case Consumer<?> consumer -> {
                    var headersSent = new AtomicReference<OutputStream>();
                    try {
                        ((Consumer<Consumer<byte[]>>) consumer).accept(bytes -> {
                            try {
                                if (headersSent.get() == null) {
                                    exchange.getResponseHeaders().put("Content-Type", List.of("application/octet-stream"));
                                    exchange.sendResponseHeaders(200, 0);
                                    headersSent.set(exchange.getResponseBody());
                                }
                                headersSent.get().write(bytes);
                            } catch (IOException e) {
                                e.printStackTrace(System.err);
                                throw new UncheckedIOException(e);
                            }
                        });
                    } catch (Exception ex) {
                        if (headersSent.get() == null) {
                            exchange.getResponseHeaders().put("Content-Type", List.of("application/octet-stream"));
                            exchange.sendResponseHeaders(500, 0);
                            headersSent.set(exchange.getResponseBody());
                        }
                        try (var os = headersSent.get()) {
                            ex.printStackTrace(new PrintStream(os, true, StandardCharsets.UTF_8));
                        }
                    }
                    return;
                }
                case String s -> {
                    if (s.startsWith("<!")) {
                        contentType = "text/html; charset=utf-8";
                    }
                    message = s;
                }
                case Exception e -> {
                    message = e + "\n";
                }
                default -> message = result.toString();
            }
        } catch (IllegalArgumentException e) {
            code = 400;
            message = "Error processing response: " + e + "\n";
        } catch (Exception e) {
            code = 500;
            message = "Error processing response: " + e + "\n";
        }
        var bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    default Object doGet(InputStream requestBody, Headers requestHeaders) throws Exception {
        return 405;
    }

    default Object doPost(InputStream requestBody, Headers requestHeaders) throws IOException {
        return 405;
    }

    default Object doPut(InputStream requestBody, Headers requestHeaders) throws IOException {
        return 405;
    }

    default Object doDelete(InputStream requestBody, Headers requestHeaders) throws IOException {
        return 405;
    }

    default Object doPatch(InputStream requestBody, Headers requestHeaders) throws IOException {
        return 405;
    }
}

static class StacksHandler implements DefaultHttpHandler {
    private final DockerService dockerService;

    public StacksHandler(DockerService dockerService) {
        this.dockerService = dockerService;
    }

    @Override
    public Object doGet(InputStream requestBody, Headers requestHeaders) throws Exception {
        return (Consumer<Consumer<byte[]>>) consumer -> {
            try {
                dockerService.composeUp(new InputStreamReader(requestBody, StandardCharsets.UTF_8), consumer);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}

public static class DockerService {
    private final CommandService commandService;
    private final String internalDomainName;
    private final String externalDomainName;
    private final Yaml yaml;
    private final String loadBalancerNetwork;

    public DockerService(Yaml yaml,
                         String internalDomainName,
                         String externalDomainName,
                         String loadBalancerNetwork,
                         CommandService commandService) {
        this.yaml = yaml;
        this.loadBalancerNetwork = loadBalancerNetwork != null && !loadBalancerNetwork.isBlank() ? loadBalancerNetwork : null;
        this.internalDomainName = internalDomainName != null && !internalDomainName.isBlank() ? internalDomainName : "localhost";
        this.externalDomainName = externalDomainName != null && !externalDomainName.isBlank() ? externalDomainName : null;
        this.commandService = commandService;
    }

    public int composeUp(Reader yaml, Consumer<byte[]> consumer) throws Exception {
        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) this.yaml.load(yaml);
        if (root == null) {
            throw new IllegalArgumentException("No yaml file provided");
        }

        var existingNetworks = commandService.getString(
                nullInputStream(),
                false,
                false,
                List.of("docker", "network", "ls", "--format", "{{.Name}}")
        );

        var networks = getNetworks(root);
        var networksToCreate = new LinkedHashSet<String>();
        
        // For each service, ensure labels and network
        for (var serviceEntry : getServices(root).entrySet()) {
            var service = serviceEntry.getValue();
            var serviceRef = serviceEntry.getKey();
            service.putIfAbsent("container_name", serviceRef);
            service.putIfAbsent("restart", "unless-stopped");

            for (var networkRef : getNetworkRefs(serviceRef, service)) {
                var network = networks.computeIfAbsent(networkRef, _ -> new LinkedHashMap<>());
                network.putIfAbsent("name", networkRef);
                network.put("driver", "external");
                networksToCreate.add(networkRef);
            }
            var labels = getLabels(serviceEntry);
            if (labels.containsKey("http.port")) {
                labels.remove("http.port");
                labels.putIfAbsent("traefik.enable", "true");
                labels.putIfAbsent("traefik.http.routers." + serviceRef + ".rule",
                    externalDomainName != null && !externalDomainName.isBlank()
                        ? "Host(`" + serviceRef + "." + externalDomainName + "`) || Host(`" + serviceRef + "." + internalDomainName + "`)"
                        : "Host(`" + serviceRef + "." + internalDomainName + "`)");

                if (loadBalancerNetwork != null && !loadBalancerNetwork.isBlank()) {
                    var network = networks.computeIfAbsent(loadBalancerNetwork, k -> new LinkedHashMap<String, Object>());
                    network.putIfAbsent("name", loadBalancerNetwork);
                    network.put("driver", "external");
                    networksToCreate.add(loadBalancerNetwork);
                }
            } 
        }
        try {
            for (var networkRef : networksToCreate) {
                if (!existingNetworks.contains(networkRef)) {
                    commandService.execute(nullInputStream(), true, true, List.of("docker", "network", "create", "--driver", "bridge", networkRef), consumer);
                }
            }
        } catch (Exception e) {
            for (var networkRef : networksToCreate) {
                if (!existingNetworks.contains(networkRef)) {
                    commandService.execute(nullInputStream(), true, true, List.of("docker", "network", "rm", "-f", networkRef), consumer);
                }
            }
            throw e;
        }

        try {
            return commandService.execute(toYamlInputStream(root), false, true, List.of("docker", "compose", "-f", "-", "up", "-d", "--wait"), consumer);
        } catch (Exception e) {
            for (var networkRef : networksToCreate) {
                if (!existingNetworks.contains(networkRef)) {
                    commandService.execute(nullInputStream(), true, true, List.of("docker", "network", "rm", "-f", networkRef), consumer);
                }
            }
            throw e;
        }
    }

    private static InputStream toYamlInputStream(Map<String, Object> root) {
        return new ByteArrayInputStream(toYamlString(root).getBytes(StandardCharsets.UTF_8));
    }

    private static String toYamlString(Map<String, Object> root) {
        var opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        var yaml = new Yaml(opts);
        return yaml.dump(root);
    }

 
    private static Map<String, Map<String, Object>> getNetworks(Map<String, Object> root) {
        var networks = root.get("networks");
        if (networks == null) {
            networks = new LinkedHashMap<String, Object>();      
        }
        if (!(networks instanceof Map networkMap)) {
            throw new RuntimeException("Unsupported .networks declaration");
        }
        return (Map<String, Map<String, Object>>) networkMap;
    }

    private static List<String> getNetworkRefs(String serviceRef, Map<String, Object> service) throws Exception {
        if (serviceRef == null) {
            throw new IllegalArgumentException("serviceRef entry cannot be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service cannot be null");
        }
        var networkRefs = service.get("networks");
        if (networkRefs == null) {
            return new ArrayList<String>();
        }
        if (!(networkRefs instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new RuntimeException("Unsupported services." + serviceRef + ".networks declaration. Only list of strings currently supported.");    
        }
        return (List<String>) networkRefs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> getServices(Map<String, Object> root) {
        // Ensure 'services' exists
        var services = root.get("services");
        if (services == null) {
            throw new RuntimeException("Compose YAML must contain 'services' mapping");
        }
        if (!(services instanceof Map<?, ?> map)) {
            throw new RuntimeException("Invalid service declaration. Must be a dictionary.");
        }
        for (var service : map.entrySet()) {
            if (!(service.getValue() instanceof Map<?, ?>)) {
                throw new RuntimeException("Invalid services." + service.getKey() + " declaration. Must be a dictionary.");
            }
        }
        return (Map<String, Map<String, Object>>) map;
    }

    private static Map<String, Object> getLabels(Map.Entry<String, Map<String, Object>> service) {
        var labelsObj = service.getValue().get("labels");
        Map<String, Object> labels;
        if (labelsObj instanceof Map<?, ?> labelsMap) {
            labels = new LinkedHashMap<>((Map<String, Object>) labelsObj);
        } else if (labelsObj instanceof List) {
            // sometimes labels are a list of strings
            labels = new LinkedHashMap<>();
            for (var l : (List<?>) labelsObj) {
                if (l instanceof String) {
                    var s = (String) l;
                    var idx = s.indexOf('=');
                    if (idx > 0)
                        labels.put(s.substring(0, idx), s.substring(idx + 1));
                    else
                        labels.put(s, "");
                }
            }
        } else {
            labels = new LinkedHashMap<>();
        }
        return labels;
    }
}

class CommandService {
    public String getString(InputStream inputStream,
                         boolean stdErrIsFailure,
                         boolean throwOnFailure,
                         List<String> command) throws Exception {

        var baos = new ByteArrayOutputStream();
        execute(inputStream, stdErrIsFailure, throwOnFailure, command, baos::writeBytes);
        return baos.toString(StandardCharsets.UTF_8);
    }

    public int execute(InputStream inputStream,
                       boolean stdErrIsFailure,
                       boolean throwOnFailure,
                       List<String> command,
                       Consumer<byte[]> consumer) throws Exception {

        var pb = new ProcessBuilder(command);
        pb.environment().putAll(System.getenv());
        pb.redirectError(Redirect.PIPE);
        pb.redirectInput(Redirect.PIPE);
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
    }

}
