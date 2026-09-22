package com.github.lisama569.localobjectstorejava.store.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用对象存储配置属性。
 */
@Configuration
@EnableConfigurationProperties(StoreProperties.class)
public class StoreConfig {
}
