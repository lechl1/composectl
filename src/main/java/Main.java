import com.sun.net.httpserver.HttpServer;
import org.yaml.snakeyaml.Yaml;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.nio.file.Path;

void main() throws Exception {
    var port = 8080;
    var yaml = new Yaml();
    var docker = new DockerService(
            yaml, "localhost", null, "homelab", new CommandService(), new SecretService(Path.of(
            ".", "prod.env"
    ), new SecureRandom()));
    var server = HttpServer.create(new InetSocketAddress(port), 0);
    var stackHandler = new StacksHandler(docker);
    server.createContext("/api/stack", stackHandler);
    server.createContext("/api/stacks", stackHandler);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    IO.println("Starting server on port " + port);
    server.start();
}
