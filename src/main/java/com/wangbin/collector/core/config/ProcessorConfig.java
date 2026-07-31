package com.wangbin.collector.core.config;

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
        config.put("name", "DataValidator");
        config.put("type", "VALIDATOR");
        config.put("description", "数据验证器");
        config.put("priority", 10);
        config.put("enabled", true);
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
        config.put("name", "DataConverter");
        config.put("type", "CONVERTER");
        config.put("description", "数据转换器");
        config.put("priority", 20);
        config.put("enabled", true);
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
        config.put("name", "UnitConverter");
        config.put("type", "CONVERTER");
        config.put("description", "单位转换器");
        config.put("priority", 30);
        config.put("enabled", true);
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
        config.put("name", "DeadbandFilter");
        config.put("type", "FILTER");
        config.put("description", "死区过滤器");
        config.put("priority", 40);
        config.put("enabled", true);
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
        config.put("name", "QualityFilter");
        config.put("type", "FILTER");
        config.put("description", "质量过滤器");
        config.put("priority", 50);
        config.put("enabled", true);
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
