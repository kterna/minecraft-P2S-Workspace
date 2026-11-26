import com.google.gson.*;
import com.p2s.agent.core.*;
import com.p2s.agent.llm.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

public class ProtocolProbe {
    record Trial(LlmApi api, String base, String endpoint, String variant) {}
    static final String MODEL="demo", KEY="local-synthetic", USER="user probe", SYSTEM="system probe";
    static void check(boolean valid,String label){ if(!valid)throw new AssertionError(label); }
    static void bearer(HttpExchange ex){check(("Bearer "+KEY).equals(ex.getRequestHeaders().getFirst("Authorization")),"Bearer");}
    static String wireReply(Trial t,HttpExchange ex,JsonObject body){
        String route=ex.getRequestURI().getPath(), answer=t.api().id()+"-ok";
        check(ex.getRequestMethod().equals("POST")&&route.equals(t.endpoint()),"route: "+route+" expected "+t.endpoint());
        check(!body.toString().contains("\"temperature\":"),"temperature");
        switch(t.api()){
            case OPENAI_COMPLETIONS,MISTRAL -> {
                bearer(ex); var m=body.getAsJsonArray("messages");
                check(MODEL.equals(body.get("model").getAsString()) && m.get(0).getAsJsonObject().get("role").getAsString().equals("system") && USER.equals(m.get(1).getAsJsonObject().get("content").getAsString()),"chat");
                return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""+answer+"\"},\"finish_reason\":\"stop\"}]}";
            }
            case OPENAI_RESPONSES -> {
                bearer(ex); check(MODEL.equals(body.get("model").getAsString()) && SYSTEM.equals(body.get("instructions").getAsString()) && body.getAsJsonArray("input").toString().contains(USER) && !body.get("store").getAsBoolean(),"responses");
                return "{\"id\":\"r_demo\",\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\""+answer+"\"}]}]}";
            }
            case ANTHROPIC_MESSAGES -> {
                check(KEY.equals(ex.getRequestHeaders().getFirst("x-api-key"))&&ex.getRequestHeaders().containsKey("anthropic-version"),"anthropic headers");
                check(MODEL.equals(body.get("model").getAsString()) && body.get("system").toString().contains(SYSTEM) && body.get("messages").toString().contains(USER) && body.get("max_tokens").getAsInt()>0,"anthropic");
                return "{\"id\":\"msg_demo\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"demo\",\"content\":[{\"type\":\"text\",\"text\":\""+answer+"\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}";
            }
            case GOOGLE_GENERATIVE_AI,GOOGLE_VERTEX -> {
                if(t.api()==LlmApi.GOOGLE_VERTEX)bearer(ex); else check(KEY.equals(ex.getRequestHeaders().getFirst("x-goog-api-key")),"Google API key");
                check(body.get("systemInstruction").toString().contains(SYSTEM) && body.get("contents").toString().contains(USER),"Google message");
                return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\""+answer+"\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":1}}";
            }
            case BEDROCK_CONVERSE -> {
                if(t.variant().equals("sigv4")){
                    String header=ex.getRequestHeaders().getFirst("Authorization");
                    check(header.startsWith("AWS4-HMAC-SHA256 Credential=SYNTHETIC/")&&header.contains("/us-east-1/bedrock/aws4_request")&&header.matches(".*Signature=[0-9a-f]{64}")&&"session-demo".equals(ex.getRequestHeaders().getFirst("x-amz-security-token")),"SigV4 headers");
                }else bearer(ex);
                check(body.get("system").toString().contains(SYSTEM)&&body.get("messages").toString().contains(USER),"Bedrock body");
                return "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\""+answer+"\"}]}},\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":2,\"outputTokens\":1}}";
            }
        }
        throw new AssertionError("unhandled api");
    }
    public static void main(String[] args) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var current=new AtomicReference<Trial>(); var errors=new AtomicReference<Throwable>(); var seen=new AtomicInteger();
        server.createContext("/", ex->{
            String content; int status=200;
            try{
                var t=current.get();
                var body=JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                content=wireReply(t,ex,body);seen.incrementAndGet();
                if(t.variant().equals("refusal")){status=429;content="{\"error\":{\"message\":\"synthetic rate limit\"}}";}
            }catch(Throwable e){errors.set(e);status=400;content="{\"error\":\"local assertion\"}";}
            byte[] b=content.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().add("Content-Type","application/json");
            ex.sendResponseHeaders(status,b.length);try(var out=ex.getResponseBody()){out.write(b);}
        }); server.start();
        try{
            String origin="http://127.0.0.1:"+server.getAddress().getPort();String vertex="/v1/projects/demo/locations/test/publishers/google"; var cfgs=List.of(
                new Trial(LlmApi.OPENAI_COMPLETIONS,"/v1/chat/completions","/v1/chat/completions","normal"),
                new Trial(LlmApi.MISTRAL,"/mistral/v1","/mistral/v1/chat/completions","normal"),
                new Trial(LlmApi.OPENAI_RESPONSES,"/v1","/v1/responses","normal"),new Trial(LlmApi.ANTHROPIC_MESSAGES,"/v1/messages","/v1/messages","normal"),
                new Trial(LlmApi.GOOGLE_GENERATIVE_AI,"/v1beta","/v1beta/models/demo:generateContent","normal"),
                new Trial(LlmApi.GOOGLE_VERTEX,vertex,vertex+"/models/demo:generateContent","normal"),
                new Trial(LlmApi.BEDROCK_CONVERSE,"","/model/demo/converse","normal"),new Trial(LlmApi.BEDROCK_CONVERSE,"","/model/demo/converse","sigv4"),
                new Trial(LlmApi.OPENAI_COMPLETIONS,"/v1/chat/completions","/v1/chat/completions","refusal"));
            var context=new StreamFunction.Context(SYSTEM,List.of(AgentMessage.User.of(USER)),List.of());
            for(var t:cfgs){
                current.set(t);var options=new JsonObject();options.addProperty("region","us-east-1");
                if(t.variant().equals("sigv4")){options.addProperty("accessKeyId","SYNTHETIC");options.addProperty("secretAccessKey","synthetic-secret");options.addProperty("sessionToken","session-demo");}
                var cfg=new LlmRequestConfig.Builder().api(t.api()).apiUrl(origin+t.base()).apiKey(KEY).model(MODEL).providerOptions(options).timeoutSeconds(10).streaming(false).build();
                var out=LlmTransports.stream(()->cfg).stream(context,AbortSignal.none(),event->{});
                if(errors.get()!=null)throw new AssertionError("fixture failed",errors.get());
                if(t.variant().equals("refusal")) check(out.stopReason()==AgentMessage.StopReason.ERROR && out.errorMessage().contains("429")&&out.errorMessage().contains("synthetic rate limit"),"429 mapping: "+out.errorMessage());
                else check(!out.failed() && out.text().equals(t.api().id()+"-ok"),"decode "+t.api()+" text="+out.text()+" error="+out.errorMessage());
                System.out.println("PASS "+t.api().id()+" "+t.variant()+" POST "+t.endpoint());
            }
            check(seen.get()==9,"request count");
            System.out.println("7 configured protocols, 9 HTTP cases, all passed");
        }finally{server.stop(0);}
        System.exit(0);
    }
}
