package com.example.babymonitor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class BabyMonitorController {

    private final AtomicBoolean captureActive = new AtomicBoolean(false);
    private final Path uploadDir = Paths.get("uploads");

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(uploadDir);
        System.out.println("Serveur BabyMonitor demarre. Uploads dans : " + uploadDir.toAbsolutePath());
    }

    @PostMapping("/command")
    public ResponseEntity<?> setCommand(@RequestParam boolean active) {
        captureActive.set(active);
        String status = active ? "CAPTURE ACTIVE" : "EN ATTENTE";
        System.out.println("[COMMANDE] " + status);
        return ResponseEntity.ok(Map.of("status", "ok", "capture", active));
    }

    @GetMapping("/command")
    public ResponseEntity<?> getCommand() {
        return ResponseEntity.ok(Map.of("capture", captureActive.get()));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            String filename = "screen_" + System.currentTimeMillis() + ".jpg";
            Path target = uploadDir.resolve(filename);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Image recue : " + filename + " (" + file.getSize() + " octets)");
            return ResponseEntity.ok(Map.of("status", "received", "file", filename));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/images")
    public ResponseEntity<?> listImages() {
        try {
            List<String> files = Files.list(uploadDir)
                    .filter(p -> p.toString().endsWith(".jpg"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
            return ResponseEntity.ok(files);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/image/{filename}")
    public ResponseEntity<org.springframework.core.io.Resource> getImage(@PathVariable String filename) {
        try {
            Path file = uploadDir.resolve(filename).normalize();
            if (!file.startsWith(uploadDir)) {
                return ResponseEntity.badRequest().build();
            }
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(file.toUri());
            return ResponseEntity.ok()
                    .header("Content-Type", "image/jpeg")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}