package com.auctionx.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${file.upload.dir:uploads/}")
    private String uploadDir;

    /**
     * Saves file and returns a clean URL path like:
     * /uploads/players/uuid_filename.jpg
     * Frontend can use this directly as <img src="..." />
     */
    public String saveFile(MultipartFile file, String subfolder) throws IOException {
        String folder = uploadDir + subfolder + "/";
        Path dirPath = Paths.get(folder);

        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }

        // ✅ Clean filename — remove spaces and special chars
        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "file";

        String filename  = UUID.randomUUID().toString().substring(0, 8) + "_" + originalName;
        Path   filePath  = dirPath.resolve(filename);

        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        // ✅ Return web-accessible URL path (not filesystem path)
        return "/uploads/" + subfolder + "/" + filename;
    }

    public void deleteFile(String urlPath) throws IOException {
        if (urlPath == null) return;
        // Convert URL path back to filesystem path
        String fsPath = urlPath.replaceFirst("^/uploads/", uploadDir);
        Files.deleteIfExists(Paths.get(fsPath));
    }
}