import com.sun.tools.javac.Main;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class SecretService {
    private final Path envFilePath;
    private final Random random;
    private final Object lock = new Object();

    public SecretService(Path envFilePath, Random random) {
        this.envFilePath = envFilePath;
        this.random = random;
    }

    /**
     * Gets the value of an existing key, or creates a new entry with a generated UUID if not found.
     *
     * @param key the environment variable key
     * @return the existing or newly created value
     * @throws IOException if file operations fail
     */
    public String getOrCreate(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key cannot be null or blank");
        }

        synchronized (lock) {
            Map<String, String> entries = null;
            entries = readEnvFile();

            // Check if key already exists
            if (entries.containsKey(key)) {
                return entries.get(key);
            }

            String safeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._+";
            // Generate new value
            var bytes = new StringBuilder(24);
            for (int i = 0; i < 24; i++) {
                bytes.append(safeChars.charAt(random.nextInt(0, safeChars.length())));
            }
            entries.put(key, bytes.toString());
            writeEnvFile(entries);
            return entries.get(key);
        }
    }

    /**
     * Removes an entry from the .env file.
     *
     * @param key the environment variable key to remove
     * @return true if the key was found and removed, false otherwise
     * @throws IOException if file operations fail
     */
    public boolean remove(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key cannot be null or blank");
        }

        synchronized (lock) {
            var entries = readEnvFile();

            // Check if key exists
            if (!entries.containsKey(key)) {
                return false;
            }

            // Remove the key
            entries.remove(key);

            // Write back to file
            writeEnvFile(entries);

            return true;
        }
    }

    /**
     * Reads the .env file and returns a map of key-value pairs.
     * Creates the file if it doesn't exist.
     */
    private Map<String, String> readEnvFile() {
        try {
            var entries = new LinkedHashMap<String, String>();

            // Create file if it doesn't exist
            if (!Files.exists(envFilePath)) {
                Files.createFile(envFilePath);
                return entries;
            }

            var lines = Files.readAllLines(envFilePath, StandardCharsets.UTF_8);
            for (var line : lines) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Parse key=value
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    var key = line.substring(0, equalsIndex).trim();
                    var value = line.substring(equalsIndex + 1).trim();

                    // Remove quotes if present
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                        value = value.substring(1, value.length() - 1);
                    } else if (value.startsWith("'") && value.endsWith("'") && value.length() > 1) {
                        value = value.substring(1, value.length() - 1);
                    }

                    entries.put(key, value);
                }
            }

            return entries;
        } catch (IOException e) {
            throw SneakyThrow.sneakyThrow(e);
        }
    }

    /**
     * Writes the map of key-value pairs to the .env file.
     */
    private void writeEnvFile(Map<String, String> entries) {
        try {
            var lines = new ArrayList<String>();

            for (var entry : entries.entrySet()) {
                // Quote values that contain spaces or special characters
                var value = entry.getValue();
                lines.add(entry.getKey() + "=" + value);
            }

            Files.write(envFilePath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw SneakyThrow.sneakyThrow(e);
        }
    }
}
