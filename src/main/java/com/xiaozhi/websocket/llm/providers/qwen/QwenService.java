package com.xiaozhi.websocket.llm.providers.qwen;

import com.xiaozhi.websocket.llm.api.AbstractLlmService;
import com.xiaozhi.websocket.llm.api.StreamResponseListener;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云通义千问 LLM服务实现
 */
public class QwenService extends AbstractLlmService {

    /**
     * 构造函数
     * 
     * @param endpoint API端点
     * @param apiKey   API密钥
     * @param model    模型名称
     */
    public QwenService(String endpoint, String appId, String apiKey, String apiSecret, String model) {
        super(endpoint, appId, apiKey, apiSecret, model);
    }

    @Override
    protected String chat(List<Map<String, String>> messages) throws IOException {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);

        // 转换为JSON
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // 构建请求
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(jsonBody, JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        // 发送请求
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: " + response);
            }

            String responseBody = response.body().string();
            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

            // 通义千问API响应格式解析
            Map<String, Object> output = (Map<String, Object>) responseMap.get("output");
            if (output != null && output.containsKey("text")) {
                return (String) output.get("text");
            }

            throw new IOException("无法解析通义千问响应");
        }
    }

    @Override
    protected void chatStream(List<Map<String, String>> messages, StreamResponseListener streamListener)
            throws IOException {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("stream", true);

        // 转换为JSON
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // 构建请求
        Request request = new Request.Builder()
                .url(endpoint + "/chat/completions")
                .post(RequestBody.create(jsonBody, JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        // 通知开始
        streamListener.onStart();

        // 发送请求
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                logger.error("流式请求失败: {}", e.getMessage(), e);
                streamListener.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String errorMsg = "流式请求响应失败: " + response;
                    logger.error(errorMsg);
                    streamListener.onError(new IOException(errorMsg));
                    return;
                }

                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) {
                        String errorMsg = "响应体为空";
                        logger.error(errorMsg);
                        streamListener.onError(new IOException(errorMsg));
                        return;
                    }
                    BufferedSource source = responseBody.source();
                    StringBuilder fullResponse = new StringBuilder();

                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line == null) {
                            break;
                        }
                        if (line.isEmpty() || line.equals("data: [DONE]")) {
                            continue;
                        }
                        if (line.startsWith("data: ")) {
                            String jsonData = line.substring(6);
                            try {
                                Map<String, Object> data = objectMapper.readValue(jsonData, Map.class);
                                List<Map<String, Object>> choices = (List<Map<String, Object>>) data.get("choices");
                                
                                if (choices != null && !choices.isEmpty()) {
                                    Map<String, Object> choice = choices.get(0);
                                    Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                                    
                                    if (delta != null && delta.containsKey("content")) {
                                        String content = (String) delta.get("content");
                                        if (content != null && !content.isEmpty()) {
                                            streamListener.onToken(content);
                                            fullResponse.append(content);
                                        }
                                    }
                                    
                                    // 检查是否完成
                                    String finishReason = (String) choice.get("finish_reason");
                                    if ("stop".equals(finishReason)) {
                                        // 通知完成
                                        streamListener.onComplete(fullResponse.toString());
                                        return;
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("解析流式响应失败: {}", e.getMessage(), e);
                                streamListener.onError(e);
                            }
                        }
                    }

                    // 通知完成
                    streamListener.onComplete(fullResponse.toString());
                }
            }
        });
    }

    @Override
    public String getProviderName() {
        return "qwen";
    }
}