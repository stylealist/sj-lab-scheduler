package com.example.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import java.util.Locale;

@SpringBootApplication
@EnableScheduling
@EnableDiscoveryClient
public class SchedulerRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerRestApplication.class, args);
        
        /* 생성되는 빈을 모두 확인 할 수 있는 코드
        ApplicationContext applicationContext = SpringApplication.run(MapServiceRestApplication.class, args);

        String[] allBeanNames = applicationContext.getBeanDefinitionNames();
        for(String beanName : allBeanNames){
            System.out.println(beanName);
        }*/
    }
    @Bean
    public LocaleResolver localeResolver() {
        SessionLocaleResolver localeResolver = new SessionLocaleResolver();
        localeResolver.setDefaultLocale(Locale.US);
        return localeResolver;
    }
}
