package com.zmb.doscortes;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.sqlite.SQLiteConfig;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BookingServer {
    private static final Gson GSON = new Gson();
    private static final ZoneOffset LOCAL_ZONE = ZoneOffset.ofHours(-3);
    private static final int MAX_REQUEST_SIZE = 8192;
    private static final Path BASE_DIR = Path.of(".").toAbsolutePath().normalize();
    private static final Path DATA_DIR = Path.of(
        System.getenv().getOrDefault("DATA_DIR", BASE_DIR.resolve("data").toString())
    ).toAbsolutePath().normalize();
    private static final Path DATABASE_PATH = DATA_DIR.resolve("appointments.sqlite3");
    private static final List<String> ALLOWED_SLOTS = buildAllowedSlots();
    private static final Map<String, StaticFile> STATIC_FILES = Map.of(
        "/", new StaticFile("index.html", "text/html; charset=utf-8"),
        "/index.html", new StaticFile("index.html", "text/html; charset=utf-8"),
        "/styles.css", new StaticFile("styles.css", "text/css; charset=utf-8"),
        "/app.js", new StaticFile("app.js", "text/javascript; charset=utf-8"),
        "/assets/logo.jpg", new StaticFile("assets/logo.jpg", "image/jpeg")
    );
    private static final Map<String, String> SERVICES = Map.ofEntries(
        Map.entry("corte", "Corte"),
        Map.entry("corte-infantil", "Corte infantil (3 a 5 anos)"),
        Map.entry("corte-domicilio", "Corte a domicílio"),
        Map.entry("corte-barba-sobrancelha", "Corte + barba + sobrancelha"),
        Map.entry("corte-barba", "Corte + barba"),
        Map.entry("corte-sobrancelha", "Corte + sobrancelha"),
        Map.entry("corte-cavanhaque-sobrancelha", "Corte + cavanhaque + sobrancelha"),
        Map.entry("corte-cavanhaque", "Corte + cavanhaque"),
        Map.entry("barba", "Barba"),
        Map.entry("sobrancelha", "Sobrancelha"),
        Map.entry("cavanhaque", "Cavanhaque")
    );

    private BookingServer() {
    }

    public static void main(String[] args) throws IOException, SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("SQLite JDBC driver was not found.", exception);
        }
        initializeDatabase();
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", BookingServer::handleRequest);
        ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors())
        );
        server.setExecutor(executor);
        server.start();
        System.out.printf("ZMB Dos Cortes listening on port %d.%n", port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            executor.shutdown();
        }));
    }

    private static void initializeDatabase() throws IOException, SQLException {
        Files.createDirectories(DATA_DIR);
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS appointments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer_name TEXT NOT NULL,
                    customer_phone TEXT NOT NULL,
                    service_id TEXT NOT NULL DEFAULT 'corte',
                    appointment_date TEXT NOT NULL,
                    appointment_time TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE (appointment_date, appointment_time)
                )
                """);

            boolean hasServiceId = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(appointments)")) {
                while (columns.next()) {
                    if ("service_id".equals(columns.getString("name"))) {
                        hasServiceId = true;
                        break;
                    }
                }
            }
            if (!hasServiceId) {
                statement.execute(
                    "ALTER TABLE appointments ADD COLUMN service_id TEXT NOT NULL DEFAULT 'corte'"
                );
            }
        }
    }

    private static Connection openConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10_000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH, config.toProperties());
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("/api/status".equals(path)) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, error("Método não permitido."));
                return;
            }
            JsonObject status = new JsonObject();
            status.addProperty("bookingEnabled", true);
            sendJson(exchange, 200, status);
            return;
        }
        if ("/api/slots".equals(path)) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, error("Método não permitido."));
                return;
            }
            handleGetSlots(exchange);
            return;
        }
        if ("/api/appointments".equals(path)) {
            if (!"POST".equals(method)) {
                sendJson(exchange, 405, error("Método não permitido."));
                return;
            }
            handleCreateAppointment(exchange);
            return;
        }
        if (!"GET".equals(method)) {
            sendError(exchange, 404, "Não encontrado.");
            return;
        }
        serveStaticFile(exchange, path);
    }

    private static void handleGetSlots(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            LocalDate selectedDate = validateAppointmentDate(query.get("date"));
            List<String> bookedTimes = getBookedTimes(selectedDate);
            JsonArray slots = new JsonArray();
            for (String slot : ALLOWED_SLOTS) {
                JsonObject item = new JsonObject();
                item.addProperty("time", slot);
                item.addProperty(
                    "available",
                    !bookedTimes.contains(slot) && appointmentIsInFuture(selectedDate, slot)
                );
                slots.add(item);
            }
            JsonObject response = new JsonObject();
            response.addProperty("date", selectedDate.toString());
            response.add("slots", slots);
            sendJson(exchange, 200, response);
        } catch (BookingException exception) {
            sendError(exchange, 400, exception.getMessage());
        } catch (SQLException exception) {
            exception.printStackTrace(System.err);
            sendError(exchange, 500, "Não foi possível consultar a agenda.");
        }
    }

    private static void handleCreateAppointment(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            sendError(exchange, 415, "O pedido deve ser enviado em JSON.");
            return;
        }

        JsonObject payload;
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_SIZE + 1);
            if (body.length == 0 || body.length > MAX_REQUEST_SIZE) {
                sendError(exchange, 413, "O pedido está vazio ou é grande demais.");
                return;
            }
            JsonElement parsed = GSON.fromJson(
                new String(body, StandardCharsets.UTF_8),
                JsonElement.class
            );
            if (parsed == null || !parsed.isJsonObject()) {
                sendError(exchange, 400, "Os dados do agendamento são inválidos.");
                return;
            }
            payload = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            sendError(exchange, 400, "Não foi possível ler os dados enviados.");
            return;
        }

        String name = getString(payload, "name");
        String phone = getString(payload, "phone");
        String selectedTime = getString(payload, "time");
        String serviceId = getString(payload, "service");
        String dateValue = getString(payload, "date");
        if (name == null || phone == null || selectedTime == null || serviceId == null) {
            sendError(exchange, 400, "Preencha serviço, nome, WhatsApp, data e horário.");
            return;
        }
        if (!SERVICES.containsKey(serviceId)) {
            sendError(exchange, 400, "Selecione um serviço válido da tabela de preços.");
            return;
        }

        name = name.trim().replaceAll("\\s+", " ");
        phone = phone.replaceAll("\\D", "");
        if (name.isEmpty() || name.length() > 80) {
            sendError(exchange, 400, "Informe um nome com até 80 caracteres.");
            return;
        }
        if (phone.length() < 10 || phone.length() > 15) {
            sendError(exchange, 400, "Informe um número de WhatsApp válido com DDD.");
            return;
        }

        LocalDate selectedDate;
        try {
            selectedDate = validateAppointmentDate(dateValue);
        } catch (BookingException exception) {
            sendError(exchange, 400, exception.getMessage());
            return;
        }
        if (!ALLOWED_SLOTS.contains(selectedTime)
                || !appointmentIsInFuture(selectedDate, selectedTime)) {
            sendError(exchange, 400, "Esse horário não está disponível para agendamento.");
            return;
        }

        try {
            if (!reserveSlot(name, phone, serviceId, selectedDate, selectedTime)) {
                sendError(exchange, 409, "Esse horário acabou de ser reservado. Escolha outro horário.");
                return;
            }
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                sendError(exchange, 409, "Esse horário acabou de ser reservado. Escolha outro horário.");
            } else {
                exception.printStackTrace(System.err);
                sendError(exchange, 500, "Não foi possível salvar o agendamento. Tente novamente.");
            }
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("date", selectedDate.toString());
        response.addProperty("time", selectedTime);
        response.addProperty("message", "Agendamento confirmado. O horário foi reservado.");
        sendJson(exchange, 201, response);
    }

    private static boolean reserveSlot(
            String name,
            String phone,
            String serviceId,
            LocalDate selectedDate,
            String selectedTime) throws SQLException {
        try (Connection connection = openConnection();
             Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT 1 FROM appointments
                        WHERE appointment_date = ? AND appointment_time = ?
                        LIMIT 1
                        """)) {
                    query.setString(1, selectedDate.toString());
                    query.setString(2, selectedTime);
                    try (ResultSet result = query.executeQuery()) {
                        if (result.next()) {
                            transaction.execute("ROLLBACK");
                            return false;
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO appointments (
                            customer_name, customer_phone, service_id,
                            appointment_date, appointment_time, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, name);
                    insert.setString(2, phone);
                    insert.setString(3, serviceId);
                    insert.setString(4, selectedDate.toString());
                    insert.setString(5, selectedTime);
                    insert.setString(6, LocalDateTime.now(LOCAL_ZONE).toString());
                    insert.executeUpdate();
                }
                transaction.execute("COMMIT");
                return true;
            } catch (SQLException | RuntimeException exception) {
                try {
                    transaction.execute("ROLLBACK");
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    private static List<String> getBookedTimes(LocalDate selectedDate) throws SQLException {
        List<String> bookedTimes = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement query = connection.prepareStatement(
                 "SELECT appointment_time FROM appointments WHERE appointment_date = ?"
             )) {
            query.setString(1, selectedDate.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    bookedTimes.add(result.getString("appointment_time"));
                }
            }
        }
        return bookedTimes;
    }

    private static void serveStaticFile(HttpExchange exchange, String path) throws IOException {
        StaticFile file = STATIC_FILES.get(path);
        if (file == null) {
            sendError(exchange, 404, "Não encontrado.");
            return;
        }
        Path filePath = BASE_DIR.resolve(file.path()).normalize();
        if (!filePath.startsWith(BASE_DIR) || !Files.isRegularFile(filePath)) {
            sendError(exchange, 404, "Não encontrado.");
            return;
        }
        byte[] content = Files.readAllBytes(filePath);
        exchange.getResponseHeaders().set("Content-Type", file.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        addSecurityHeaders(exchange);
        exchange.sendResponseHeaders(200, content.length);
        try (var output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message)
            throws IOException {
        sendJson(exchange, status, error(message));
    }

    private static JsonObject error(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("error", message);
        return response;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonElement payload)
            throws IOException {
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        addSecurityHeaders(exchange);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                : "";
            result.putIfAbsent(key, value);
        }
        return result;
    }

    private static String getString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    private static LocalDate validateAppointmentDate(String value) throws BookingException {
        if (value == null) {
            throw new BookingException("Selecione uma data válida.");
        }
        LocalDate selected;
        try {
            selected = LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BookingException("Selecione uma data válida.");
        }
        LocalDate today = LocalDate.now(LOCAL_ZONE);
        if (!selected.toString().equals(value) || selected.isBefore(today)) {
            throw new BookingException("A data selecionada já passou ou é inválida.");
        }
        if (selected.isAfter(today.plusDays(90))) {
            throw new BookingException("A agenda está aberta para os próximos 90 dias.");
        }
        DayOfWeek weekday = selected.getDayOfWeek();
        if (weekday.getValue() < DayOfWeek.TUESDAY.getValue()
                || weekday.getValue() > DayOfWeek.SATURDAY.getValue()) {
            throw new BookingException("Os atendimentos acontecem de terça a sábado.");
        }
        return selected;
    }

    private static boolean appointmentIsInFuture(LocalDate selectedDate, String selectedTime) {
        return LocalDateTime.of(selectedDate, LocalTime.parse(selectedTime))
            .isAfter(LocalDateTime.now(LOCAL_ZONE));
    }

    private static List<String> buildAllowedSlots() {
        List<String> slots = new ArrayList<>();
        addSlots(slots, 9 * 60, 11 * 60 + 20);
        addSlots(slots, 13 * 60, 18 * 60 + 15);
        return List.copyOf(slots);
    }

    private static void addSlots(List<String> slots, int start, int end) {
        for (int minutes = start; minutes <= end; minutes += 35) {
            slots.add("%02d:%02d".formatted(minutes / 60, minutes % 60));
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        return exception.getErrorCode() == 19
            || (exception.getMessage() != null
                && exception.getMessage().contains("UNIQUE constraint failed"));
    }

    private record StaticFile(String path, String contentType) {
    }

    private static final class BookingException extends Exception {
        private BookingException(String message) {
            super(message);
        }
    }
}
