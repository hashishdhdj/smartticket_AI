import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Pattern;

/** JDK-only HTTP smoke; reads ephemeral synthetic credentials without printing them. */
class LinuxRuntimeSmoke {
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static HttpResponse<String> request(String method,String path,String body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083"+path)).timeout(Duration.ofSeconds(25));
        if(token!=null)builder.header("X-API-Key",token);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static void status(HttpResponse<String> reply,int expected) {
        if(reply.statusCode()!=expected)throw new IllegalStateException("Unexpected HTTP status "+reply.statusCode());
    }
    static long number(String json,String field) {
        var match=Pattern.compile("\""+field+"\"\\s*:\\s*(\\d+)").matcher(json);
        if(!match.find())throw new IllegalStateException("Required numeric field missing");
        return Long.parseLong(match.group(1));
    }
    public static void main(String[] args) throws Exception {
        var props=new Properties();
        try(var reader=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)){props.load(reader);}
        String submitter=props.getProperty("smartticket.phase3.users[0].token");
        String handler=props.getProperty("smartticket.phase3.users[1].token");
        String admin=props.getProperty("smartticket.phase3.users[2].token");
        long owner=Long.parseLong(props.getProperty("smartticket.phase3.users[0].id"));
        long deadline=System.nanoTime()+Duration.ofSeconds(100).toNanos();
        boolean ready=false;
        while(System.nanoTime()<deadline){
            try{if(request("GET","/api/tickets",null,submitter).statusCode()==200){ready=true;break;}}
            catch(Exception unavailable){}
            Thread.sleep(1000);
        }
        if(!ready)throw new IllegalStateException("Linux application readiness timeout");
        var created=request("POST","/api/tickets","{\"title\":\"Linux runtime smoke\",\"description\":\"Synthetic only\",\"creatorId\":1,\"priority\":\"MEDIUM\"}",submitter);
        status(created,200);
        if(number(created.body(),"code")!=0||number(created.body(),"creatorId")!=owner)throw new IllegalStateException("Trusted actor contract failed");
        long id=number(created.body(),"id");
        status(request("GET","/api/tickets/"+id,null,submitter),200);
        status(request("GET","/api/tickets/"+id,null,null),401);
        status(request("GET","/api/tickets/"+id,null,handler),403);
        status(request("GET","/api/admin/metrics",null,admin),200);
        var stopped=request("POST","/api/agent/runs","{\"message\":\"Check ticket status\"}",submitter);
        status(stopped,200);
        if(!stopped.body().contains("\"status\":\"STOPPED\"")||!stopped.body().contains("\"needHuman\":true"))
            throw new IllegalStateException("Offline Agent stop contract failed");
        Files.writeString(Path.of(args[1]),"{\"status\":\"PASS\",\"synthetic_ticket_id\":"+id
            +",\"trusted_identity\":true,\"unauthenticated_status\":401,\"foreign_handler_status\":403,\"admin_metrics\":true,\"offline_agent_stopped\":true}");
    }
}
