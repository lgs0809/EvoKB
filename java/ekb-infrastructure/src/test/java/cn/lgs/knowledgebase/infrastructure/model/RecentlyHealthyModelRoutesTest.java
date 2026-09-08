package cn.lgs.knowledgebase.infrastructure.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RecentlyHealthyModelRoutesTest {
  @Test
  void onlyTheSameRecentlyWorkingRouteModelAndCredentialQualifies() {
    var clock = new AtomicLong(1);
    var routes = new RecentlyHealthyModelRoutes(clock::get);
    String key = RecentlyHealthyModelRoutes.identity("https://gateway/v1", "small", "private-a");
    assertFalse(routes.recentlySucceeded(key));
    routes.succeeded(key);
    assertTrue(routes.recentlySucceeded(key));
    assertFalse(
        routes.recentlySucceeded(
            RecentlyHealthyModelRoutes.identity("https://other/v1", "small", "private-a")));
    assertFalse(
        routes.recentlySucceeded(
            RecentlyHealthyModelRoutes.identity("https://gateway/v1", "large", "private-a")));
    assertFalse(
        routes.recentlySucceeded(
            RecentlyHealthyModelRoutes.identity("https://gateway/v1", "small", "private-b")));
    assertFalse(key.contains("private-a"));
    clock.addAndGet(Duration.ofMinutes(10).toNanos() + 1);
    assertFalse(routes.recentlySucceeded(key));
  }

  @Test
  void configurationChurnDoesNotRetainUnboundedHealthOrTrustAnEvictedRoute() {
    var routes = new RecentlyHealthyModelRoutes();
    for (int index = 0; index < 17; index++) routes.succeeded("route-" + index);
    assertFalse(routes.recentlySucceeded("route-0"));
    assertTrue(routes.recentlySucceeded("route-16"));
  }
}
