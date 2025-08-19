package com.example.scheduler.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;

@OpenAPIDefinition(
        info = @Info(title = "My Restful Service API 명세서",
                     description = "SpringBoot로 개발하는 RESTful API 명세서 입니다.",
                     version = "v1.0.0")
)
@Configuration
@RequiredArgsConstructor
public class NewSwaggerConfig {
    @Value("${spring.profiles.active:}")
    private String profile;

    @Bean
    public GroupedOpenApi customTestOpenApi(){
        String[] paths = {"/users/**","/admin/**"};

        return GroupedOpenApi.builder()
                .group("일반사용자와 관리자를 위한 User 도메인에 대한 API")
                .pathsToMatch(paths)
                .build();
    }
    @Bean
    public OpenAPI customOpenAPI() {
        String BaseUrl = "";
        if(profile.equals("local")) {
            BaseUrl = "/map";
        }else if(profile.equals("prod")) {
            BaseUrl = "/api/map";
        }
        return new OpenAPI()
                .servers(List.of(new Server().url(BaseUrl)));
    }
    @Bean
    ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }
}
