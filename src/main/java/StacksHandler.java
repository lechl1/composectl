
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class StacksHandler implements DefaultHttpHandler {
    private final DockerService dockerService;

    public StacksHandler(DockerService dockerService) {
        this.dockerService = dockerService;
    }

    @Override
    public Object doGet(HttpRequest request) {
        var path = request.path();
        if (path.getNameCount() == 2) {
            return dockerService.getStacks();
        }
        return 404;
    }

    @Override
    public Object doPut(HttpRequest request) {
        return (CheckedFunction<CheckedConsumer<byte[]>, Object>) consumer -> {
            var path = request.path();
            if (path.getNameCount() == 3) {
                var projectName = path.getName(2).toString();
                var exitCode = dockerService.composeUp(projectName, new InputStreamReader(request.body(), StandardCharsets.UTF_8), consumer);
                return exitCode == 0 ? 200 : 500;
            }
            return 404;
        };
    }

    @Override
    public Object doPost(HttpRequest request) {
        return (CheckedFunction<CheckedConsumer<byte[]>, Object>) consumer -> {
            var path = request.path();
            if (path.getNameCount() == 4 && path.endsWith("/down")) {
                var projectName = path.getName(2).toString();
                var exitCode = dockerService.composeDown(projectName, new InputStreamReader(request.body(), StandardCharsets.UTF_8), consumer);
                return exitCode == 0 ? 200 : 500;
            }
            return 404;
        };
    }
}
