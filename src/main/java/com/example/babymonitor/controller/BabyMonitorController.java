package com.example.babymonitor.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class BabyMonitorController {

    private static final String DEFAULT_DEVICE = "unknown";

    // Le telephone envoie 1 image/4s : avec 750 images conservees par appareil,
    // cela represente environ 50 minutes d'historique (surveillance en direct).
    private static final int MAX_IMAGES_PER_DEVICE = 750;

    // Frequence de capture (en secondes) par appareil, reglable depuis la page
    // web. Par defaut : 1 photo / 4s. Persiste dans state/intervals.json.
    // Max 60s : cohérent avec le telephone (qui plafonne aussi a 60s) et la
    // liste de valeurs proposee dans la page web.
    private static final int DEFAULT_INTERVAL_SEC = 4;
    private static final int MIN_INTERVAL_SEC = 1;
    private static final int MAX_INTERVAL_SEC = 60;

    // Extrait la milliseconde de capture depuis le nom de fichier envoye par le
    // telephone (screen_<millis>.jpg).
    private static final Pattern FILENAME_MILLIS = Pattern.compile("\\d+");

    // Etat "capture active" par appareil (cle = id de l'appareil).
    // Persiste dans state/commands.json pour survivre a un redemarrage de l'app.
    private final Map<String, Boolean> captureActive = new ConcurrentHashMap<>();

    // Frequence de capture (en secondes) par appareil, reglee depuis la page web.
    private final Map<String, Integer> captureIntervals = new ConcurrentHashMap<>();

    // Appareils enregistres (POST /api/register) : visibles dans le selecteur web
    // meme avant d'avoir envoye leur premiere image.
    private final Set<String> knownDevices = ConcurrentHashMap.newKeySet();

    private final Path uploadDir = Paths.get("uploads");
    private final Path stateDir = Paths.get("state");
    private final Path commandsFile = stateDir.resolve("commands.json");
    private final Path devicesFile = stateDir.resolve("devices.json");
    private final Path intervalsFile = stateDir.resolve("intervals.json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(uploadDir);
        Files.createDirectories(stateDir);
        loadPersistedState();
        System.out.println("Serveur BabyMonitor demarre. Uploads dans : " + uploadDir.toAbsolutePath());
    }

    // ---- Persistance d'etat (best effort : le disque de Render gratuit est ephemere) ----

    private void loadPersistedState() {
        try {
            if (Files.exists(commandsFile)) {
                Map<String, Boolean> saved = objectMapper.readValue(
                        commandsFile.toFile(), new TypeReference<Map<String, Boolean>>() {});
                if (saved != null) {
                    captureActive.putAll(saved);
                    System.out.println("Etat capture recharge : " + saved);
                }
            }
            if (Files.exists(devicesFile)) {
                List<String> saved = objectMapper.readValue(
                        devicesFile.toFile(), new TypeReference<List<String>>() {});
                if (saved != null) {
                    knownDevices.addAll(saved);
                    System.out.println("Appareils recharges : " + saved);
                }
            }
            if (Files.exists(intervalsFile)) {
                Map<String, Integer> saved = objectMapper.readValue(
                        intervalsFile.toFile(), new TypeReference<Map<String, Integer>>() {});
                if (saved != null) {
                    captureIntervals.putAll(saved);
                    System.out.println("Intervalles recharges : " + saved);
                }
            }
        } catch (IOException e) {
            System.out.println("Etat persiste illisible, on repart de zero : " + e.getMessage());
        }
    }

    private void persistCommands() {
        try {
            Files.createDirectories(stateDir);
            objectMapper.writeValue(commandsFile.toFile(), captureActive);
        } catch (IOException e) {
            System.out.println("Impossible de persister l'etat capture : " + e.getMessage());
        }
    }

    private void persistDevices() {
        try {
            Files.createDirectories(stateDir);
            objectMapper.writeValue(devicesFile.toFile(), new ArrayList<>(knownDevices));
        } catch (IOException e) {
            System.out.println("Impossible de persister la liste des appareils : " + e.getMessage());
        }
    }

    private void persistIntervals() {
        try {
            Files.createDirectories(stateDir);
            objectMapper.writeValue(intervalsFile.toFile(), captureIntervals);
        } catch (IOException e) {
            System.out.println("Impossible de persister les intervalles : " + e.getMessage());
        }
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

    // ---- Enregistrement du telephone ----
    // Casse le blocage "l'appareil n'apparait dans la liste qu'apres avoir envoye
    // une image, mais il n'envoie une image que si on lui a donne l'ordre depuis
    // la liste web". Le telephone s'enregistre des son demarrage, donc il est
    // visible dans le selecteur avant la premiere capture.

    @PostMapping("/register")
    public ResponseEntity<?> registerDevice(@RequestParam(defaultValue = DEFAULT_DEVICE) String device) {
        String dev = sanitizeDevice(device);
        if (knownDevices.add(dev)) {
            persistDevices();
            System.out.println("[ENREGISTREMENT] Appareil " + dev);
        }
        return ResponseEntity.ok(Map.of("status", "registered", "device", dev));
    }

    // ---- Commande (start/stop capture) par appareil ----

    @PostMapping("/command")
    public ResponseEntity<?> setCommand(@RequestParam boolean active,
                                         @RequestParam(defaultValue = DEFAULT_DEVICE) String device,
                                         @RequestParam(required = false) Integer interval) {
        String dev = sanitizeDevice(device);
        captureActive.put(dev, active);
        if (interval != null) {
            int clamped = Math.max(MIN_INTERVAL_SEC, Math.min(MAX_INTERVAL_SEC, interval));
            captureIntervals.put(dev, clamped);
            persistIntervals();
        }
        persistCommands();
        int currentInterval = captureIntervals.getOrDefault(dev, DEFAULT_INTERVAL_SEC);
        System.out.println("[COMMANDE] " + dev + " -> " + (active ? "CAPTURE ACTIVE" : "EN ATTENTE")
                + " (intervalle " + currentInterval + "s)");
        return ResponseEntity.ok(Map.of("status", "ok", "capture", active,
                "interval", currentInterval, "device", dev));
    }

    @GetMapping("/command")
    public ResponseEntity<?> getCommand(@RequestParam(defaultValue = DEFAULT_DEVICE) String device) {
        String dev = sanitizeDevice(device);
        return ResponseEntity.ok(Map.of(
                "capture", captureActive.getOrDefault(dev, false),
                "interval", captureIntervals.getOrDefault(dev, DEFAULT_INTERVAL_SEC),
                "device", dev));
    }

    // ---- Upload d'une ou plusieurs captures d'ecran ----
    // Le telephone envoie les images par lot (champ multipart "files", jusqu'a 10
    // par requete). Le champ "file" (1 image) reste accepte pour compatibilite.

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(defaultValue = DEFAULT_DEVICE) String device) {
        try {
            String dev = sanitizeDevice(device);
            Path dir = deviceDir(dev);

            List<MultipartFile> all = new ArrayList<>();
            if (files != null) all.addAll(files);
            if (file != null) all.add(file);
            if (all.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Aucun fichier recu"));
            }

            int saved = 0;
            for (MultipartFile f : all) {
                if (f.isEmpty()) continue;
                // Le telephone indique l'heure de capture dans le nom du fichier
                // (screen_<millis>.jpg) : on la reprend pour un historique fiable.
                long ts = System.currentTimeMillis();
                String original = f.getOriginalFilename();
                if (original != null) {
                    Matcher m = FILENAME_MILLIS.matcher(original);
                    if (m.find()) {
                        try {
                            ts = Long.parseLong(m.group());
                        } catch (NumberFormatException ignored) {
                            // nom illisible : on garde l'heure du serveur
                        }
                    }
                }
                // Suffixe _NN : garantit un nom unique au sein d'un lot.
                String filename = "screen_" + ts + "_" + String.format("%02d", saved) + ".jpg";
                Files.copy(f.getInputStream(), dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                saved++;
            }

            // L'appareil vient de se manifester : on le rend visible dans la liste web.
            if (knownDevices.add(dev)) {
                persistDevices();
            }

            // Retention : ne garde que les N images les plus recentes par appareil.
            pruneOldImages(dir, MAX_IMAGES_PER_DEVICE);

            System.out.println("Lot recu [" + dev + "] : " + saved + " image(s)");
            return ResponseEntity.ok(Map.of("status", "received", "saved", saved, "device", dev));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private void pruneOldImages(Path dir, int keep) throws IOException {
        List<Path> jpgs;
        try (Stream<Path> files = Files.list(dir)) {
            // Tri lexicographique = tri chronologique (screen_<millis>.jpg, memes longueurs)
            jpgs = files
                    .filter(p -> p.toString().endsWith(".jpg"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        while (jpgs.size() > keep) {
            Files.deleteIfExists(jpgs.remove(0));
        }
    }

    // ---- Liste des appareils connus (enregistres + ceux qui ont envoye une image) ----

    @GetMapping("/devices")
    public ResponseEntity<?> listDevices() {
        Set<String> devices = new TreeSet<>(knownDevices);
        if (Files.exists(uploadDir)) {
            try (Stream<Path> paths = Files.list(uploadDir)) {
                paths.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .forEach(devices::add);
            } catch (IOException e) {
                return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
            }
        }
        return ResponseEntity.ok(new ArrayList<>(devices));
    }

    // ---- Liste des images d'un appareil precis ----

    @GetMapping("/images")
    public ResponseEntity<?> listImages(@RequestParam(defaultValue = DEFAULT_DEVICE) String device) {
        try {
            String dev = sanitizeDevice(device);
            Path dir = uploadDir.resolve(dev);
            // Pas de creation de dossier en lecture : on renvoie simplement une liste vide.
            if (!Files.isDirectory(dir)) {
                return ResponseEntity.ok(List.of());
            }
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
            String dev = sanitizeDevice(device);
            Path dir = uploadDir.resolve(dev);
            // Pas de creation de dossier en lecture : image introuvable si le dossier n'existe pas.
            if (!Files.isDirectory(dir)) {
                return ResponseEntity.notFound().build();
            }
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
                    // Pas de cache navigateur : on surveille en "direct"
                    .header("Cache-Control", "no-store")
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
