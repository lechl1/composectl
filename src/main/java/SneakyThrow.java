public class SneakyThrow {
    @SuppressWarnings("unchecked")
    public static <E extends Throwable, T extends RuntimeException> T sneakyThrow(E t) {
        return (T) t;
    }
}
