package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.SysConfigMapper;
import com.example.agentdeepseek.model.entity.SysConfig;
import com.example.agentdeepseek.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ConfigServiceImpl implements ConfigService {

    @Autowired
    private SysConfigMapper sysConfigMapper;

    @Override
    public String getValue(String key) {
        SysConfig config = sysConfigMapper.selectByKey(key);
        return config != null ? config.getConfigValue() : null;
    }

    @Override
    public void setValue(String key, String value) {
        sysConfigMapper.upsert(key, value);
        log.info("更新配置: key={}", key);
    }
}
