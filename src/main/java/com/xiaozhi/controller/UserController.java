package com.xiaozhi.controller;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import com.xiaozhi.common.exception.UserPasswordNotMatchException;
import com.xiaozhi.common.exception.UsernameNotFoundException;
import com.xiaozhi.common.web.AjaxResult;
import com.xiaozhi.entity.SysUser;
import com.xiaozhi.security.AuthenticationService;
import com.xiaozhi.service.SysUserService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户信息
 * 
 * @author: Joey
 * 
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    @Resource
    private SysUserService userService;

    @Resource
    private AuthenticationService authenticationService;

    /**
     * @param username
     * @param password
     * @return
     * @throws UsernameNotFoundException
     * @throws UserPasswordNotMatchException
     */
    @PostMapping("/login")
    public AjaxResult login(String username, String password, HttpServletRequest request) {
        AjaxResult result;
        HttpSession session = request.getSession();
        try {
            userService.login(username, password);
            SysUser user = userService.query(username);
            session.setAttribute(SysUserService.USER_SESSIONKEY, user);
            result = AjaxResult.success(user);
        } catch (UsernameNotFoundException e) {
            log.info("用户:{} 不存在.", username);
            result = AjaxResult.error("用户不存在");
        } catch (UserPasswordNotMatchException e) {
            log.info("{} 密码错误.", username);
            result = AjaxResult.error("密码错误");
        } catch (Exception e) {
            log.info(e.getMessage(), e);
            result = AjaxResult.error("操作失败");
        }
        return result;
    }

    /**
     * 用户信息查询
     * 
     * @param username
     * @return
     */
    @GetMapping("/query")
    public AjaxResult query(String username) {
        try {
            AjaxResult result;
            SysUser user = userService.query(username);
            result = AjaxResult.success();
            result.put("data", user);
            return result;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return AjaxResult.error();
        }
    }

    /**
     * 用户信息修改
     *
     * @param user
     * @return
     */
    @PostMapping("/update")
    public AjaxResult update(SysUser user, HttpServletRequest request) {
        try {
            SysUser userQuery = userService.query(user.getUsername());
            if (ObjectUtils.isEmpty(userQuery)) {
                return AjaxResult.error("无此用户，操作失败");
            }
            if (!StringUtils.hasText(user.getPassword())) {
                String newPassword = authenticationService.encryptPassword(user.getPassword());
                user.setPassword(newPassword);
            }
            // if (StringUtils.hasText(userQuery.getAvatar())) {
            // user.setAvatar(ImageUtils.GenerateImg(userQuery.getName()));
            // }
            if (0 < userService.update(user)) {
                return AjaxResult.success(user);
            }
            return AjaxResult.error();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return AjaxResult.error();
        }

    }
}