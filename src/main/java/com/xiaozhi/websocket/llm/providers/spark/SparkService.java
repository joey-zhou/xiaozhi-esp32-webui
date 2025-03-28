package com.xiaozhi.websocket.llm.providers.spark;

import com.xiaozhi.websocket.llm.api.AbstractLlmService;
import com.xiaozhi.websocket.llm.api.StreamResponseListener;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.*;

/**
 * 讯飞星火 LLM服务实现
 */
public class SparkService extends AbstractLlmService {

    /**
     * 构造函数
     * 
     * @param endpoint API端点 (host url)
     * @param apiKey   API密钥 (apiKey)
     * @param model    模型名称
     */
    public SparkService(String endpoint, String appId, String apiKey, String apiSecret, String model) {
        super(endpoint, appId, apiKey, apiSecret, model);
    }

    @Override
    protected String chat(List<Map<String, String>> messages) throws IOException {
        try {
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();

            Map<String, Object> chat = new HashMap<>();
            chat.put("model", model);

            Map<String, Object> requestPayload = new HashMap<>();

            // 转换消息格式为讯飞要求的格式
            List<Map<String, Object>> sparkMessages = new ArrayList<>();
            for (Map<String, String> message : messages) {
                Map<String, Object> sparkMessage = new HashMap<>();
                sparkMessage.put("role", message.get("role"));
                sparkMessage.put("content", message.get("content"));
                sparkMessages.add(sparkMessage);
            }

            requestPayload.put("message", sparkMessages);

            requestBody.put("payload", requestPayload);

            // 转换为JSON
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 构建请求 - 使用简单的Bearer token认证
            Request request = new Request.Builder()
                    .url(endpoint + "/chat/completions")
                    .post(RequestBody.create(jsonBody, JSON))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer " + apiSecret) // 使用apiSecret作为Bearer token
                    .build();

            // 发送请求
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("请求失败: " + response);
                }

                String responseBody = response.body().string();
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

                // 解析星火API响应
                Map<String, Object> responsePayload = (Map<String, Object>) responseMap.get("payload");
                if (responsePayload != null) {
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) responsePayload.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> choice = choices.get(0);
                        if (choice.containsKey("content")) {
                            return (String) choice.get("content");
                        }
                    }
                }

                throw new IOException("无法解析星火响应");
            }
        } catch (Exception e) {
            throw new IOException("星火API请求失败: " + e.getMessage(), e);
        }
    }

    @Override
    protected void chatStream(List<Map<String, String>> messages, StreamResponseListener streamListener)
            throws IOException {
        try {
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("stream", true);
            requestBody.put("messages", messages);

            // 转换为JSON
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            // 构建请求 - 使用简单的Bearer token认证
            Request request = new Request.Builder()
                    .url(endpoint + "/chat/completions")
                    .post(RequestBody.create(jsonBody, JSON))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer " + apiSecret) // 使用apiSecret作为Bearer token
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
                            if (line.startsWith("data:")) {
                                String jsonData = line.substring(6);

                                try {
                                    Map<String, Object> data = objectMapper.readValue(jsonData, Map.class);

                                    // 解析内容
                                    List<Map<String, Object>> choices = (List<Map<String, Object>>) data.get("choices");
                                    if (choices != null && !choices.isEmpty()) {
                                        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");

                                        if (delta != null && delta.containsKey("content")) {
                                            String content = (String) delta.get("content");
                                            if (content != null && !content.isEmpty()) {
                                                streamListener.onToken(content);
                                                fullResponse.append(content);
                                            }
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
        } catch (Exception e) {
            throw new IOException("星火API流式请求失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getProviderName() {
        return "spark";
    }
}