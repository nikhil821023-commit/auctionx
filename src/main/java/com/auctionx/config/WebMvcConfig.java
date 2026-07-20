package com.auctionx.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.upload.dir:uploads/}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ✅ Serve /uploads/** from the local filesystem uploads/ folder
        String absolutePath = "file:" + System.getProperty("user.dir") + "/" + uploadDir;

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(absolutePath);
    }
}