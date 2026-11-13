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
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private static final String DATABASE_URL = System.getenv("DATABASE_URL");
    private static final boolean USE_POSTGRES = DATABASE_URL != null && !DATABASE_URL.isBlank();
    private static final String WHATSAPP_RECIPIENT = System.getenv("WHATSAPP_RECIPIENT");
    private static final String ADMIN_PASSWORD = System.getenv("ADMIN_PASSWORD");
    private static final String ADMIN_COOKIE_NAME = "zmb_admin_session";
    private static final Duration ADMIN_SESSION_DURATION = Duration.ofHours(8);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ConcurrentMap<String, Instant> ADMIN_SESSIONS = new ConcurrentHashMap<>();
    private static final List<String> ALLOWED_SLOTS = buildAllowedSlots();
    private static final Map<String, StaticFile> STATIC_FILES = Map.of(
        "/", new StaticFile("index.html", "text/html; charset=utf-8"),
        "/index.html", new StaticFile("index.html", "text/html; charset=utf-8"),
        "/styles.css", new StaticFile("styles.css", "text/css; charset=utf-8"),
        "/app.js", new StaticFile("app.js", "text/javascript; charset=utf-8"),
        "/admin", new StaticFile("admin.html", "text/html; charset=utf-8"),
        "/admin.html", new StaticFile("admin.html", "text/html; charset=utf-8"),
        "/admin.css", new StaticFile("admin.css", "text/css; charset=utf-8"),
        "/admin.js", new StaticFile("admin.js", "text/javascript; charset=utf-8"),
        "/assets/logo.jpg", new StaticFile("assets/logo.jpg", "image/jpeg")
    );
    private static final Map<String, Service> SERVICES = Map.ofEntries(
        Map.entry("corte", new Service("Corte", 2000)),
        Map.entry("corte-infantil", new Service("Corte infantil (3 a 5 anos)", 2500)),
        Map.entry("corte-domicilio", new Service("Corte a domicílio", 3000)),
        Map.entry("corte-barba-sobrancelha", new Service("Corte + barba + sobrancelha", 3700)),
        Map.entry("corte-barba", new Service("Corte + barba", 3000)),
        Map.entry("corte-sobrancelha", new Service("Corte + sobrancelha", 2500)),
        Map.entry(
            "corte-cavanhaque-sobrancelha",
            new Service("Corte + cavanhaque + sobrancelha", 3300)
        ),
        Map.entry("corte-cavanhaque", new Service("Corte + cavanhaque", 2700)),
        Map.entry("barba", new Service("Barba", 1500)),
        Map.entry("sobrancelha", new Service("Sobrancelha", 700)),
        Map.entry("cavanhaque", new Service("Cavanhaque", 1000))
    );

    private BookingServer() {
    }

    public static void main(String[] args) throws IOException, SQLException {
        try {
            Class.forName(USE_POSTGRES ? "org.postgresql.Driver" : "org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("The configured database JDBC driver was not found.", exception);
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
        if (!USE_POSTGRES) {
            Files.createDirectories(DATA_DIR);
        }
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            String idColumn = USE_POSTGRES
                ? "BIGSERIAL PRIMARY KEY"
                : "INTEGER PRIMARY KEY AUTOINCREMENT";
            statement.execute("""
                CREATE TABLE IF NOT EXISTS appointments (
                    id %s,
                    customer_name TEXT NOT NULL,
                    customer_phone TEXT NOT NULL,
                    service_id TEXT NOT NULL DEFAULT 'corte',
                    appointment_date TEXT NOT NULL,
                    appointment_time TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE (appointment_date, appointment_time)
                )
                """.formatted(idColumn));

            if (USE_POSTGRES) {
                statement.execute(
                    "ALTER TABLE appointments ADD COLUMN IF NOT EXISTS "
                        + "service_id TEXT NOT NULL DEFAULT 'corte'"
                );
                statement.execute(
                    "ALTER TABLE appointments ADD COLUMN IF NOT EXISTS "
                        + "service_price_cents BIGINT NOT NULL DEFAULT 0"
                );
                statement.execute(
                    "ALTER TABLE appointments ADD COLUMN IF NOT EXISTS "
                        + "is_paid BOOLEAN NOT NULL DEFAULT FALSE"
                );
                statement.execute(
                    "ALTER TABLE appointments ADD COLUMN IF NOT EXISTS paid_at TEXT"
                );
            } else {
                ensureSQLiteColumn(
                    statement,
                    "service_id",
                    "TEXT NOT NULL DEFAULT 'corte'"
                );
                ensureSQLiteColumn(
                    statement,
                    "service_price_cents",
                    "BIGINT NOT NULL DEFAULT 0"
                );
                ensureSQLiteColumn(
                    statement,
                    "is_paid",
                    "BOOLEAN NOT NULL DEFAULT FALSE"
                );
                ensureSQLiteColumn(statement, "paid_at", "TEXT");
            }

            statement.execute("""
                CREATE TABLE IF NOT EXISTS cancelled_appointments (
                    original_appointment_id BIGINT PRIMARY KEY,
                    customer_name TEXT NOT NULL,
                    customer_phone TEXT NOT NULL,
                    service_id TEXT NOT NULL,
                    appointment_date TEXT NOT NULL,
                    appointment_time TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    service_price_cents BIGINT NOT NULL,
                    is_paid BOOLEAN NOT NULL DEFAULT FALSE,
                    paid_at TEXT,
                    cancellation_reason TEXT NOT NULL,
                    cancelled_at TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS closed_dates (
                    appointment_date TEXT PRIMARY KEY,
                    reason TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_appointments_paid_at "
                    + "ON appointments (paid_at)"
            );
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_cancelled_appointments_paid_at "
                    + "ON cancelled_appointments (paid_at)"
            );
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_cancelled_appointments_date "
                    + "ON cancelled_appointments (appointment_date)"
            );
            backfillServicePrices(connection);
        }
    }

    private static void ensureSQLiteColumn(
            Statement statement,
            String columnName,
            String columnDefinition) throws SQLException {
        boolean found = false;
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(appointments)")) {
            while (columns.next()) {
                if (columnName.equals(columns.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            statement.execute(
                "ALTER TABLE appointments ADD COLUMN " + columnName + " " + columnDefinition
            );
        }
    }

    private static void backfillServicePrices(Connection connection) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE appointments
                SET service_price_cents = ?
                WHERE service_id = ? AND service_price_cents = 0
                """)) {
            for (Map.Entry<String, Service> entry : SERVICES.entrySet()) {
                update.setLong(1, entry.getValue().priceCents());
                update.setString(2, entry.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static Connection openConnection() throws SQLException {
        if (USE_POSTGRES) {
            return openPostgresConnection();
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10_000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH, config.toProperties());
    }

    private static Connection openPostgresConnection() throws SQLException {
        String databaseUrl = DATABASE_URL.trim();
        if (databaseUrl.startsWith("jdbc:postgresql://")) {
            return DriverManager.getConnection(databaseUrl);
        }
        if (!databaseUrl.startsWith("postgresql://")
                && !databaseUrl.startsWith("postgres://")) {
            throw new IllegalStateException(
                "DATABASE_URL must use a PostgreSQL URL starting with "
                    + "postgresql://, postgres://, or jdbc:postgresql://."
            );
        }

        URI uri;
        try {
            uri = URI.create(databaseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DATABASE_URL is not a valid PostgreSQL URL.", exception);
        }
        String userInfo = uri.getRawUserInfo();
        int separator = userInfo == null ? -1 : userInfo.indexOf(':');
        String authority = uri.getRawAuthority();
        int at = authority == null ? -1 : authority.lastIndexOf('@');
        if (separator <= 0 || at < 0 || uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
            throw new IllegalStateException(
                "DATABASE_URL must include a PostgreSQL user, password, host, and database."
            );
        }

        String jdbcUrl = "jdbc:postgresql://" + authority.substring(at + 1) + uri.getRawPath();
        if (uri.getRawQuery() != null) {
            jdbcUrl += "?" + uri.getRawQuery();
        }
        Properties credentials = new Properties();
        credentials.setProperty("user", decodeUrlCredential(userInfo.substring(0, separator)));
        credentials.setProperty(
            "password",
            decodeUrlCredential(userInfo.substring(separator + 1))
        );
        return DriverManager.getConnection(jdbcUrl, credentials);
    }

    private static String decodeUrlCredential(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.startsWith("/api/admin/")) {
            handleAdminRequest(exchange, path, method);
            return;
        }
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

    private static void handleAdminRequest(HttpExchange exchange, String path, String method)
            throws IOException {
        if ("/api/admin/login".equals(path)) {
            if (!"POST".equals(method)) {
                sendJson(exchange, 405, error("Método não permitido."));
                return;
            }
            handleAdminLogin(exchange);
            return;
        }
        if ("/api/admin/session".equals(path)) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, error("Método não permitido."));
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("authenticated", isAdminAuthenticated(exchange));
            sendJson(exchange, 200, response);
            return;
        }
        if ("/api/admin/logout".equals(path)) {
            if (!"POST".equals(method)) {
                sendJson(exchange, 405, error("Método não permitido."));
                return;
            }
            String token = getAdminSessionToken(exchange);
            if (token != null) {
                ADMIN_SESSIONS.remove(token);
            }
            setAdminCookie(exchange, "", 0);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            sendJson(exchange, 200, response);
            return;
        }
        if (!isAdminAuthenticated(exchange)) {
            sendError(exchange, 401, "Faça login para acessar a administração.");
            return;
        }
        if ("/api/admin/report".equals(path)) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, error("Método não permitido."));
                return;
            }
            handleAdminReport(exchange);
            return;
        }
        if ("/api/admin/closures".equals(path)) {
            if (!"POST".equals(method)) {
                sendJson(exchange, 405, error("Método não permitido."));
                return;
            }
            handleCloseDate(exchange);
            return;
        }
        if (path.startsWith("/api/admin/appointments/")) {
            handleAdminAppointmentAction(exchange, path, method);
            return;
        }
        sendError(exchange, 404, "Não encontrado.");
    }

    private static void handleAdminLogin(HttpExchange exchange) throws IOException {
        if (ADMIN_PASSWORD == null || ADMIN_PASSWORD.isBlank()) {
            sendError(exchange, 503, "A administração ainda não foi configurada no servidor.");
            return;
        }
        JsonObject payload = readJsonObject(exchange, "Informe a senha de administração.");
        if (payload == null) {
            return;
        }
        String password = getString(payload, "password");
        if (password == null
                || !MessageDigest.isEqual(
                    ADMIN_PASSWORD.getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8))) {
            sendError(exchange, 401, "Senha incorreta.");
            return;
        }

        Instant now = Instant.now();
        ADMIN_SESSIONS.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        ADMIN_SESSIONS.put(token, now.plus(ADMIN_SESSION_DURATION));
        setAdminCookie(exchange, token, ADMIN_SESSION_DURATION.toSeconds());
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        sendJson(exchange, 200, response);
    }

    private static JsonObject readJsonObject(HttpExchange exchange, String invalidMessage)
            throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            sendError(exchange, 415, "O pedido deve ser enviado em JSON.");
            return null;
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_SIZE + 1);
            if (body.length == 0 || body.length > MAX_REQUEST_SIZE) {
                sendError(exchange, 413, "O pedido está vazio ou é grande demais.");
                return null;
            }
            JsonElement parsed = GSON.fromJson(
                new String(body, StandardCharsets.UTF_8),
                JsonElement.class
            );
            if (parsed == null || !parsed.isJsonObject()) {
                sendError(exchange, 400, invalidMessage);
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            sendError(exchange, 400, invalidMessage);
            return null;
        }
    }

    private static boolean isAdminAuthenticated(HttpExchange exchange) {
        String token = getAdminSessionToken(exchange);
        if (token == null) {
            return false;
        }
        Instant expiresAt = ADMIN_SESSIONS.get(token);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(Instant.now())) {
            ADMIN_SESSIONS.remove(token);
            return false;
        }
        return true;
    }

    private static String getAdminSessionToken(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String cookieHeader : cookies) {
            for (String cookie : cookieHeader.split(";")) {
                String[] parts = cookie.trim().split("=", 2);
                if (parts.length == 2 && ADMIN_COOKIE_NAME.equals(parts[0])) {
                    return parts[1];
                }
            }
        }
        return null;
    }

    private static void setAdminCookie(HttpExchange exchange, String token, long maxAgeSeconds) {
        String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        String protocol = forwardedProto == null
            ? ""
            : forwardedProto.split(",", 2)[0].trim();
        String secure = "https".equalsIgnoreCase(protocol)
            ? "; Secure"
            : "";
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            ADMIN_COOKIE_NAME + "=" + token
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds
                + secure
        );
    }

    private static void handleAdminReport(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        LocalDate from;
        LocalDate to;
        try {
            from = parseReportDate(query.get("from"));
            to = parseReportDate(query.get("to"));
            if (to.isBefore(from)) {
                throw new BookingException("A data final deve ser igual ou posterior à inicial.");
            }
        } catch (BookingException exception) {
            sendError(exchange, 400, exception.getMessage());
            return;
        }

        JsonArray appointments = new JsonArray();
        JsonArray closures = new JsonArray();
        long receivedCents = 0;
        try (Connection connection = openConnection()) {
            try (PreparedStatement queryAppointments = connection.prepareStatement("""
                    SELECT id, customer_name, customer_phone, service_id,
                           appointment_date, appointment_time, service_price_cents,
                           is_paid, paid_at, FALSE AS is_cancelled, '' AS cancellation_reason
                    FROM appointments
                    WHERE appointment_date BETWEEN ? AND ?
                    UNION ALL
                    SELECT original_appointment_id, customer_name, customer_phone, service_id,
                           appointment_date, appointment_time, service_price_cents,
                           is_paid, paid_at, TRUE AS is_cancelled, cancellation_reason
                    FROM cancelled_appointments
                    WHERE appointment_date BETWEEN ? AND ?
                    ORDER BY appointment_date, appointment_time
                    """)) {
                queryAppointments.setString(1, from.toString());
                queryAppointments.setString(2, to.toString());
                queryAppointments.setString(3, from.toString());
                queryAppointments.setString(4, to.toString());
                try (ResultSet result = queryAppointments.executeQuery()) {
                    while (result.next()) {
                        JsonObject appointment = new JsonObject();
                        appointment.addProperty("id", result.getLong("id"));
                        appointment.addProperty("name", result.getString("customer_name"));
                        appointment.addProperty("phone", result.getString("customer_phone"));
                        String serviceId = result.getString("service_id");
                        appointment.addProperty("serviceId", serviceId);
                        appointment.addProperty("service", serviceName(serviceId));
                        appointment.addProperty("date", result.getString("appointment_date"));
                        appointment.addProperty("time", result.getString("appointment_time"));
                        appointment.addProperty(
                            "priceCents",
                            result.getLong("service_price_cents")
                        );
                        appointment.addProperty("paid", result.getBoolean("is_paid"));
                        addNullableProperty(appointment, "paidAt", result.getString("paid_at"));
                        appointment.addProperty("cancelled", result.getBoolean("is_cancelled"));
                        appointment.addProperty(
                            "cancellationReason",
                            result.getString("cancellation_reason")
                        );
                        appointments.add(appointment);
                    }
                }
            }
            try (PreparedStatement queryClosures = connection.prepareStatement("""
                    SELECT appointment_date, reason
                    FROM closed_dates
                    WHERE appointment_date BETWEEN ? AND ?
                    ORDER BY appointment_date
                    """)) {
                queryClosures.setString(1, from.toString());
                queryClosures.setString(2, to.toString());
                try (ResultSet result = queryClosures.executeQuery()) {
                    while (result.next()) {
                        JsonObject closure = new JsonObject();
                        closure.addProperty("date", result.getString("appointment_date"));
                        closure.addProperty("reason", result.getString("reason"));
                        closures.add(closure);
                    }
                }
            }
            try (PreparedStatement queryPayments = connection.prepareStatement("""
                    SELECT service_price_cents
                    FROM appointments
                    WHERE is_paid = TRUE AND paid_at >= ? AND paid_at < ?
                    UNION ALL
                    SELECT service_price_cents
                    FROM cancelled_appointments
                    WHERE is_paid = TRUE AND paid_at >= ? AND paid_at < ?
                    """)) {
                String fromTime = from + "T00:00:00";
                String untilTime = to.plusDays(1) + "T00:00:00";
                queryPayments.setString(1, fromTime);
                queryPayments.setString(2, untilTime);
                queryPayments.setString(3, fromTime);
                queryPayments.setString(4, untilTime);
                try (ResultSet result = queryPayments.executeQuery()) {
                    while (result.next()) {
                        receivedCents += result.getLong("service_price_cents");
                    }
                }
            }
        } catch (SQLException exception) {
            exception.printStackTrace(System.err);
            sendError(exchange, 500, "Não foi possível gerar o relatório.");
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("from", from.toString());
        response.addProperty("to", to.toString());
        response.addProperty("receivedCents", receivedCents);
        response.add("appointments", appointments);
        response.add("closures", closures);
        sendJson(exchange, 200, response);
    }

    private static void handleAdminAppointmentAction(
            HttpExchange exchange,
            String path,
            String method) throws IOException {
        if (!"POST".equals(method)) {
            sendJson(exchange, 405, error("Método não permitido."));
            return;
        }
        String[] parts = path.substring("/api/admin/appointments/".length()).split("/", -1);
        if (parts.length != 2 || parts[0].isEmpty()) {
            sendError(exchange, 404, "Não encontrado.");
            return;
        }
        long appointmentId;
        try {
            appointmentId = Long.parseLong(parts[0]);
            if (appointmentId < 1) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            sendError(exchange, 400, "Agendamento inválido.");
            return;
        }

        try {
            boolean updated;
            if ("paid".equals(parts[1])) {
                JsonObject payload = readJsonObject(exchange, "Informe se o pagamento foi recebido.");
                if (payload == null) {
                    return;
                }
                JsonElement paidValue = payload.get("paid");
                if (paidValue == null || !paidValue.isJsonPrimitive()
                        || !paidValue.getAsJsonPrimitive().isBoolean()) {
                    sendError(exchange, 400, "Informe se o pagamento foi recebido.");
                    return;
                }
                updated = updateAppointmentPayment(appointmentId, paidValue.getAsBoolean());
            } else if ("cancel".equals(parts[1])) {
                JsonObject payload = readJsonObject(exchange, "Os dados de cancelamento são inválidos.");
                if (payload == null) {
                    return;
                }
                String reason = getString(payload, "reason");
                if (reason == null || reason.isBlank()) {
                    reason = "Cancelado pela administração";
                }
                reason = reason.trim().replaceAll("\\s+", " ");
                if (reason.length() > 160) {
                    sendError(exchange, 400, "O motivo deve ter até 160 caracteres.");
                    return;
                }
                updated = cancelAppointment(appointmentId, reason);
            } else {
                sendError(exchange, 404, "Não encontrado.");
                return;
            }
            if (!updated) {
                sendError(exchange, 404, "O agendamento não está mais ativo.");
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            sendJson(exchange, 200, response);
        } catch (SQLException exception) {
            exception.printStackTrace(System.err);
            sendError(exchange, 500, "Não foi possível atualizar o agendamento.");
        }
    }

    private static boolean updateAppointmentPayment(long appointmentId, boolean paid)
            throws SQLException {
        try (Connection connection = openConnection()) {
            LocalDate appointmentDate = findAppointmentDate(connection, appointmentId);
            if (appointmentDate == null) {
                return false;
            }
            beginTransaction(connection, appointmentDate);
            try {
                String paidAt = paid ? LocalDateTime.now(LOCAL_ZONE).toString() : null;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE appointments SET is_paid = ?, paid_at = ? WHERE id = ?
                        """)) {
                    update.setBoolean(1, paid);
                    if (paidAt == null) {
                        update.setNull(2, Types.VARCHAR);
                    } else {
                        update.setString(2, paidAt);
                    }
                    update.setLong(3, appointmentId);
                    if (update.executeUpdate() == 0) {
                        rollbackTransaction(connection);
                        return false;
                    }
                }
                commitTransaction(connection);
                return true;
            } catch (SQLException | RuntimeException exception) {
                rollbackAfterFailure(connection, exception);
                throw exception;
            }
        }
    }

    private static boolean cancelAppointment(long appointmentId, String reason)
            throws SQLException {
        try (Connection connection = openConnection()) {
            LocalDate appointmentDate = findAppointmentDate(connection, appointmentId);
            if (appointmentDate == null) {
                return false;
            }
            beginTransaction(connection, appointmentDate);
            try {
                boolean archived = archiveAppointment(connection, appointmentId, reason);
                if (!archived) {
                    rollbackTransaction(connection);
                    return false;
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM appointments WHERE id = ?")) {
                    delete.setLong(1, appointmentId);
                    delete.executeUpdate();
                }
                commitTransaction(connection);
                return true;
            } catch (SQLException | RuntimeException exception) {
                rollbackAfterFailure(connection, exception);
                throw exception;
            }
        }
    }

    private static void handleCloseDate(HttpExchange exchange) throws IOException {
        JsonObject payload = readJsonObject(exchange, "Informe uma data e um motivo.");
        if (payload == null) {
            return;
        }
        LocalDate date;
        try {
            date = parseReportDate(getString(payload, "date"));
        } catch (BookingException exception) {
            sendError(exchange, 400, exception.getMessage());
            return;
        }
        String reason = getString(payload, "reason");
        if (reason == null || reason.isBlank()) {
            sendError(exchange, 400, "Informe o motivo do fechamento.");
            return;
        }
        reason = reason.trim().replaceAll("\\s+", " ");
        if (reason.length() > 160) {
            sendError(exchange, 400, "O motivo deve ter até 160 caracteres.");
            return;
        }
        try {
            if (!closeDate(date, reason)) {
                sendError(exchange, 409, "Essa data já está fechada.");
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            sendJson(exchange, 200, response);
        } catch (SQLException exception) {
            exception.printStackTrace(System.err);
            sendError(exchange, 500, "Não foi possível fechar a agenda.");
        }
    }

    private static boolean closeDate(LocalDate date, String reason) throws SQLException {
        try (Connection connection = openConnection()) {
            beginTransaction(connection, date);
            try {
                if (isDateClosed(connection, date)) {
                    rollbackTransaction(connection);
                    return false;
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO closed_dates (appointment_date, reason, created_at)
                        VALUES (?, ?, ?)
                        """)) {
                    insert.setString(1, date.toString());
                    insert.setString(2, reason);
                    insert.setString(3, LocalDateTime.now(LOCAL_ZONE).toString());
                    insert.executeUpdate();
                }
                archiveAppointmentsForDate(connection, date, reason);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM appointments WHERE appointment_date = ?")) {
                    delete.setString(1, date.toString());
                    delete.executeUpdate();
                }
                commitTransaction(connection);
                return true;
            } catch (SQLException | RuntimeException exception) {
                rollbackAfterFailure(connection, exception);
                throw exception;
            }
        }
    }

    private static String archiveSql(String whereClause) {
        return """
            INSERT INTO cancelled_appointments (
                original_appointment_id, customer_name, customer_phone, service_id,
                appointment_date, appointment_time, created_at, service_price_cents,
                is_paid, paid_at, cancellation_reason, cancelled_at
            )
            SELECT id, customer_name, customer_phone, service_id,
                   appointment_date, appointment_time, created_at, service_price_cents,
                   is_paid, paid_at, ?, ?
            FROM appointments
            """ + whereClause;
    }

    private static boolean archiveAppointment(
            Connection connection,
            long appointmentId,
            String reason) throws SQLException {
        try (PreparedStatement archive = connection.prepareStatement(archiveSql("WHERE id = ?"))) {
            archive.setString(1, reason);
            archive.setString(2, LocalDateTime.now(LOCAL_ZONE).toString());
            archive.setLong(3, appointmentId);
            return archive.executeUpdate() == 1;
        }
    }

    private static void archiveAppointmentsForDate(
            Connection connection,
            LocalDate date,
            String reason) throws SQLException {
        try (PreparedStatement archive = connection.prepareStatement(
                archiveSql("WHERE appointment_date = ?"))) {
            archive.setString(1, reason);
            archive.setString(2, LocalDateTime.now(LOCAL_ZONE).toString());
            archive.setString(3, date.toString());
            archive.executeUpdate();
        }
    }

    private static LocalDate findAppointmentDate(Connection connection, long appointmentId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT appointment_date FROM appointments WHERE id = ?")) {
            query.setLong(1, appointmentId);
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                    ? LocalDate.parse(result.getString("appointment_date"))
                    : null;
            }
        }
    }

    private static void rollbackAfterFailure(Connection connection, Exception exception) {
        try {
            rollbackTransaction(connection);
        } catch (SQLException rollbackException) {
            exception.addSuppressed(rollbackException);
        }
    }

    private static void addNullableProperty(JsonObject object, String key, String value) {
        if (value == null) {
            object.add(key, null);
        } else {
            object.addProperty(key, value);
        }
    }

    private static String serviceName(String serviceId) {
        Service service = SERVICES.get(serviceId);
        return service == null ? serviceId : service.name();
    }

    private static LocalDate parseReportDate(String value) throws BookingException {
        if (value == null) {
            throw new BookingException("Selecione uma data válida.");
        }
        try {
            LocalDate date = LocalDate.parse(value);
            if (!date.toString().equals(value)) {
                throw new BookingException("Selecione uma data válida.");
            }
            if (date.isAfter(LocalDate.of(2030, 12, 31))) {
                throw new BookingException("A data deve ser até 31/12/2030.");
            }
            return date;
        } catch (DateTimeParseException exception) {
            throw new BookingException("Selecione uma data válida.");
        }
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
            if (!reserveSlot(
                    name,
                    phone,
                    serviceId,
                    SERVICES.get(serviceId).priceCents(),
                    selectedDate,
                    selectedTime)) {
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
        String whatsappUrl = buildWhatsAppUrl(name, serviceId, selectedDate, selectedTime);
        if (whatsappUrl == null) {
            response.addProperty(
                "whatsappMessage",
                "Agendamento confirmado, mas o WhatsApp da barbearia não está configurado."
            );
        } else {
            response.addProperty("whatsappUrl", whatsappUrl);
        }
        sendJson(exchange, 201, response);
    }

    private static String buildWhatsAppUrl(
            String name,
            String serviceId,
            LocalDate selectedDate,
            String selectedTime) {
        if (WHATSAPP_RECIPIENT == null || WHATSAPP_RECIPIENT.isBlank()) {
            return null;
        }

        String recipient = WHATSAPP_RECIPIENT.replaceAll("\\D", "");
        if (recipient.startsWith("00")) {
            recipient = recipient.substring(2);
        }
        if (recipient.length() == 10 || recipient.length() == 11) {
            recipient = "55" + recipient;
        }
        if (!recipient.startsWith("55") || recipient.length() < 12 || recipient.length() > 13) {
            return null;
        }

        Service service = SERVICES.get(serviceId);
        String message = "Olá! Acabei de confirmar um agendamento na ZMB dos Cortes."
            + "\nNome: " + name
            + "\nServiço: " + service.name()
            + "\nValor: " + formatPrice(service.priceCents())
            + "\nData: " + selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            + "\nHorário: " + selectedTime;
        return "https://wa.me/" + recipient + "?text="
            + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    private static boolean reserveSlot(
            String name,
            String phone,
            String serviceId,
            long servicePriceCents,
            LocalDate selectedDate,
            String selectedTime) throws SQLException {
        try (Connection connection = openConnection()) {
            beginTransaction(connection, selectedDate);
            try {
                if (isDateClosed(connection, selectedDate)) {
                    rollbackTransaction(connection);
                    return false;
                }
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT 1 FROM appointments
                        WHERE appointment_date = ? AND appointment_time = ?
                        LIMIT 1
                        """)) {
                    query.setString(1, selectedDate.toString());
                    query.setString(2, selectedTime);
                    try (ResultSet result = query.executeQuery()) {
                        if (result.next()) {
                            rollbackTransaction(connection);
                            return false;
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO appointments (
                            customer_name, customer_phone, service_id,
                            appointment_date, appointment_time, created_at,
                            service_price_cents, is_paid
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE)
                        """)) {
                    insert.setString(1, name);
                    insert.setString(2, phone);
                    insert.setString(3, serviceId);
                    insert.setString(4, selectedDate.toString());
                    insert.setString(5, selectedTime);
                    insert.setString(6, LocalDateTime.now(LOCAL_ZONE).toString());
                    insert.setLong(7, servicePriceCents);
                    insert.executeUpdate();
                }
                commitTransaction(connection);
                return true;
            } catch (SQLException | RuntimeException exception) {
                try {
                    rollbackTransaction(connection);
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    private static void beginTransaction(Connection connection, LocalDate selectedDate)
            throws SQLException {
        if (USE_POSTGRES) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, selectedDate.toEpochDay());
                lock.execute();
            }
        } else {
            try (Statement transaction = connection.createStatement()) {
                transaction.execute("BEGIN IMMEDIATE");
            }
        }
    }

    private static boolean isDateClosed(Connection connection, LocalDate selectedDate)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM closed_dates WHERE appointment_date = ?")) {
            query.setString(1, selectedDate.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void commitTransaction(Connection connection) throws SQLException {
        if (USE_POSTGRES) {
            connection.commit();
        } else {
            try (Statement transaction = connection.createStatement()) {
                transaction.execute("COMMIT");
            }
        }
    }

    private static void rollbackTransaction(Connection connection) throws SQLException {
        if (USE_POSTGRES) {
            connection.rollback();
        } else {
            try (Statement transaction = connection.createStatement()) {
                transaction.execute("ROLLBACK");
            }
        }
    }

    private static List<String> getBookedTimes(LocalDate selectedDate) throws SQLException {
        try (Connection connection = openConnection()) {
            if (isDateClosed(connection, selectedDate)) {
                return new ArrayList<>(ALLOWED_SLOTS);
            }
        }
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
        exchange.getResponseHeaders().set(
            "Content-Security-Policy",
            "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
                + "form-action 'self'"
        );
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
        if (selected.isAfter(LocalDate.of(2030, 12, 31))) {
            throw new BookingException("A agenda está aberta até 31/12/2030.");
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
        return "23505".equals(exception.getSQLState())
            || exception.getErrorCode() == 19
            || (exception.getMessage() != null
                && exception.getMessage().contains("UNIQUE constraint failed"));
    }

    private static String formatPrice(long priceCents) {
        return "R$ %d,%02d".formatted(priceCents / 100, priceCents % 100);
    }

    private record Service(String name, long priceCents) {
    }

    private record StaticFile(String path, String contentType) {
    }

    private static final class BookingException extends Exception {
        private BookingException(String message) {
            super(message);
        }
    }
}
