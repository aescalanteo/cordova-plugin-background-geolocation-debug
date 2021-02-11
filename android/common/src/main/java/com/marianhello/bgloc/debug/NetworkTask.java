package com.marianhello.bgloc.debug;

import com.marianhello.logging.LoggerManager;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NetworkTask extends BaseTask {
    private org.slf4j.Logger logger;

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    OkHttpClient client = new OkHttpClient();
    Long timestamp = System.currentTimeMillis();
    private final DebugMessage dm;//listener in fragment that shows and hides ProgressBar
    public NetworkTask(DebugMessage dm) {
        this.dm = dm;
    }

    @Override
    public Object call() throws Exception {
        logger = LoggerManager.getLogger(NetworkTask.class);
        Object result = null;
        result = postMessage(dm.getMessage());//some network request for example
        return result;
    }

    public boolean postMessage(String message) {
        logger.debug("[PLUGIN_LOG] {} | {}", message, timestamp);
        RequestBody body = RequestBody.create(JSON, "{\"mensaje\":\"" + message + " | " + timestamp +"\"}");
        Request request = new Request.Builder()
                .url("https://global.akar.pe/taxi/plugin-log")
                .post(body)
                .build();
        Call call = client.newCall(request);

        Response response = null;
        try {
            response = call.execute();
            logger.debug("[PLUGIN_LOG] Response {}", "a");
            return true;
        } catch (IOException e) {
            logger.debug("[PLUGIN_LOG] Ocurrió un error: {}", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void setUiForLoading() {

    }

    @Override
    public void setDataAfterLoading(Object result) {
    }
}