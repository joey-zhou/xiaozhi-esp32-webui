package com.xiaozhi.websocket.llm.api;

import com.xiaozhi.websocket.llm.memory.ModelContext;
import java.io.IOException;

/**
 * LLM服务接口
 * 定义与大语言模型交互的基本方法
 */
public interface LlmService {
    
    /**
     * 发送聊天请求（同步方式）
     * 
     * @param userMessage 用户消息
     * @param modelContext 模型上下文
     * @return 模型回复
     * @throws IOException 如果请求失败
     */
    String chat(String userMessage, ModelContext modelContext) throws IOException;
    
    /**
     * 发送聊天请求（流式方式）
     * 
     * @param userMessage 用户消息
     * @param modelContext 模型上下文
     * @param streamListener 流式响应监听器
     * @throws IOException 如果请求失败
     */
    void chatStream(String userMessage, ModelContext modelContext, StreamResponseListener streamListener) throws IOException;
    
    /**
     * 获取模型名称
     * 
     * @return 模型名称
     */
    String getModelName();
    
    /**
     * 获取提供商名称
     * 
     * @return 提供商名称
     */
    String getProviderName();
}