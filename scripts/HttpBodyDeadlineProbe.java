package cn.lgs.knowledgebase.infrastructure.model;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Real loopback headers/body phases, zero model calls; preserves failed deadline proof. */
public final class HttpBodyDeadlineProbe {
  public static void main(String[] args) throws Exception {
    var calls=new AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    byte[] bytes="complete response payload with an intentionally delayed body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    server.createContext("/body",exchange->{
      calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200,bytes.length);
      try(var out=exchange.getResponseBody()) {
        out.write(bytes,0,8);out.flush();
        try {Thread.sleep(700);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        out.write(bytes,8,bytes.length-8);
      }
    });server.start();
    long start=System.nanoTime();Throwable failure=null;byte[] returned=null;
    try {
      returned=BudgetedHttpRetry.send(HttpClient.newHttpClient(),HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/body"))
          .timeout(Duration.ofMillis(200)).POST(HttpRequest.BodyPublishers.ofString("complete unchanged original request")).build(),"embedding",false);
    }catch(Throwable e){failure=e;}
    double elapsed=(System.nanoTime()-start)/1_000_000d;
    server.stop(0);
    var checks=Map.of("headersAndSlowBodyStayWithinTotal200msWithSmallSchedulingAllowance",elapsed<450 && failure!=null,"singleOriginalRequestNoDeadlineExtension",calls.get()==1);
    var result=new LinkedHashMap<String,Object>();result.put("status",checks.values().stream().allMatch(Boolean.TRUE::equals)?"PASS_HTTP_SLOW_BODY_DEADLINE":"FAIL_HTTP_SLOW_BODY_DEADLINE");result.put("checks",checks);
    result.put("configuredObservationMillis",200);result.put("delayedBodyMillis",700);result.put("actualObservedMillis",elapsed);result.put("actualHttpRequests",calls.get());
    result.put("returnedCompleteBody",returned!=null && Arrays.equals(returned,bytes));result.put("failure",failure==null?null:failure.toString());result.put("modelCalls",0);
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),result);
    System.exit(result.get("status").toString().startsWith("PASS")?0:1);
  }
}
