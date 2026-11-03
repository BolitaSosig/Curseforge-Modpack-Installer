import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instala los archivos de un modpack exportado por CurseForge.
 * Requiere Java 17+ y acceso a los enlaces públicos de CurseForge.
 */
public class InstalarModpack {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        List<String> paths = new ArrayList<>();
        boolean api = false;
        URI zipLink = null;
        Path instance = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.println("Uso: java InstalarModpack.java [--api] [-i instancia] [--zipLink URL] [modpack.zip] [carpeta-destino]");
                System.out.println("--zipLink descarga un ZIP temporal en lugar de usar un ZIP local.");
                System.out.println("--api usa el endpoint público de CurseForge sin clave; por defecto usa modlist.html.");
                System.out.println("-i instala en una instancia existente y reemplaza completamente su carpeta mods.");
                return;
            }
            if (arg.equals("--api")) {
                api = true;
            } else if (arg.equals("--zipLink")) {
                if (zipLink != null || i + 1 >= args.length || args[i + 1].startsWith("-")) {
                    throw new IOException("--zipLink requiere una única URL HTTP o HTTPS.");
                }
                try {
                    zipLink = URI.create(args[++i]);
                } catch (IllegalArgumentException e) {
                    throw new IOException("URL de ZIP no válida.", e);
                }
                if (zipLink.getHost() == null || zipLink.getUserInfo() != null
                        || !("http".equalsIgnoreCase(zipLink.getScheme())
                        || "https".equalsIgnoreCase(zipLink.getScheme()))) {
                    throw new IOException("--zipLink requiere una URL HTTP o HTTPS válida sin credenciales.");
                }
            } else if (arg.equals("-i")) {
                if (instance != null || i + 1 >= args.length || args[i + 1].startsWith("-")) {
                    throw new IOException("-i requiere una única ruta de instancia.");
                }
                instance = Path.of(args[++i]).toAbsolutePath().normalize();
            } else if (arg.startsWith("--")) {
                throw new IOException("Opción desconocida: " + arg);
            } else {
                paths.add(arg);
            }
        }
        if (paths.size() > 2) {
            throw new IOException("Uso: java InstalarModpack.java [--api] [modpack.zip] [carpeta-destino]");
        }
        if (zipLink != null && !paths.isEmpty()) {
            throw new IOException("--zipLink no se puede combinar con rutas posicionales; use -i para elegir la instancia.");
        }
        if (instance != null && paths.size() > 1) {
            throw new IOException("No se puede combinar -i con una carpeta de destino posicional.");
        }
        Path zipPath = Path.of(paths.size() >= 1 ? paths.get(0) : "ServerTest_26.3-0.1.zip");
        Path root = instance != null ? instance : Path.of(paths.size() >= 2 ? paths.get(1) : "ServerTest_26.3-0.1")
                .toAbsolutePath().normalize();
        if (instance != null && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("La instancia debe ser un directorio existente sin enlace simbólico: " + root);
        }
        Path downloadedZip = null;
        try {
            if (zipLink != null) {
                downloadedZip = Files.createTempFile("curseforge-modpack-", ".zip");
                downloadZip(zipLink, downloadedZip);
                zipPath = downloadedZip;
            }
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException("El ZIP no contiene manifest.json en su raíz.");
            }
            Map<String, Object> manifest;
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                manifest = object(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)), "manifest.json");
            }
            if (!"minecraftModpack".equals(manifest.get("manifestType"))) {
                throw new IOException("El manifiesto no es un modpack de Minecraft de CurseForge.");
            }
            Map<String, Object> minecraft = object(manifest.get("minecraft"), "minecraft");
            List<Object> files = array(manifest.get("files"), "files");
            String overrides = manifest.get("overrides") instanceof String s ? s : "overrides";
            safeRelative(overrides);
            List<String> links = List.of();
            if (!api) {
                ZipEntry modlistEntry = zip.getEntry("modlist.html");
                if (modlistEntry == null || modlistEntry.isDirectory()) {
                    throw new IOException("El ZIP no contiene modlist.html en su raíz.");
                }
                try (InputStream in = zip.getInputStream(modlistEntry)) {
                    links = modLinks(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                if (links.size() != files.size()) {
                    throw new IOException("modlist.html contiene " + links.size() + " enlaces y el manifiesto "
                            + files.size() + " archivos; no se pueden asociar por posición.");
                }
                System.out.println("Aviso: los enlaces se asocian por posición; el ZIP no permite verificar sus projectID.");
            } else {
                System.out.println("Modo API pública: se usan los projectID y fileID del manifiesto, sin clave.");
            }

            System.out.println("Modpack: " + manifest.get("name") + " " + manifest.get("version"));
            System.out.println("Minecraft: " + minecraft.get("version"));
            System.out.println("Cargadores: " + minecraft.get("modLoaders"));
            System.out.println("Destino: " + root);
            System.out.println("El cargador y Minecraft se instalan por separado.");

            ensureDirectory(root);
            Path staging = instance == null ? null : Files.createTempDirectory(root, ".modpack-stage-");
            try {
            Path installRoot = staging == null ? root : staging;
            ensureDirectory(installRoot.resolve("mods"));
            copyOverrides(zip, overrides, installRoot);

            List<String> failures = new ArrayList<>();
            int number = 0;
            for (Object item : files) {
                number++;
                Map<String, Object> file = object(item, "files[" + (number - 1) + "]");
                long projectId = positiveLong(file.get("projectID"), "projectID");
                long fileId = positiveLong(file.get("fileID"), "fileID");
                boolean required = !Boolean.FALSE.equals(file.get("required"));
                System.out.printf("[%d/%d] Proyecto %d, archivo %d%s%n", number, files.size(),
                        projectId, fileId, required ? "" : " (opcional)");
                String link = api ? "https://www.curseforge.com/api/v1/mods/" + projectId
                        + "/files/" + fileId + "/download" : links.get(number - 1);
                try {
                    installFile(installRoot, link, fileId, api);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    String message = "Proyecto " + projectId + ", archivo " + fileId + ": " + e.getMessage()
                            + "\n  Enlace de descarga: " + (api ? link : link + "/download/" + fileId);
                    System.err.println("  No instalado: " + message);
                    failures.add(message);
                }
            }
            if (!failures.isEmpty()) {
                System.err.println("\nInstalación incompleta: " + failures.size() + " archivo(s) no instalados.");
                System.err.println("Revise los mensajes anteriores; los archivos fallidos requieren atención.");
                System.err.println("Si CurseForge bloquea una descarga, utilice el enlace indicado en el navegador.");
                throw new IOException("No se pudieron instalar todos los archivos del manifiesto.");
            }
            if (staging != null) {
                copyOverrides(zip, overrides, root, true);
                replaceMods(root, staging.resolve("mods"));
            }
            System.out.println("\nArchivos del modpack instalados correctamente en " + root);
            System.out.println("Configure un perfil del launcher con la versión de Minecraft y el cargador indicados.");
            } finally {
                if (staging != null) deleteTree(staging);
            }
        }
        } finally {
            if (downloadedZip != null) Files.deleteIfExists(downloadedZip);
        }
    }

    private static void downloadZip(URI link, Path destination) throws Exception {
        System.out.println("Descargando ZIP del modpack...");
        HttpRequest request = HttpRequest.newBuilder(link)
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("La descarga del ZIP devolvió HTTP " + response.statusCode() + ".");
            }
            Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /*
    private static void installFile(Path root, String key, long projectId, long fileId) throws Exception {
        URI apiUri = URI.create(API + "/mods/" + projectId + "/files/" + fileId);
        HttpRequest request = HttpRequest.newBuilder(apiUri)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("x-api-key", key)
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("API CurseForge devolvió HTTP " + response.statusCode()
                    + ". Compruebe la clave y los IDs.");
        }
        Map<String, Object> data = object(object(Json.parse(response.body()), "respuesta").get("data"), "data");
        if (positiveLong(data.get("id"), "id") != fileId
                || positiveLong(data.get("modId"), "modId") != projectId) {
            throw new IOException("La API devolvió un archivo o proyecto diferente.");
        }
        String fileName = string(data.get("fileName"), "fileName");
        Path name = safeRelative(fileName);
        if (name.getNameCount() != 1 || fileName.contains("/") || fileName.contains("\\")) {
            throw new IOException("Nombre de archivo no válido: " + fileName);
        }
        long length = nonNegativeLong(data.get("fileLength"), "fileLength");
        String algorithm = null;
        String expectedHash = null;
        if (data.get("hashes") instanceof List<?> hashes) {
            for (Object entry : hashes) {
                Map<String, Object> hash = object(entry, "hash");
                long algo = nonNegativeLong(hash.get("algo"), "hash.algo");
                if (algo == 1 || (algo == 2 && algorithm == null)) {
                    algorithm = algo == 1 ? "SHA-1" : "MD5";
                    expectedHash = string(hash.get("value"), "hash.value");
                    if (algo == 1) break;
                }
            }
        }

        Path target = root.resolve("mods").resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("El destino no es un archivo normal: " + target);
            }
            if (algorithm == null) {
                throw new IOException("No se puede verificar el archivo existente sin hash: " + target);
            }
            verify(target, length, algorithm, expectedHash);
            System.out.println("  Ya verificado: " + fileName);
            return;
        }
        Object downloadUrl = data.get("downloadUrl");
        if (!(downloadUrl instanceof String url) || url.isBlank()) {
            throw new IOException("CurseForge no proporciona descarga automática para " + fileName
                    + ". Descárguelo manualmente desde https://www.curseforge.com/projects/" + projectId
                    + " (archivo ID " + fileId + ") y colóquelo en mods.");
        }
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("La URL de descarga no es HTTPS válida.");
        }
        if (algorithm == null) {
            System.err.println("  Aviso: la API no proporciona hash para " + fileName + "; solo se verificará el tamaño.");
        }
        HttpRequest download = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<InputStream> result = HTTP.send(download, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = result.body()) {
            if (result.statusCode() != 200) {
                throw new IOException("Descarga de " + fileName + ": HTTP " + result.statusCode());
            }
            Path temp = Files.createTempFile(target.getParent(), ".curseforge-", ".part");
            try {
                Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
                verify(temp, length, algorithm, expectedHash);
                Files.move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        System.out.println("  Descargado: " + fileName);
    }

    private static void verify(Path file, long length, String algorithm, String expectedHash) throws Exception {
        if (Files.size(file) != length) {
            throw new IOException("Tamaño incorrecto: " + file.getFileName());
        }
        if (algorithm != null) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedHash)) {
                throw new IOException("Hash incorrecto: " + file.getFileName());
            }
        }
    }

    */

    private static List<String> modLinks(String html) throws IOException {
        List<String> links = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            @Override
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                if (tag != HTML.Tag.A) return;
                Object href = attributes.getAttribute(HTML.Attribute.HREF);
                if (href != null) links.add(href.toString());
            }
        }, true);
        for (int i = 0; i < links.size(); i++) {
            String link = links.get(i);
            if (!link.matches("https://www\\.curseforge\\.com/minecraft/mc-mods/[A-Za-z0-9_-]+/?")) {
                throw new IOException("Enlace de mod no válido: " + link);
            }
            if (link.endsWith("/")) links.set(i, link.substring(0, link.length() - 1));
        }
        return links;
    }

    private static void installFile(Path root, String link, long fileId, boolean api) throws Exception {
        String url = api ? link : link + "/download/" + fileId;
        IOException last = null;
        for (String candidate : api ? List.of(url) : List.of(url, url + "/file")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(candidate))
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    last = new IOException("HTTP " + response.statusCode() + " en " + candidate
                            + (response.headers().firstValue("cf-mitigated").isPresent()
                            ? " (desafío de Cloudflare)" : ""));
                    continue;
                }
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                if (type.contains("text/html")) {
                    last = new IOException("CurseForge devolvió una página HTML en vez del archivo: " + candidate);
                    continue;
                }
                String disposition = response.headers().firstValue("Content-Disposition").orElse("");
                Matcher matcher = Pattern.compile("(?i)filename\\*=UTF-8''([^;]+)|filename=\"([^\"]+)\"|filename=([^;]+)")
                        .matcher(disposition);
                String fileName = (api ? "curseforge" : link.substring(link.lastIndexOf('/') + 1))
                        + "-" + fileId + ".jar";
                if (matcher.find()) {
                    if (matcher.group(1) != null) fileName = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
                    else fileName = matcher.group(2) != null ? matcher.group(2) : matcher.group(3).trim();
                } else {
                    String path = response.uri().getPath();
                    if (path != null && path.toLowerCase().endsWith(".jar")) {
                        fileName = path.substring(path.lastIndexOf('/') + 1);
                    }
                }
                Path name = safeRelative(fileName);
                if (name.getNameCount() != 1 || !fileName.toLowerCase().endsWith(".jar")) {
                    throw new IOException("Nombre de mod no válido: " + fileName);
                }
                Path target = root.resolve("mods").resolve(name);
                Path temp = Files.createTempFile(target.getParent(), ".curseforge-", ".part");
                try {
                    Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
                    try (ZipFile archive = new ZipFile(temp.toFile())) {
                        if (!archive.entries().hasMoreElements()) throw new IOException("El archivo ZIP está vacío.");
                    } catch (IOException e) {
                        last = new IOException("La respuesta no es un JAR válido: " + e.getMessage());
                        continue;
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                                || Files.mismatch(temp, target) != -1) {
                            throw new IOException("Ya existe un archivo diferente: " + target);
                        }
                        System.out.println("  Ya existente y coincide: " + fileName);
                    } else {
                        Files.move(temp, target);
                        System.out.println("  Descargado: " + fileName);
                    }
                    return;
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        }
        throw last == null ? new IOException("No se pudo descargar " + url) : last;
    }

    private static void copyOverrides(ZipFile zip, String overrides, Path root) throws Exception {
        copyOverrides(zip, overrides, root, false);
    }

    private static void copyOverrides(ZipFile zip, String overrides, Path root, boolean skipMods) throws Exception {
        String prefix = overrides.replaceAll("/+$", "") + "/";
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int copied = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(prefix) || name.equals(prefix)) continue;
            String relative = name.substring(prefix.length());
            if (skipMods && (relative.equalsIgnoreCase("mods") || relative.toLowerCase(java.util.Locale.ROOT).startsWith("mods/"))) continue;
            if (entry.isDirectory()) {
                relative = relative.replaceAll("/+$", "");
                if (!relative.isEmpty()) ensureDirectory(root.resolve(safeRelative(relative)));
                continue;
            }
            Path target = root.resolve(safeRelative(relative));
            ensureDirectory(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".override-", ".part");
            try {
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                            || Files.mismatch(temp, target) != -1) {
                        throw new IOException("El archivo existente difiere del override: " + target);
                    }
                } else {
                    Files.move(temp, target);
                    copied++;
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        System.out.println("Archivos de overrides copiados: " + copied);
    }

    private static void replaceMods(Path root, Path stagedMods) throws IOException {
        Path mods = root.resolve("mods");
        if (Files.exists(mods, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("La carpeta mods existente no es un directorio normal: " + mods);
        }
        if (!Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(stagedMods, mods);
            return;
        }
        Path backup = Files.createTempDirectory(root, ".modpack-backup-");
        Path oldMods = backup.resolve("mods");
        try {
            Files.move(mods, oldMods);
        } catch (IOException e) {
            Files.deleteIfExists(backup);
            throw e;
        }
        try {
            Files.move(stagedMods, mods);
        } catch (IOException e) {
            try {
                Files.move(oldMods, mods);
                Files.deleteIfExists(backup);
            } catch (IOException restore) {
                e.addSuppressed(restore);
                throw new IOException("No se pudo sustituir ni restaurar mods. Copia anterior: " + oldMods, e);
            }
            throw e;
        }
        try {
            deleteTree(backup);
        } catch (IOException e) {
            System.err.println("Aviso: mods se ha sustituido, pero no se pudo borrar la copia anterior: " + backup);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path safeRelative(String value) throws IOException {
        if (value.isBlank() || value.contains("\\") || value.indexOf('\0') >= 0
                || value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IOException("Ruta no válida en el modpack: " + value);
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.contains(":")) {
                throw new IOException("Ruta no válida en el modpack: " + value);
            }
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) throw new IOException("Ruta absoluta no permitida: " + value);
        return path;
    }

    private static void ensureDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("No es un directorio normal o es un enlace simbólico: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) throws IOException {
        if (!(value instanceof Map<?, ?>)) throw new IOException("Se esperaba un objeto en " + field);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String field) throws IOException {
        if (!(value instanceof List<?>)) throw new IOException("Se esperaba una lista en " + field);
        return (List<Object>) value;
    }

    private static String string(Object value, String field) throws IOException {
        if (!(value instanceof String s) || s.isBlank()) throw new IOException("Texto no válido en " + field);
        return s;
    }

    private static long nonNegativeLong(Object value, String field) throws IOException {
        try {
            if (!(value instanceof BigDecimal number)) throw new ArithmeticException();
            long result = number.longValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException e) {
            throw new IOException("Número no válido en " + field);
        }
    }

    private static long positiveLong(Object value, String field) throws IOException {
        long result = nonNegativeLong(value, field);
        if (result == 0) throw new IOException("Número no válido en " + field);
        return result;
    }

    /** Analizador JSON autocontenido para evitar dependencias externas. */
    private static final class Json {
        private final String text;
        private int pos;

        private Json(String text) { this.text = text; }

        static Object parse(String text) throws IOException {
            Json parser = new Json(text);
            Object result = parser.value(0);
            parser.space();
            if (parser.pos != text.length()) throw parser.error();
            return result;
        }

        private Object value(int depth) throws IOException {
            if (depth > 100) throw error();
            space();
            if (pos >= text.length()) throw error();
            char c = text.charAt(pos);
            if (c == '"') return quoted();
            if (c == '{') {
                pos++;
                Map<String, Object> map = new LinkedHashMap<>();
                space();
                if (take('}')) return map;
                do {
                    space();
                    if (pos >= text.length() || text.charAt(pos) != '"') throw error();
                    String key = quoted();
                    space();
                    if (!take(':')) throw error();
                    if (map.containsKey(key)) throw error();
                    map.put(key, value(depth + 1));
                    space();
                    if (take('}')) return map;
                } while (take(','));
                throw error();
            }
            if (c == '[') {
                pos++;
                List<Object> list = new ArrayList<>();
                space();
                if (take(']')) return list;
                do {
                    list.add(value(depth + 1));
                    space();
                    if (take(']')) return list;
                } while (take(','));
                throw error();
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (text.startsWith(literal, pos)) {
                    pos += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = pos;
            while (pos < text.length() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            String number = text.substring(start, pos);
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw error();
            try { return new BigDecimal(number); }
            catch (NumberFormatException e) { throw error(); }
        }

        private String quoted() throws IOException {
            if (!take('"')) throw error();
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw error();
                if (c != '\\') { out.append(c); continue; }
                if (pos >= text.length()) throw error();
                c = text.charAt(pos++);
                switch (c) {
                    case '"', '\\', '/' -> out.append(c);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error();
                        try { out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); }
                        catch (NumberFormatException e) { throw error(); }
                        pos += 4;
                    }
                    default -> throw error();
                }
            }
            throw error();
        }

        private void space() {
            while (pos < text.length() && " \n\r\t".indexOf(text.charAt(pos)) >= 0) pos++;
        }
        private boolean take(char c) {
            if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; }
            return false;
        }
        private IOException error() { return new IOException("JSON no válido en posición " + pos); }
    }
}
