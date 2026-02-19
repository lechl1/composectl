import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.io.InputStream.nullInputStream;

@SuppressWarnings("unchecked")
public class DockerService {
    private final CommandService commandService;
    private final SecretService secretService;
    private final String internalDomainName;
    private final String externalDomainName;
    private final Yaml yaml;
    private final String loadBalancerNetwork;

    public DockerService(Yaml yaml,
                         String internalDomainName,
                         String externalDomainName,
                         String loadBalancerNetwork,
                         CommandService commandService,
                         SecretService secretService) {
        this.yaml = yaml;
        this.loadBalancerNetwork = loadBalancerNetwork != null && !loadBalancerNetwork.isBlank() ? loadBalancerNetwork : null;
        this.internalDomainName = internalDomainName != null && !internalDomainName.isBlank() ? internalDomainName : "localhost";
        this.externalDomainName = externalDomainName != null && !externalDomainName.isBlank() ? externalDomainName : null;
        this.commandService = commandService;
        this.secretService = secretService;
    }

    public int composeDown(String projectName, Reader yaml, CheckedConsumer<byte[]> consumer) {
        try {
            var root = (Map<String, Object>) this.yaml.load(substituteEnvironmentVariables(yaml, secretService::getOrCreate));
            return commandService.execute(toYamlInputStream(root), false, true, List.of("docker", "compose", "-p", projectName, "-f", "-", "down"), Collections.emptyMap(), consumer);
        } catch (Exception e) {
            throw SneakyThrow.sneakyThrow(e);
        }
    }

    public List<Stack> getStacks() {
        try {
            // Use docker ps with format to get container info including project label
            var containers = commandService.getString(
                    nullInputStream(),
                    false,
                    false,
                    Collections.emptyMap(),
                    List.of("docker", "ps", "-a", "--format", "{{json .}}")).lines()
                    .map(line -> new ObjectMapper().readValue(line, DockerContainer.class))
                    .toList();
            var containersByProject = new LinkedHashMap<String, List<DockerContainer>>();

            for (var container : containers) {
                    var project = Stream.of(container.Labels.split(","))
                            .filter(l -> l.startsWith("com.docker.compose.project="))
                            .map(l -> l.substring("com.docker.compose.project=".length()))
                            .findFirst()
                            .orElse(null);

                    // Only include containers that are part of a compose project
                    if (project != null && !project.isBlank()) {
                        containersByProject.computeIfAbsent(project, k -> new ArrayList<>()).add(container);
                    }
            }
            // Create Stack instances for each project
            return containersByProject.entrySet().stream()
                    .map(entry -> new Stack(entry.getKey(), entry.getValue()))
                    .toList();
        } catch (Exception e) {
            throw SneakyThrow.sneakyThrow(e);
        }
    }

    public int composeUp(String projectName, Reader yaml, CheckedConsumer<byte[]> consumer) {
        try {
            @SuppressWarnings("unchecked")
            var root = (Map<String, Object>) this.yaml.load(substituteEnvironmentVariables(yaml, secretService::getOrCreate));
            if (root == null) {
                throw new IllegalArgumentException("No yaml file provided");
            }

            var existingVolumes = commandService.getString(
                    nullInputStream(),
                    false,
                    false,
                    Collections.emptyMap(),
                    List.of("docker", "volume", "ls", "--format", "{{.Name}}")
            );
            var existingNetworks = commandService.getString(
                    nullInputStream(),
                    false,
                    false,
                    Collections.emptyMap(),
                    List.of("docker", "network", "ls", "--format", "{{.Name}}")
            );

            var networks = getNetworks(root);
            var volumes = getVolumes(root);
            var secrets = getSecrets(root);
            root.put("volumes", volumes);
            root.put("networks", networks);
            root.put("secrets", secrets);
            var networksToCreate = new LinkedHashSet<String>();
            var volumesToCreate = new LinkedHashSet<String>();

            // For each service, ensure labels and network
            for (var serviceEntry : getServices(root).entrySet()) {
                var service = serviceEntry.getValue();
                var serviceRef = serviceEntry.getKey();
                service.putIfAbsent("container_name", serviceRef);
                service.putIfAbsent("restart", "unless-stopped");
                service.putIfAbsent("cpus", "0.2");
                service.putIfAbsent("mem_limit", "128m");
                service.putIfAbsent("memswap_limit", "0");

                for (var secretRef : getSecretRefs(serviceRef, service)) {
                    ((Map<String, Object>) service.computeIfAbsent("environment", k -> new LinkedHashMap<>()))
                            .putIfAbsent(secretRef, "/run/secrets/" + secretRef);
                    secrets.computeIfAbsent(secretRef, _ -> new LinkedHashMap<>())
                            .putIfAbsent("environment", secretRef);
                }

                for (var volumeRef : getVolumeRefs(serviceRef, service)) {
                    var volume = volumes.computeIfAbsent(volumeRef, _ -> new LinkedHashMap<>());
                    volume.putIfAbsent("external", true);
                    volumesToCreate.add(volumeRef);
                }

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
                        if (!networks.containsKey(loadBalancerNetwork)) {
                            var map = networks.computeIfAbsent(loadBalancerNetwork, _ -> new LinkedHashMap<>());
                            map.putIfAbsent("name", loadBalancerNetwork);
                            map.put("driver", "external");
                        }
                    }
                }
            }

            try {
                for (var networkRef : networksToCreate) {
                    if (!existingNetworks.contains(networkRef)) {
                        commandService.execute(nullInputStream(), true, true, List.of("docker", "network", "create", "--driver", "bridge", networkRef), Collections.emptyMap(), consumer);
                    }
                }
                for (var volumeRef : volumesToCreate) {
                    if (!existingVolumes.contains(volumeRef)) {
                        commandService.execute(nullInputStream(), true, true, List.of("docker", "volume", "create", "--driver", "local", volumeRef), Collections.emptyMap(), consumer);
                    }
                }
            } catch (Exception e) {
                for (var networkRef : networksToCreate) {
                    if (!existingNetworks.contains(networkRef)) {
                        commandService.execute(nullInputStream(), true, false, List.of("docker", "network", "rm", "-f", networkRef), Collections.emptyMap(), consumer);
                    }
                }
                for (var volumeRef : volumesToCreate) {
                    if (!existingVolumes.contains(volumeRef)) {
                        commandService.execute(nullInputStream(), true, false, List.of("docker", "volume", "rm", "-f", "local", volumeRef), Collections.emptyMap(), consumer);
                    }
                }
                throw SneakyThrow.sneakyThrow(e);
            }

            try {
                return commandService.execute(toYamlInputStream(root), false, true, List.of("docker", "compose", "-p", projectName, "-f", "-", "up", "-d", "--wait"), secrets.keySet().stream().collect(Collectors.toMap(Function.identity(), secretService::getOrCreate)), consumer);
            } catch (Exception e) {
                for (var networkRef : networksToCreate) {
                    if (!existingNetworks.contains(networkRef)) {
                        commandService.execute(nullInputStream(), true, true, List.of("docker", "network", "rm", "-f",
                                        networkRef),
                                Collections.emptyMap(),
                                consumer);
                    }
                }
                throw SneakyThrow.sneakyThrow(e);
            }
        } catch (Exception e) {
            throw SneakyThrow.sneakyThrow(e);
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

    private static Reader substituteEnvironmentVariables(Reader reader, Function<String, String> getEnv) throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            sb.append((char) c);
        }
        var content = sb.toString();

        // Replace ${X} and $X with environment variable values
        var result = new StringBuilder();
        int i = 0;
        while (i < content.length()) {
            if (content.charAt(i) == '$') {
                if (i + 1 < content.length() && content.charAt(i + 1) == '{') {
                    // Handle ${VAR} format
                    int start = i + 2;
                    int end = content.indexOf('}', start);
                    if (end != -1) {
                        var varName = content.substring(start, end);
                        var varValue = getEnv.apply(varName);
                        result.append(varValue != null ? varValue : "${" + varName + "}");
                        i = end + 1;
                    } else {
                        result.append(content.charAt(i));
                        i++;
                    }
                } else if (i + 1 < content.length() && Character.isJavaIdentifierStart(content.charAt(i + 1))) {
                    // Handle $VAR format
                    int start = i + 1;
                    int end = start;
                    while (end < content.length() && (Character.isJavaIdentifierPart(content.charAt(end)) || content.charAt(end) == '_')) {
                        end++;
                    }
                    var varName = content.substring(start, end);
                    var varValue = getEnv.apply(varName);
                    result.append(varValue != null ? varValue : "$" + varName);
                    i = end;
                } else {
                    result.append(content.charAt(i));
                    i++;
                }
            } else {
                result.append(content.charAt(i));
                i++;
            }
        }

        return new InputStreamReader(
                new ByteArrayInputStream(result.toString().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8
        );
    }


    private static Map<String, Map<String, Object>> getNetworks(Map<String, Object> root) {
        var networks = root.get("networks");
        if (networks == null) {
            networks = new LinkedHashMap<String, Object>();
        }
        if (!(networks instanceof Map<?, ?> networkMap)) {
            throw new RuntimeException("Unsupported .networks declaration");
        }
        return (Map<String, Map<String, Object>>) networkMap;
    }

    private static Map<String, Map<String, Object>> getSecrets(Map<String, Object> root) {
        var secrets = root.get("secrets");
        if (secrets == null) {
            secrets = new LinkedHashMap<String, Object>();
        }
        if (!(secrets instanceof Map<?, ?> volumeMap)) {
            throw new RuntimeException("Unsupported .secrets declaration");
        }
        return (Map<String, Map<String, Object>>) volumeMap;
    }

    private static Map<String, Map<String, Object>> getVolumes(Map<String, Object> root) {
        var volumes = root.get("volumes");
        if (volumes == null) {
            volumes = new LinkedHashMap<String, Object>();
        }
        if (!(volumes instanceof Map<?, ?> volumeMap)) {
            throw new RuntimeException("Unsupported .volumes declaration");
        }
        return (Map<String, Map<String, Object>>) volumeMap;
    }

    private static List<String> getSecretRefs(String serviceRef, Map<String, Object> service) {
        if (serviceRef == null) {
            throw new IllegalArgumentException("serviceRef entry cannot be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service cannot be null");
        }
        var secretRefs = service.get("secrets");
        if (secretRefs == null) {
            return new ArrayList<>();
        }
        if (!(secretRefs instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new RuntimeException("Unsupported services." + serviceRef + ".networks declaration. Only list of strings currently supported.");
        }
        return ((List<String>) secretRefs);
    }


    private static List<String> getVolumeRefs(String serviceRef, Map<String, Object> service) {
        if (serviceRef == null) {
            throw new IllegalArgumentException("serviceRef entry cannot be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service cannot be null");
        }
        var volumeRefs = service.get("volumes");
        if (volumeRefs == null) {
            return new ArrayList<>();
        }
        if (!(volumeRefs instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new RuntimeException("Unsupported services." + serviceRef + ".networks declaration. Only list of strings currently supported.");
        }
        return ((List<String>) volumeRefs).stream().map(y -> y.split(":")[0]).toList();
    }


    private static List<String> getNetworkRefs(String serviceRef, Map<String, Object> service) {
        if (serviceRef == null) {
            throw new IllegalArgumentException("serviceRef entry cannot be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service cannot be null");
        }
        var networkRefs = service.get("networks");
        if (networkRefs == null) {
            return new ArrayList<>();
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
        } else if (labelsObj instanceof List<?>) {
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
