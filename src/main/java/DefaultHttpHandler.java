import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public interface DefaultHttpHandler extends HttpHandler {
    @Override
    default void handle(HttpExchange exchange) {
        int code = 200;
        Object result = 404;
        var contentType = new AtomicReference<>("text/plain; charset=utf-8");
        var body = new AtomicReference<OutputStream>();
        try {
            var path = Path.of("/" + String.join("/", Stream.of(exchange.getRequestURI().getPath().split("/"))
                    .filter(s -> !s.isBlank())
                    .toList()));
            var request = new HttpRequest(path, exchange.getRequestBody(), exchange.getRequestHeaders());
            result = switch (exchange.getRequestMethod().toUpperCase()) {
                case "GET" -> doGet(request);
                case "POST" -> doPost(request);
                case "PUT" -> doPut(request);
                case "DELETE" -> doDelete(request);
                case "PATCH" -> doPatch(request);
                default -> {
                    code = 405;
                    yield "Method Not Allowed\n";
                }
            };
        } catch (SocketException e) {
            send(body, exchange, 500, contentType, e);
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
                case CheckedFunction<?, ?> function -> {
                    result = ((CheckedFunction<CheckedConsumer<byte[]>, Object>) function).apply(bytes -> {
                        send(body, exchange, 200, contentType, bytes);
                    });
                    return;
                }
                case CheckedConsumer<?> consumer -> {
                    ((CheckedConsumer<CheckedConsumer<byte[]>>) consumer).accept(bytes -> {
                        send(body, exchange, 200, contentType, bytes);
                    });
                    return;
                }
                case String s -> {
                    if (s.startsWith("<!")) {
                        contentType.set("text/html; charset=utf-8");
                    }
                    message = s;
                }
                case Exception e -> {
                    message = e + "\n";
                }
                default -> {
                    contentType .set("application/json");
                    message = new String(new ObjectMapper().writeValueAsBytes(result), StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException e) {
            code = 400;
            message = "Error processing response: " + e + "\n";
        } catch (Exception e) {
            code = 500;
            message = "Error processing response: " + e + "\n";
        }
        var bytes = message.getBytes(StandardCharsets.UTF_8);
        send(body, exchange, code, contentType, bytes);
    }

    default Object doGet(HttpRequest request) throws Exception {
        return 405;
    }

    default Object doPost(HttpRequest request) throws IOException, Exception {
        return 405;
    }

    default Object doPut(HttpRequest request) throws IOException {
        return 405;
    }

    default Object doDelete(HttpRequest request) throws IOException {
        return 405;
    }

    default Object doPatch(HttpRequest request) throws IOException {
        return 405;
    }


    static void send(AtomicReference<OutputStream> body, HttpExchange exchange, int code, AtomicReference<String> contentType, Object bytes) {
        try {
            if (bytes instanceof byte[] b) {
                if (body.get() == null) {
                    exchange.getResponseHeaders().put("Content-Type", List.of(contentType.get()));
                    exchange.sendResponseHeaders(code, 0);
                    body.set(exchange.getResponseBody());
                }
                body.get().write(b);
            } else if (bytes instanceof String s) {
                if (body.get() == null) {
                    exchange.getResponseHeaders().put("Content-Type", List.of(contentType.get()));
                    exchange.sendResponseHeaders(code, 0);
                    body.set(exchange.getResponseBody());
                }
                body.get().write(s.getBytes(StandardCharsets.UTF_8));
            } else if (bytes instanceof Throwable) {
                if (body.get() == null) {
                    exchange.getResponseHeaders().put("Content-Type", List.of("text/plain; charset=utf-8"));
                    exchange.sendResponseHeaders(code, 0);
                    body.set(exchange.getResponseBody());
                }
                ((Throwable) bytes).printStackTrace(new PrintStream(body.get(), true, StandardCharsets.UTF_8));
            } else {
                if (body.get() == null) {
                    exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
                    exchange.sendResponseHeaders(code, 0);
                    body.set(exchange.getResponseBody());
                }
                body.get().write(new ObjectMapper().writeValueAsBytes(bytes));
            }
            body.get().flush();
        } catch (Exception e) {
            throw SneakyThrow.sneakyThrow(e);
        }
    }
}
