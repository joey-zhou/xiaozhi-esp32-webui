package com.xiaozhi.websocket.llm.memory;

import com.xiaozhi.entity.SysMessage;
import java.util.List;

/**
 * 聊天记忆接口
 * 负责管理聊天历史记录
 */
public interface ChatMemory {
    
    /**
     * 添加消息到历史记录
     * 
     * @param deviceId 设备ID
     * @param sessionId 会话ID
     * @param sender 发送者
     * @param content 内容
     * @param roleId 角色ID
     */
    void addMessage(String deviceId, String sessionId, String sender, String content, Integer roleId);
    
    /**
     * 获取历史消息
     * 
     * @param deviceId 设备ID
     * @param limit 消息数量限制
     * @return 历史消息列表
     */
    List<SysMessage> getMessages(String deviceId, Integer limit);
    
    /**
     * 清除设备的历史记录
     * 
     * @param deviceId 设备ID
     */
    void clearMessages(String deviceId);
    
    /**
     * 获取系统消息
     * 
     * @param deviceId 设备ID
     * @param roleId 角色ID
     * @return 系统消息
     */
    String getSystemMessage(String deviceId, Integer roleId);
    
    /**
     * 设置系统消息
     * 
     * @param deviceId 设备ID
     * @param roleId 角色ID
     * @param systemMessage 系统消息
     */
    void setSystemMessage(String deviceId, Integer roleId, String systemMessage);
}