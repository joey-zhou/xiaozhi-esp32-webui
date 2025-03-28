package com.xiaozhi;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import org.junit.runner.RunWith;

// Change to NONE to avoid starting a web server
@RunWith(SpringRunner.class)
@SpringBootTest
@WebAppConfiguration
public class ApplicationTest {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationTest.class);

    @Resource
    public void main(String[] args) throws Exception {
    }

}