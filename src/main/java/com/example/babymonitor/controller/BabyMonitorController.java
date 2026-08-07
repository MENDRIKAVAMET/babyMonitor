package com.example.babymonitor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class BabyMonitorController {

    private static final String DEFAULT_DEVICE = "unknown";

    // Etat "capture active" par appareil (cle = id de l'appareil)
    private final Map<String, Boolean> captureActive = new ConcurrentHashMap<>();
    private final Path uploadDir = Paths.get("uploads");

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(uploadDir);
        System.out.println("Serveur BabyMonitor demarre. Uploads dans : " + uploadDir.toAbsolutePath());
    }

    // ---- Securise le nom d'appareil pour eviter tout path traversal (../, etc.) ----
    private String sanitizeDevice(String device) {
        if (device == null || device.isBlank()) {
            return DEFAULT_DEVICE;
        }
        String clean = device.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return clean.isEmpty() ? DEFAULT_DEVICE : clean;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "";
        return Paths.get(filename).getFileName().toString();
    }

    private Path deviceDir(String device) throws IOException {
        Path dir = uploadDir.resolve(sanitizeDevice(device));
        Files.createDirectories(dir);
        return dir;
    }

    // ---- Commande (start/stop capture) par appareil ----

    @PostMapping("/command")
    public ResponseEntity<?> setCommand(@RequestParam boolean active,
                                         @RequestParam(defaultValue = DEFAULT_DEVICE) String device) {
        String dev = sanitizeDevice(device);
        captureActive.put(dev, active);
        System.out.println("[COMMANDE] " + dev + " -> " + (active ? "CAPTURE ACTIVE" : "EN ATTENTE"));
        return ResponseEntity.ok(Map.of("status", "ok", "capture", active, "device", dev));
    }

    @GetMapping("/command")
    public ResponseEntity<?> getCommand(@RequestParam(defaultValue = DEFAULT_DEVICE) String device) {
        String dev = sanitizeDevice(device);
        return ResponseEntity.ok(Map.of("capture", captureActive.getOrDefault(dev, false), "device", dev));
    }

    // ---- Upload d'une capture d'ecran ----

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file,
                                          @RequestParam(defaultValue = DEFAULT_DEVICE) String device) {
        try {
            String dev = sanitizeDevice(device);
            Path dir = deviceDir(dev);
            String filename = "screen_" + System.currentTimeMillis() + ".jpg";
            Path target = dir.resolve(filename);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Image recue [" + dev + "] : " + filename + " (" + file.getSize() + " octets)");
            return ResponseEntity.ok(Map.of("status", "received", "file", filename, "device", dev));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ---- Liste des appareils connus (ceux qui ont deja envoye au moins une image) ----

    @GetMapping("/devices")
    public ResponseEntity<?> listDevices() {
        try (Stream<Path> paths = Files.exists(uploadDir) ? Files.list(uploadDir) : Stream.empty()) {
            List<String> devices = paths
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
            return ResponseEntity.ok(devices);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ---- Liste des images d'un appareil precis ----

    @GetMapping("/images")
    public ResponseEntity<?> listImages(@RequestParam(defaultValue = DEFAULT_DEVICE) String device) {
        try {
            Path dir = deviceDir(device);
            List<String> files = Files.list(dir)
                    .filter(p -> p.toString().endsWith(".jpg"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
            return ResponseEntity.ok(files);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/image/{device}/{filename}")
    public ResponseEntity<org.springframework.core.io.Resource> getImage(@PathVariable String device,
                                                                          @PathVariable String filename) {
        try {
            Path dir = deviceDir(device);
            Path file = dir.resolve(sanitizeFilename(filename)).normalize();
            if (!file.startsWith(dir)) {
                return ResponseEntity.badRequest().build();
            }
            if (!Files.exists(file)) {
                return ResponseEntity.notFound().build();
            }
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(file.toUri());
            return ResponseEntity.ok()
                    .header("Content-Type", "image/jpeg")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ---- Suppression des photos ----
    // DELETE /api/images                -> supprime TOUTES les photos, tous appareils confondus
    // DELETE /api/images?device=xxx     -> supprime uniquement les photos de cet appareil

    @DeleteMapping("/images")
    public ResponseEntity<?> deleteImages(@RequestParam(required = false) String device) {
        try {
            int deleted;
            if (device == null || device.isBlank()) {
                deleted = 0;
                if (Files.exists(uploadDir)) {
                    try (Stream<Path> deviceDirs = Files.list(uploadDir)) {
                        for (Path d : deviceDirs.filter(Files::isDirectory).collect(Collectors.toList())) {
                            deleted += deleteJpgsIn(d);
                        }
                    }
                }
            } else {
                Path dir = deviceDir(device);
                deleted = deleteJpgsIn(dir);
            }
            return ResponseEntity.ok(Map.of("status", "ok", "deleted", deleted));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private int deleteJpgsIn(Path dir) throws IOException {
        int count = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jpg")).collect(Collectors.toList())) {
                Files.deleteIfExists(f);
                count++;
            }
        }
        return count;
    }
}
