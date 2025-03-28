package com.xiaozhi.websocket.llm;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysConfigService;
import com.xiaozhi.websocket.llm.api.LlmService;
import com.xiaozhi.websocket.llm.api.StreamResponseListener;
import com.xiaozhi.websocket.llm.factory.LlmServiceFactory;
import com.xiaozhi.websocket.llm.memory.ChatMemory;
import com.xiaozhi.websocket.llm.memory.ModelContext;

import org.apache.logging.log4j.util.TriConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * LLM管理器
 * 负责管理和协调LLM相关功能
 */
@Service
public class LlmManager {
    private static final Logger logger = LoggerFactory.getLogger(LlmManager.class);

    // 标点符号模式（中英文标点）
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[，。！？；,.!?;]");

    // 最小句子长度（字符数）
    private static final int MIN_SENTENCE_LENGTH = 5;

    @Autowired
    private SysConfigService configService;

    @Autowired
    private ChatMemory chatMemory;

    // 设备LLM服务缓存，每个设备只保留一个服务
    private Map<String, LlmService> deviceLlmServices = new ConcurrentHashMap<>();
    // 设备当前使用的configId缓存
    private Map<String, Integer> deviceConfigIds = new ConcurrentHashMap<>();

    /**
     * 处理用户查询（同步方式）
     * 
     * @param device  设备信息
     * @param message 用户消息
     * @return 模型回复
     */
    public String chat(SysDevice device, String message) {
        try {
            String deviceId = device.getDeviceId();
            Integer configId = device.getConfigId();

            // 获取LLM服务
            LlmService llmService = getLlmService(deviceId, configId);

            // 创建模型上下文
            ModelContext modelContext = new ModelContext(
                    deviceId,
                    device.getSessionId(),
                    device.getRoleId(),
                    chatMemory);

            // 调用LLM
            return llmService.chat(message, modelContext);

        } catch (Exception e) {
            logger.error("处理查询时出错: {}", e.getMessage(), e);
            return "抱歉，我在处理您的请求时遇到了问题。请稍后再试。";
        }
    }

    /**
     * 处理用户查询（流式方式）
     * 
     * @param device         设备信息
     * @param message        用户消息
     * @param streamListener 流式响应监听器
     */
    public void chatStream(SysDevice device, String message, StreamResponseListener streamListener) {
        try {
            String deviceId = device.getDeviceId();
            Integer configId = device.getConfigId();

            // 获取LLM服务
            LlmService llmService = getLlmService(deviceId, configId);

            // 创建模型上下文
            ModelContext modelContext = new ModelContext(
                    deviceId,
                    device.getSessionId(),
                    device.getRoleId(),
                    chatMemory);

            // 调用LLM流式接口
            llmService.chatStream(message, modelContext, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
            streamListener.onError(e);
        }
    }

    /**
     * 处理用户查询（流式方式，使用lambda表达式）
     * 
     * @param device       设备信息
     * @param message      用户消息
     * @param tokenHandler token处理函数，接收每个生成的token
     */
    public void chatStream(SysDevice device, String message, Consumer<String> tokenHandler) {
        try {
            // 创建流式响应监听器
            StreamResponseListener streamListener = new StreamResponseListener() {
                private final StringBuilder fullResponse = new StringBuilder();

                @Override
                public void onStart() {
                    logger.info("开始流式响应");
                }

                @Override
                public void onToken(String token) {
                    // 将token添加到完整响应
                    fullResponse.append(token);

                    // 调用处理函数
                    tokenHandler.accept(token);
                }

                @Override
                public void onComplete(String completeResponse) {
                    logger.info("流式响应完成，完整响应: {}", completeResponse);
                }

                @Override
                public void onError(Throwable e) {
                    logger.error("流式响应出错: {}", e.getMessage(), e);
                }
            };

            // 调用现有的流式方法
            chatStream(device, message, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理用户查询（流式方式，使用句子切分，带有开始和结束标志）
     * 
     * @param device          设备信息
     * @param message         用户消息
     * @param sentenceHandler 句子处理函数，接收句子内容、是否是开始句子、是否是结束句子
     */
    public void chatStreamBySentence(SysDevice device, String message,
            TriConsumer<String, Boolean, Boolean> sentenceHandler) {
        try {
            final StringBuilder currentSentence = new StringBuilder();
            final AtomicInteger sentenceCount = new AtomicInteger(0);
            final StringBuilder fullResponse = new StringBuilder();

            // 用于跟踪是否有待处理的句子
            final AtomicReference<String> pendingSentence = new AtomicReference<>(null);

            // 创建流式响应监听器
            StreamResponseListener streamListener = new StreamResponseListener() {
                @Override
                public void onStart() {
                }

                @Override
                public void onToken(String token) {
                    // 将token添加到完整响应
                    fullResponse.append(token);

                    // 检查是否有待处理的句子
                    String pending = pendingSentence.get();
                    if (pending != null) {
                        // 有待处理的句子，发送它
                        boolean isStart = sentenceCount.get() == 0;
                        boolean isEnd = false; // 中间句子不是结束
                        sentenceHandler.accept(pending, isStart, isEnd);
                        sentenceCount.incrementAndGet();
                        pendingSentence.set(null);
                    }

                    // 将token添加到当前句子
                    currentSentence.append(token);

                    // 检查是否包含标点符号
                    if (PUNCTUATION_PATTERN.matcher(token).matches()) {
                        // 检查当前句子是否达到最小长度
                        if (currentSentence.length() >= MIN_SENTENCE_LENGTH) {
                            String sentence = currentSentence.toString().trim();

                            // 不立即发送，而是将其设置为待处理
                            pendingSentence.set(sentence);

                            // 重置当前句子
                            currentSentence.setLength(0);
                        }
                    }
                }

                @Override
                public void onComplete(String completeResponse) {
                    // 处理待发送的句子（如果有）
                    String pending = pendingSentence.getAndSet(null);
                    if (pending != null) {
                        // 如果这是第一个句子，isStart=true，如果是最后一个，isEnd=true
                        boolean isStart = sentenceCount.get() == 0;
                        boolean isEnd = true; // 最后一个待处理句子是结束
                        sentenceHandler.accept(pending, isStart, isEnd);
                        sentenceCount.incrementAndGet();
                    }

                    // 处理可能剩余的最后一个句子
                    if (currentSentence.length() > 0) {
                        String sentence = currentSentence.toString().trim();

                        // 确定句子状态
                        boolean isStart = sentenceCount.get() == 0;
                        boolean isEnd = true; // 最后一个句子是结束

                        sentenceHandler.accept(sentence, isStart, isEnd);
                        sentenceCount.incrementAndGet();
                    }
                    
                    // 如果没有任何句子被处理，发送一个空的结束标记
                    if (sentenceCount.get() == 0) {
                        sentenceHandler.accept("", true, true);
                    }
                }

                @Override
                public void onError(Throwable e) {
                    logger.error("流式响应出错: {}", e.getMessage(), e);
                    // 发送错误信号
                    sentenceHandler.accept("抱歉，我在处理您的请求时遇到了问题。", true, true);
                }
            };

            // 调用现有的流式方法
            chatStream(device, message, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
            // 发送错误信号
            sentenceHandler.accept("抱歉，我在处理您的请求时遇到了问题。", true, true);
        }
    }

    /**
     * 获取或创建LLM服务
     * 
     * @param deviceId 设备ID
     * @param configId 配置ID
     * @return LLM服务
     */
    public LlmService getLlmService(String deviceId, Integer configId) {
        // 检查设备是否已有服务且配置ID不同
        Integer currentConfigId = deviceConfigIds.get(deviceId);
        if (currentConfigId != null && !currentConfigId.equals(configId)) {
            // 配置ID变更，移除旧服务
            deviceLlmServices.remove(deviceId);
        }

        // 更新设备当前使用的configId
        deviceConfigIds.put(deviceId, configId);

        // 获取或创建服务
        return deviceLlmServices.computeIfAbsent(deviceId, k -> createLlmService(configId));
    }

    /**
     * 创建LLM服务
     * 
     * @param configId 配置ID
     * @return LLM服务
     */
    private LlmService createLlmService(Integer configId) {
        // 根据配置ID查询配置
        SysConfig config = configService.selectConfigById(configId);
        if (config == null) {
            throw new RuntimeException("找不到配置ID: " + configId);
        }

        String provider = config.getProvider().toLowerCase();
        String model = config.getConfigName();
        String endpoint = config.getApiUrl();
        String apiKey = config.getApiKey();
        String appId = config.getAppId();
        String apiSecret = config.getApiSecret();

        return LlmServiceFactory.createLlmService(provider, endpoint, appId, apiKey, apiSecret, model);
    }

    /**
     * 清除设备缓存
     * 
     * @param deviceId 设备ID
     */
    public void clearDeviceCache(String deviceId) {
        deviceLlmServices.remove(deviceId);
        deviceConfigIds.remove(deviceId);
        chatMemory.clearMessages(deviceId);
    }

    /**
     * 三参数消费者接口
     */
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }
}