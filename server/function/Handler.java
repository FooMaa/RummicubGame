import java.util.Map;
import java.util.function.Function;

public class Handler implements Function<Map<String, Object>, Map<String, Object>> {
    private final com.rummiclub.server.CloudFunction delegate = new com.rummiclub.server.CloudFunction();
    @Override public Map<String, Object> apply(Map<String, Object> event) { return delegate.apply(event); }
}
