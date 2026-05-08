package com.example.agentdeepseek.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger配置类
 * 配置API文档信息
 */
@Configuration
public class OpenApiConfig {

    /**
     * 创建OpenAPI配置Bean
     * @return OpenAPI配置实例
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agent DeepSeek API")
                        .version("1.0.0")
                        .description("Spring Boot 3 + Swagger 3 示例项目")
                        .contact(new Contact()
                                .name("开发者")
                                .email("developer@example.com")
                                .url("https://example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }

    /**
     * 全局header参数自定义配置
     * 在所有请求上添加token参数
     */
    @Bean
    public OpenApiCustomizer globalHeaderOpenApiCustomizer() {
        return openApi -> {
            // 创建Authorization参数
            Parameter tokenParameter = new Parameter()
                    .in("header")
                    .name("Authorization")
                    .description("访问令牌")
                    .required(false)
                    .schema(new StringSchema());

            // 为所有路径的所有操作添加token参数
            openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation ->
                    operation.addParametersItem(tokenParameter)
                )
            );
        };
    }
}