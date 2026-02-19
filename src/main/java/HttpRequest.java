import com.sun.net.httpserver.Headers;

import java.io.InputStream;
import java.nio.file.Path;

public record HttpRequest(Path path, InputStream body, Headers headers) {
}
