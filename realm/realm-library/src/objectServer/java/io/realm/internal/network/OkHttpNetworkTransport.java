package io.realm.internal.network;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import io.realm.internal.objectstore.OsJavaNetworkTransport;
import io.realm.mongodb.AppException;
import io.realm.mongodb.ErrorCode;
import io.realm.mongodb.log.obfuscator.HttpLogObfuscator;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSource;

public class OkHttpNetworkTransport extends OsJavaNetworkTransport {

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private volatile OkHttpClient client = null;
    private volatile OkHttpClient streamClient = null;

    @Nullable
    private final HttpLogObfuscator httpLogObfuscator;

    public OkHttpNetworkTransport(@Nullable HttpLogObfuscator httpLogObfuscator) {
        this.httpLogObfuscator = httpLogObfuscator;
    }

    private okhttp3.Request makeRequest(String method, String url, Map<String, String> headers, String body){
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
        switch (method) {
            case "get":
                builder.get();
                break;
            case "delete":
                builder.delete(RequestBody.create(JSON, body));
                break;
            case "patch":
                builder.patch(RequestBody.create(JSON, body));
                break;
            case "post":
                builder.post(RequestBody.create(JSON, body));
                break;
            case "put":
                builder.put(RequestBody.create(JSON, body));
                break;
            default:
                throw new IllegalArgumentException("Unknown method type: " + method);
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

    @Override
    public Response sendRequest(String method, String url, long timeoutMs, Map<String, String> headers, String body) {
        try {
            OkHttpClient client = getClient(timeoutMs);

            okhttp3.Response response = null;
            try {
                okhttp3.Request request = makeRequest(method, url, headers, body);

                Call call = client.newCall(request);
                response = call.execute();
                ResponseBody responseBody = response.body();
                String result = "";
                if (responseBody != null) {
                    result = responseBody.string();
                }
                return OkHttpResponse.httpResponse(response.code(), parseHeaders(response.headers()), result);
            } catch (IOException ex) {
                return OkHttpResponse.ioError(ex.toString());
            } catch (Exception ex) {
                return OkHttpResponse.unknownError(ex.toString());
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        } catch (Exception e) {
            return OkHttpResponse.unknownError(e.toString());
        }
    }

    @Override
    public Response sendStreamingRequest(Request request) throws IOException, AppException {
        OkHttpClient client = getStreamClient();

        okhttp3.Request okRequest = makeRequest(request.getMethod(), request.getUrl(), request.getHeaders(), request.getBody());

        Call call = client.newCall(okRequest);
        okhttp3.Response response = call.execute();

        if((response.code() >= 300) || ((response.code() < 200) && (response.code() != 0))) {
            throw new AppException(ErrorCode.fromNativeError(ErrorCode.Type.HTTP, response.code()), "http error code considered fatal");
        }

        return OkHttpResponse.httpResponse(response.code(), parseHeaders(response.headers()), response.body().source());
    }

    // Lazily creates the client if not already created
    // TODO: timeOuts are not expected to change between requests. So for now just use the timeout first send.
    private synchronized OkHttpClient getClient(long timeoutMs) {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .followRedirects(true)
                    .addInterceptor(new LoggingInterceptor(httpLogObfuscator))
                    // using custom Connection Pool to evict idle connection after 5 seconds rather than 5 minutes (which is the default)
                    // keeping idle connection on the pool will prevent the ROS to be stopped, since the HttpUtils#stopSyncServer query
                    // will not return before the tests timeout (ex 10 seconds for AuthTests)
                    .connectionPool(new ConnectionPool(5, 5, TimeUnit.SECONDS))
                    .build();
        }

        return client;
    }

    private synchronized OkHttpClient getStreamClient() {
        if (streamClient == null) {
            streamClient = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .followRedirects(true)
                    .addInterceptor(new LoggingInterceptor(httpLogObfuscator))
                    .build();
        }

        return streamClient;
    }

    // Parse Headers output from OKHttp to the format expected by ObjectStore
    private Map<String, String> parseHeaders(Headers headers) {
        HashMap<String, String> osHeaders = new HashMap<>(headers.size() / 2);
        for (String key : headers.names()) {
            osHeaders.put(key, headers.get(key));
        }
        return osHeaders;
    }

    public static class OkHttpResponse extends OsJavaNetworkTransport.Response {
        private BufferedSource bufferedSource;

        public static Response unknownError(String stacktrace) {
            return new OkHttpResponse(0, ERROR_UNKNOWN, new HashMap<>(), stacktrace);
        }

        public static Response ioError(String stackTrace) {
            return new OkHttpResponse(0, ERROR_IO, new HashMap<>(), stackTrace);
        }

        public static Response interruptedError(String stackTrace) {
            return new OkHttpResponse(0, ERROR_INTERRUPTED, new HashMap<>(), stackTrace);
        }

        public static Response httpResponse(int statusCode, Map<String, String> responseHeaders, String body) {
            return new OkHttpResponse(statusCode, 0, responseHeaders, body);
        }

        private OkHttpResponse(int httpResponseCode, int customResponseCode, Map<String, String> headers, String body) {
            super(httpResponseCode, customResponseCode, headers, body);
        }

        private OkHttpResponse(int httpResponseCode, Map<String, String> headers, BufferedSource bufferedSource) {
            super(httpResponseCode, 0, headers, "");

            this.bufferedSource = bufferedSource;
        }

        public static Response httpResponse(int httpResponseCode, Map<String, String> headers, BufferedSource originalResponse) {
            return new OkHttpResponse(httpResponseCode, headers, originalResponse);
        }

        @Override
        public String readBodyLine() throws IOException {
            return bufferedSource.readUtf8LineStrict();
        }

        @Override
        public void close() throws IOException {
            bufferedSource.close();
        }
        
        @Override
        public boolean isOpen() {
            return bufferedSource.isOpen();
        }
    }

}

