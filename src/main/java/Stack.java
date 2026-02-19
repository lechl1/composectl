import java.util.List;
import java.util.Map;

public class Stack {
    private final String name;
    private final List<DockerContainer> containers;

    public Stack(String name, List<DockerContainer> containers) {
        this.name = name;
        this.containers = containers;
    }

    public Stack(String name, List<DockerContainer> containers, Map<String, List<DockerContainer>> containersByStatus) {
        this.name = name;
        this.containers = containers;
    }

    public String getName() {
        return name;
    }

    public List<DockerContainer> getContainers() {
        return containers;
    }
}
