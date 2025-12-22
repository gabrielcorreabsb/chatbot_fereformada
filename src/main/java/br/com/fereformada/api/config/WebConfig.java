package br.com.fereformada.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Aplica CORS para TODAS as rotas, não só /api/**
        registry.addMapping("/**")
                // EM ALPHA: O "*" permite que localhost, IP e Domínio acessem sem bloquear.
                // Se usasse allowedOrigins("*") daria erro com credenciais. O Patterns resolve isso.
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD")
                .allowedHeaders("*")
                .allowCredentials(true) // Permite enviar Cookies/Auth Headers
                .maxAge(3600);
    }
}