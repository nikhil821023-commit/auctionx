package com.auctionx.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class FileStorageConfig implements WebMvcConfigurer {

    @Value("${file.upload.dir:uploads/}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ✅ Maps /uploads/** → local uploads/ folder
        // Frontend can now fetch /uploads/players/abc.jpg directly
        String absolutePath = System.getProperty("user.dir") + "/" + uploadDir;

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + absolutePath)
                .setCachePeriod(3600);

        registry.addResourceHandler("/uploads/players/**")
                .addResourceLocations("file:" + absolutePath + "players/")
                .setCachePeriod(3600);

        registry.addResourceHandler("/uploads/teams/**")
                .addResourceLocations("file:" + absolutePath + "teams/")
                .setCachePeriod(3600);

        registry.addResourceHandler("/uploads/tournaments/**")
                .addResourceLocations("file:" + absolutePath + "tournaments/")
                .setCachePeriod(3600);
    }
}