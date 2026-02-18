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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

void main() throws Exception {
    var port = 8080;
    var dockerService = new DockerService();
    var server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/stacks", new StacksHandler(dockerService));
    server.createContext("/", new RootHandler());
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    IO.println("Starting server on port " + port);
    server.start();
}

static class StacksHandler implements HttpHandler {
    private final DockerService dockerService;

    public StacksHandler(DockerService dockerService) {
        this.dockerService = dockerService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            send(exchange, 405, "Only POST allowed\n");
        } catch (Exception e) {
            var sw = new StringWriter();
            var pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            send(exchange, 400, sw.toString());
            System.err.println(sw.toString());
        }
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

static class RootHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            var bytes = "Only GET allowed\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(405, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        var html = """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>stackctl - up</title></head><body>
                <h3>Paste Docker Compose YAML and click Up</h3>
                <textarea id="compose" rows="20" cols="80"></textarea><br/>
                <button id="btn">Up</button>
                <pre id="out" style="white-space:pre-wrap; border:1px solid #ccc; padding:8px; margin-top:12px; max-height:300px; overflow:auto"></pre>
                <script>document.getElementById('btn').onclick = async function(){
                  var txt = document.getElementById('compose').value;
                  document.getElementById('out').textContent = 'Sending...';
                  try{
                    var r = await fetch('/up',{method:'POST', body: txt});
                    var t = await r.text();
                    document.getElementById('out').textContent = t;
                  }catch(e){ document.getElementById('out').textContent = 'Error: ' + e; }
                };</script>
                </body></html>
                """;

        var bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

record CommandResult(ByteArrayOutputStream stdOut,
        ByteArrayOutputStream stdErr,
        boolean stdErrIsFailure,
        boolean throwOnFailure,
        int exitCode) {

    public boolean isFailure() {
        return exitCode != 0 && stdErrIsFailure && stdErr.size() > 0;
    }

    public boolean shouldThrow() {
        return throwOnFailure && isFailure();
    }

    @Override
    public final String toString() {
        return isFailure() && stdErr.size() > 0
            ? stdErr.toString(StandardCharsets.UTF_8)
            : stdOut.toString(StandardCharsets.UTF_8);
    }
}

class DockerService {
    private final CommandService commandService;
    private final String internalDomainName;
    private final String externalDomainName;
    private final String loadBalancerNetwork;

    public DockerService() {
        this("localhost", null, null, new CommandService());
    }

    public DockerService(String internalDomainName, String externalDomainName, String loadBalancerNetwork, CommandService commandService) {
        this.loadBalancerNetwork = loadBalancerNetwork;
        this.internalDomainName = internalDomainName;
        this.externalDomainName = externalDomainName;
        this.commandService = commandService;
    }

    public CommandResult composeUp(Map<String, Object> root) throws Exception {
        var existingNetworks = Set.of(commandService.getString(InputStream.nullInputStream(), false, false, List.of("docker", "network", "ls", "--format", "{{.Name}}")).split("\\s+"));
        var networks = getNetworks(root);
        var networksToCreate = new LinkedHashSet<String>();
        
        // For each service, ensure labels and network
        for (var serviceEntry : getServices(root).entrySet()) {
            var service = serviceEntry.getValue();
            var serviceRef = serviceEntry.getKey();
            service.putIfAbsent("container_name", serviceRef);
            service.putIfAbsent("restart", "unless-stopped");

            for (var networkRef : getNetworkRefs(serviceRef, service)) {
                var network = (Map<String, Object>) networks.computeIfAbsent(networkRef, k -> new LinkedHashMap<String, Object>());
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
                    var network = (Map<String, Object>) networks.computeIfAbsent(loadBalancerNetwork, k -> new LinkedHashMap<String, Object>());
                    network.putIfAbsent("name", loadBalancerNetwork);
                    network.put("driver", "external");
                    networksToCreate.add(loadBalancerNetwork);
                }
            } 
        }
        try {
            for (var networkRef : networksToCreate) {
                if (!existingNetworks.contains(networkRef)) {
                    commandService.execute(InputStream.nullInputStream(), true, true, List.of("docker", "network", "create", "--driver", "bridge", networkRef));
                }
            }
        } catch (Exception e) {
            for (var networkRef : networksToCreate) {
                if (!existingNetworks.contains(networkRef)) {
                    commandService.execute(InputStream.nullInputStream(), true, true, List.of("docker", "network", "rm", "-f", networkRef));
                }
            }
            throw e;
        }

        try {
            return commandService.execute(toYamlInputStream(root), false, true, List.of("docker", "compose", "-f", "-", "up", "-d", "--wait"));
        } catch (Exception e) {
            for (var networkRef : networksToCreate) {
                if (!existingNetworks.contains(networkRef)) {
                    commandService.execute(InputStream.nullInputStream(), true, true, List.of("docker", "network", "rm", "-f", networkRef));
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
    public String getString(InputStream inputStream, boolean stdErrIsFailure, boolean throwOnFailure,
            List<String> command) throws Exception {
        return new String(execute(inputStream, stdErrIsFailure, throwOnFailure, command).stdOut().toByteArray());
    }

    public CommandResult execute(InputStream inputStream, boolean stdErrIsFailure, boolean throwOnFailure, List<String> command) throws Exception {
        var pb = new ProcessBuilder(command);
        pb.environment().putAll(System.getenv());
        pb.redirectError(Redirect.PIPE);
        pb.redirectInput(Redirect.PIPE);
        var p = pb.start();

        var successBuffer = new ByteArrayOutputStream(1000000);
        var errorBuffer = new ByteArrayOutputStream(1000000);
        try (var threadPoolExecutor = new ThreadPoolExecutor(0, 2, 0, TimeUnit.MICROSECONDS,
                new ArrayBlockingQueue<>(3),
                Thread.ofVirtual().uncaughtExceptionHandler(this::uncaughtException).factory(), null)) {
            threadPoolExecutor.submit(() -> transfer(p.getInputStream(), successBuffer));
            threadPoolExecutor.submit(() -> transfer(p.getErrorStream(), errorBuffer));
            var outputStream = p.getOutputStream();
            inputStream.transferTo(outputStream);
            var exitCode = p.waitFor();
            var result = new CommandResult(successBuffer, errorBuffer, stdErrIsFailure, throwOnFailure, exitCode);
            if (result.shouldThrow()) {
                   throw new RuntimeException("Exit code " + exitCode + ". Error: "
                            + new String(errorBuffer.toByteArray(), StandardCharsets.UTF_8));
            }
            return new CommandResult(successBuffer, errorBuffer, stdErrIsFailure, throwOnFailure, exitCode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void transfer(InputStream inputStream, OutputStream outputStream) {
        try {
            inputStream.transferTo(outputStream);
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    private void uncaughtException(Thread t, Throwable e) {
        if (e instanceof InterruptedException) {
            return;   
        }
        e.printStackTrace(System.err);
    }
}
