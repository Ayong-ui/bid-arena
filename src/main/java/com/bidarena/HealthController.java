package com.bidarena;

import java.time.Instant;
import java.util.Map;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Controller;

@Controller
@Mapping("/api/v1")
public class HealthController {
  @Mapping("/health")
  public Map<String, Object> health() {
    return Map.of("status", "UP", "service", "bid-arena", "time", Instant.now().toString());
  }
}
