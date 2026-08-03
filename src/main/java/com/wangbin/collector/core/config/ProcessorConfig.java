package com.wangbin.collector.core.config;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.processor.converter.DataConverter;
import com.wangbin.collector.core.processor.converter.UnitConverter;
import com.wangbin.collector.core.processor.filter.DeadbandFilter;
import com.wangbin.collector.core.processor.filter.QualityFilter;
import com.wangbin.collector.core.processor.validator.DataValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 处理器配置类
 */
@Slf4j
@Configuration
public class ProcessorConfig {

    /**
     * 数据验证器
     */
    @Bean
    public DataValidator dataValidator() {
        DataValidator validator = new DataValidator();
        Map<String, Object> config = new HashMap<>();
        config.put(CommonMapKeys.NAME, "DataValidator");
        config.put(CommonMapKeys.TYPE, "VALIDATOR");
        config.put(CommonMapKeys.DESCRIPTION, "数据验证器");
        config.put("priority", 10);
        config.put(CommonMapKeys.ENABLED, true);
        validator.init(config);
        return validator;
    }

    /**
     * 数据转换器
     */
    @Bean
    public DataConverter dataConverter() {
        DataConverter converter = new DataConverter();
        Map<String, Object> config = new HashMap<>();
        config.put(CommonMapKeys.NAME, "DataConverter");
        config.put(CommonMapKeys.TYPE, "CONVERTER");
        config.put(CommonMapKeys.DESCRIPTION, "数据转换器");
        config.put("priority", 20);
        config.put(CommonMapKeys.ENABLED, true);
        converter.init(config);
        return converter;
    }

    /**
     * 单位转换器
     */
    @Bean
    public UnitConverter unitConverter() {
        UnitConverter converter = new UnitConverter();
        Map<String, Object> config = new HashMap<>();
        config.put(CommonMapKeys.NAME, "UnitConverter");
        config.put(CommonMapKeys.TYPE, "CONVERTER");
        config.put(CommonMapKeys.DESCRIPTION, "单位转换器");
        config.put("priority", 30);
        config.put(CommonMapKeys.ENABLED, true);
        converter.init(config);
        return converter;
    }

    /**
     * 死区过滤器
     */
    @Bean
    public DeadbandFilter deadbandFilter() {
        DeadbandFilter filter = new DeadbandFilter();
        Map<String, Object> config = new HashMap<>();
        config.put(CommonMapKeys.NAME, "DeadbandFilter");
        config.put(CommonMapKeys.TYPE, "FILTER");
        config.put(CommonMapKeys.DESCRIPTION, "死区过滤器");
        config.put("priority", 40);
        config.put(CommonMapKeys.ENABLED, true);
        config.put("defaultDeadband", 0.1);
        filter.init(config);
        return filter;
    }

    /**
     * 质量过滤器
     */
    @Bean
    public QualityFilter qualityFilter() {
        QualityFilter filter = new QualityFilter();
        Map<String, Object> config = new HashMap<>();
        config.put(CommonMapKeys.NAME, "QualityFilter");
        config.put(CommonMapKeys.TYPE, "FILTER");
        config.put(CommonMapKeys.DESCRIPTION, "质量过滤器");
        config.put("priority", 50);
        config.put(CommonMapKeys.ENABLED, true);
        config.put("minQuality", 60);
        filter.init(config);
        return filter;
    }

    /**
     * 处理组件生命周期。
     */
    @PostConstruct
    public void init() {
        log.info("处理器配置初始化完成");
    }
}
