package org.selsup.crpt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class CrptApi {

  private static final String DEFAULT_BASE_URL = "https://ismp.crpt.ru";
  private static final String DEFAULT_CREATE_PATH = "/api/v3/lk/documents/create";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final RateLimiter rateLimiter;
  private final String baseUrl;
  private final String createPath;

  /**
   * Main constructor from the task.
   *
   * @param timeUnit window size for rate-limit (e.g. SECONDS)
   * @param requestLimit max allowed requests in one window (must be > 0)
   */
  public CrptApi(TimeUnit timeUnit, int requestLimit) {
    this(DEFAULT_BASE_URL, DEFAULT_CREATE_PATH, timeUnit, requestLimit);
  }

  public CrptApi(String baseUrl, String createPath, TimeUnit timeUnit, int requestLimit) {
    if (requestLimit <= 0) {
      throw new IllegalArgumentException("requestLimit must be > 0");
    }
    Objects.requireNonNull(timeUnit, "timeUnit");
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
    this.createPath = Objects.requireNonNull(createPath, "createPath");

    this.httpClient =
        HttpClient.newBuilder()
            .version(Version.HTTP_2)
            .followRedirects(Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    this.objectMapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    this.rateLimiter = new SlidingWindowRateLimiter(requestLimit, timeUnit);
  }

  /**
   * Create "introduce to circulation (RF)" document. Body format expected by the test task:
   * {"document": {...}, "signature": "..."}.
   *
   * @param document Java object (POJO/Map/record) representing CRPT document
   * @param signature detached signature string (usually Base64)
   * @param bearerToken token value (only the token string, method adds "Bearer " header)
   * @return parsed DocumentId if server returns {"value":"..."}, otherwise value == null
   * @throws IOException network/serialization errors
   * @throws InterruptedException if thread was interrupted while waiting for rate-limit or HTTP
   */
  public DocumentId createIntroduceRf(Object document, String signature, String bearerToken)
      throws IOException, InterruptedException {

    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(signature, "signature");
    Objects.requireNonNull(bearerToken, "bearerToken");

    // Block if limit is reached (strict sliding window)
    rateLimiter.acquire();
    try {
      // Prepare body: {"document": { ... }, "signature": "..."}
      CreateBody body = new CreateBody(document, signature);
      byte[] jsonBody = objectMapper.writeValueAsBytes(body);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + createPath))
              .timeout(Duration.ofSeconds(30))
              .header("Authorization", "Bearer " + bearerToken)
              .header("Content-Type", "application/json")
              .header("Accept", "*/*")
              .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody))
              .build();

      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

      int status = response.statusCode();
      if (status / 100 == 2) {
        // Typical success payload is {"value":"..."}; parse leniently
        try {
          return objectMapper.readValue(response.body(), DocumentId.class);
        } catch (Exception ignore) {
          return new DocumentId(null);
        }
      }

      // Propagate readable error
      String errorText = new String(response.body(), StandardCharsets.UTF_8);
      throw new IOException("CRPT API error [HTTP " + status + "]: " + errorText);
    } finally {
      // Wake up waiters; window may have moved forward
      rateLimiter.release();
    }
  }

  /* ===================== Inner classes (single-file requirement) ===================== */

  /** Request payload wrapper: {"document": {...}, "signature": "..."} */
  public static final class CreateBody {
    public Object document;
    public String signature;

    public CreateBody() {
      /* for Jackson */
    }

    public CreateBody(Object document, String signature) {
      this.document = document;
      this.signature = signature;
    }
  }

  /** Typical success response: {"value":"..."} */
  public static final class DocumentId {
    public String value;

    public DocumentId() {
      /* for Jackson */
    }

    public DocumentId(String value) {
      this.value = value;
    }
  }

  /** Minimal rate limiter contract. */
  interface RateLimiter {
    void acquire() throws InterruptedException;

    void release();
  }

  /**
   * Simple sliding-window limiter: not more than N calls per 1 * timeUnit. Implementation uses
   * synchronized + wait/notify to keep it junior-friendly.
   */
  static final class SlidingWindowRateLimiter implements RateLimiter {
    private final int limit;
    private final long windowNanos;

    private final Object lock = new Object();
    private final Deque<Long> starts = new ArrayDeque<>();

    SlidingWindowRateLimiter(int limit, TimeUnit unit) {
      if (limit <= 0) {
        throw new IllegalArgumentException("limit must be > 0");
      }
      this.limit = limit;
      this.windowNanos = unit.toNanos(1);
    }

    @Override
    public void acquire() throws InterruptedException {
      long now = System.nanoTime();
      synchronized (lock) {
        purge(now);
        while (starts.size() >= limit) {
          long oldest = starts.peekFirst();
          long waitNanos = Math.max(1L, windowNanos - (now - oldest));
          long ms = waitNanos / 1_000_000L;
          int ns = (int) (waitNanos % 1_000_000L);
          lock.wait(ms, ns);
          now = System.nanoTime();
          purge(now);
        }
        starts.addLast(System.nanoTime());
      }
    }

    @Override
    public void release() {
      synchronized (lock) {
        lock.notifyAll();
      }
    }

    private void purge(long now) {
      while (!starts.isEmpty() && now - starts.peekFirst() >= windowNanos) {
        starts.removeFirst();
      }
    }
  }
}
