/**
 * Represents the output of 'docker ps --format {{json .}}'
 * All fields are nullable.
 */
public class DockerContainer {
    public String Command;
    public String CreatedAt;
    public String ID;
    public String Image;
    public String Labels;
    public String LocalVolumes;
    public String Mounts;
    public String Names;
    public String Networks;
    public String Platform;
    public String Ports;
    public String RunningFor;
    public String Size;
    public String State;
    public String Status;
}
