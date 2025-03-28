package com.xiaozhi.websocket.llm.api;

/**
 * 流式响应监听器接口
 * 用于处理LLM的流式响应
 */
public interface StreamResponseListener {
    
    /**
     * 当流式响应开始时调用
     */
    void onStart();
    
    /**
     * 当接收到新的token时调用
     * 
     * @param token 接收到的token
     */
    void onToken(String token);
    
    /**
     * 当流式响应完成时调用
     * 
     * @param fullResponse 完整的响应内容
     */
    void onComplete(String fullResponse);
    
    /**
     * 当发生错误时调用
     * 
     * @param e 发生的异常
     */
    void onError(Throwable e);
}