package com.auctionx.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${file.upload.dir}")
    private String uploadDir;

    public String saveFile(MultipartFile file, String subfolder) throws IOException {
        String folder = uploadDir + subfolder + "/";
        Path dirPath = Paths.get(folder);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
        String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path filePath = dirPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        return folder + filename;
    }

    public void deleteFile(String filePath) throws IOException {
        if (filePath != null) {
            Files.deleteIfExists(Paths.get(filePath));
        }
    }
}