package com.xiaozhi.service.impl;

import java.util.List;

import com.github.pagehelper.PageHelper;
import com.xiaozhi.dao.MessageMapper;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.entity.SysMessage;
import com.xiaozhi.service.SysMessageService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 报名操作
 *
 * @author xiaozhi
 *
 */

@Service
public class SysMessageServiceImpl implements SysMessageService {

    @Resource
    private MessageMapper messageMapper;

    /**
     * 新增聊天记录
     *
     * @param device
     * @return
     */
    @Override
    @Transactional
    public int add(SysDevice device) {
        return messageMapper.add(device);
    }

    /**
     * 查询聊天记录
     *
     * @param message
     * @return
     */
    @Override
    public List<SysMessage> query(SysMessage message) {
        if (message.getLimit() != null && message.getLimit() > 0) {
            PageHelper.startPage(message.getStart(), message.getLimit());
        }
        return messageMapper.query(message);
    }

}