import java.util.List;

public class Container {
    private final String id;
    private final List<String> names;
    private final String name;
    private final String status;
    private final String project;
    private final String statusKey;

    public Container(String id, List<String> names) {
        this.id = id;
        this.names = names;
        this.name = names != null && !names.isEmpty() ? names.get(0) : null;
        this.status = null;
        this.project = null;
        this.statusKey = null;
    }

    public Container(String id, String name, String status, String project, String statusKey) {
        this.id = id;
        this.name = name;
        this.names = name != null ? List.of(name) : List.of();
        this.status = status;
        this.project = project;
        this.statusKey = statusKey;
    }

    public String getId() {
        return id;
    }

    public List<String> getNames() {
        return names;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getProject() {
        return project;
    }

    public String getStatusKey() {
        return statusKey;
    }
}
