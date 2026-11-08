package com.mimavault.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mimavault.config.AppConfig;
import com.mimavault.model.Entry;
import com.mimavault.service.PasswordService;
import com.mimavault.util.ClipboardSafe;
import com.mimavault.util.PasswordGenerator;
import com.mimavault.util.TotpUtil;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKey;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * MimaVault 本地 HTTP API：让 DSH 等本机进程能检索密码库。
 *
 * <h2>安全模型（逐条对应设计决定）</h2>
 * <ol>
 *   <li><b>默认不存在</b>：{@code AppConfig.localApiEnabled} 默认 false，只有用户在托盘菜单里
 *       显式打开才会启动监听。</li>
 *   <li><b>只监听回环</b>：绑定 {@link InetAddress#getLoopbackAddress()} + 端口 0（系统分配随机端口），
 *       不对外网暴露。</li>
 *   <li><b>Bearer token</b>：每次启动生成 256 位随机 token，写入 {@code %APPDATA%\MimaVault\api.json}，
 *       并把该文件 ACL 收紧为仅当前用户可读。</li>
 *   <li><b>拒绝浏览器来源</b>：带 {@code Origin} 或 {@code Referer} 的请求一律 403，
 *       防止网页对本机端口发起 CSRF。</li>
 *   <li><b>仅解锁时可用</b>：会话密钥为空（未登录/已锁定/已登出）时所有数据接口返回 401 locked。
 *       主程序 3 分钟空闲自动登出，会连带关掉 API。</li>
 *   <li><b>明文不出库</b>：{@link ApiEntryView} 是唯一出口白名单，密码/备注/otpauth 都不外泄；
 *       需要明文时由本进程自己写剪贴板（复用 {@link ClipboardSafe}，30 秒自动清除）。</li>
 *   <li><b>审计</b>：每次访问记一条不含敏感值的审计日志到 {@code %APPDATA%\MimaVault\api-audit.log}。</li>
 * </ol>
 *
 * <p><b>边界声明</b>：token 文件对本机同一用户下的任何进程可读，因此上述防护挡的是
 * "浏览器误打 / 别的用户 / 意外暴露"，<b>挡不住本机同用户的恶意程序</b>。真正的隔离边界是
 * Windows 用户账户。
 *
 * @since 8.35.5
 */
public final class LocalApiServer {

    /** 应用标识，写入描述文件供调用方校验 */
    private static final String APP_NAME = "MimaVault";

    /** 请求体上限：本 API 只有几个小 JSON，超过即视为异常 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    /** 复制到剪贴板后自动清除的秒数，与 {@link ClipboardSafe} 内部常量保持一致 */
    private static final int CLIPBOARD_CLEAR_SECONDS = 30;

    /** 代理调用取值时必带的意图头，避免其它本地程序误用明文端点 */
    private static final String PROXY_PURPOSE_HEADER = "X-MimaVault-Purpose";
    private static final String PROXY_PURPOSE_VALUE = "proxy";

    /** 走剪贴板自动清除的字段（值即凭据） */
    private static final List<String> SECRET_COPY_FIELDS = Arrays.asList(
            "password", "note", "api_key", "bank_cvv", "bank_card_no", "id_number");

    /** 标准明文字段（非密钥，复制后不自动清除） */
    private static final List<String> PLAIN_COPY_FIELDS = Arrays.asList(
            "platform", "account", "email", "phone");

    private static final DateTimeFormatter AUDIT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final LocalApiServer INSTANCE = new LocalApiServer();

    private final Gson gson = new Gson();

    private HttpServer server;
    private ExecutorService executor;
    private String token;
    private PasswordService service;

    /** 当前会话密钥；null 表示未解锁。volatile 保证跨线程可见性 */
    private volatile SecretKey sessionKey;

    private LocalApiServer() {
    }

    /** 全局单例：主界面与托盘菜单都通过它开关 */
    public static LocalApiServer get() {
        return INSTANCE;
    }

    // ==================== 生命周期 ====================

    /** 服务是否正在监听 */
    public synchronized boolean isRunning() {
        return server != null;
    }

    /** 当前监听端口；未运行返回 -1 */
    public synchronized int getPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /**
     * 解锁并（必要时）启动服务。登录成功、或用户打开开关时调用。
     * 重复调用只更新会话密钥，不会重复绑定端口。
     *
     * @param service 密码业务服务
     * @param key     本次会话的 AES 密钥
     */
    public synchronized void unlock(PasswordService service, SecretKey key) {
        if (service == null || key == null) {
            return;
        }
        this.service = service;
        this.sessionKey = key;
        if (server == null) {
            try {
                startLocked();
            } catch (Exception e) {
                System.err.println("本地 API 启动失败（不影响主程序）: " + e.getMessage());
            }
        }
    }

    /**
     * 清空会话密钥并停止监听。锁定、自动登出、退出程序时调用。
     * 停止后描述文件会被删除，调用方据此判断"密匣没开"。
     */
    public synchronized void lock() {
        this.sessionKey = null;
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception ignored) {
                // 停止失败不应阻塞退出
            }
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        deleteDescriptor();
    }

    private void startLocked() throws IOException {
        this.token = randomHex(32);

        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", this::handleSafely);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "mimavault-api");
            t.setDaemon(true); // 守护线程：绝不阻止 JVM 退出
            return t;
        };
        executor = Executors.newFixedThreadPool(2, tf);
        s.setExecutor(executor);
        s.start();
        this.server = s;

        writeDescriptor();
    }

    // ==================== 描述文件 ====================

    /** 端口/token 描述文件：与数据目录定位文件同目录（%APPDATA%\MimaVault\） */
    private static Path descriptorPath() {
        Path locator = AppConfig.LOCATOR_FILE;
        Path dir = locator.getParent();
        return dir == null ? locator : dir.resolve("api.json");
    }

    private static Path auditPath() {
        Path locator = AppConfig.LOCATOR_FILE;
        Path dir = locator.getParent();
        return dir == null ? locator : dir.resolve("api-audit.log");
    }

    private void writeDescriptor() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("schema", 1);
        d.put("app", APP_NAME);
        d.put("port", getPort());
        d.put("token", token);
        d.put("pid", ProcessHandle.current().pid());
        d.put("startedAt", Instant.now().toEpochMilli());
        Path target = descriptorPath();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(d), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            restrictToOwner(target);
        } catch (Exception e) {
            System.err.println("本地 API 描述文件写入失败: " + e.getMessage());
        }
    }

    /**
     * 把描述文件的 ACL 收紧为"仅当前用户"：清空继承项，只留所有者完全控制。
     * 非 Windows / 无 ACL 视图时静默跳过（此时依赖 %APPDATA% 本身的用户隔离）。
     */
    private static void restrictToOwner(Path file) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (view == null) {
                return;
            }
            UserPrincipal owner = view.getOwner();
            if (owner == null) {
                return;
            }
            AclEntry onlyOwner = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            view.setAcl(java.util.Collections.singletonList(onlyOwner));
        } catch (Exception ignored) {
            // 权限收紧失败不影响功能；%APPDATA% 本身已按用户隔离
        }
    }

    private void deleteDescriptor() {
        try {
            Files.deleteIfExists(descriptorPath());
        } catch (Exception ignored) {
            // 删不掉时调用方仍可从 pid 判断进程已退出
        }
    }

    // ==================== 审计 ====================

    private void audit(String action, long entryId, String field, String result) {
        try {
            Path p = auditPath();
            Files.createDirectories(p.getParent());
            if (Files.exists(p) && Files.size(p) > 1024 * 1024) {
                Files.writeString(p, "", StandardCharsets.UTF_8); // 超 1MB 直接截断，避免无限增长
            }
            StringBuilder sb = new StringBuilder()
                    .append(AUDIT_TIME.format(Instant.now()))
                    .append('\t').append(action);
            if (entryId > 0) {
                sb.append("\tid=").append(entryId);
            }
            if (field != null && !field.isEmpty()) {
                sb.append("\tfield=").append(field);
            }
            sb.append('\t').append(result).append(System.lineSeparator());
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 审计写失败绝不影响主流程
        }
    }

    // ==================== 请求入口 ====================

    private void handleSafely(HttpExchange ex) {
        try {
            handle(ex);
        } catch (Exception e) {
            try {
                send(ex, 500, err("internal", e.getMessage() == null ? "内部错误" : e.getMessage()));
            } catch (Exception ignored) {
                // 响应已发出
            }
        } finally {
            try {
                ex.close();
            } catch (Exception ignored) {
                // 关闭失败无影响
            }
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        // 1) 拒绝一切浏览器来源：网页对本机端口的请求必然带 Origin（或至少 Referer）
        Headers req = ex.getRequestHeaders();
        if (req.containsKey("Origin") || req.containsKey("Referer")) {
            send(ex, 403, err("forbidden_origin", "拒绝来自浏览器的请求"));
            return;
        }

        // 2) Bearer token 认证（常量时间比较）
        if (!authorized(req.getFirst("Authorization"))) {
            send(ex, 401, err("unauthorized", "缺少或错误的 Authorization: Bearer <token>"));
            return;
        }

        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);

        // 3) 健康检查：锁定时也可用，用于区分"没开" 与 "锁着"
        if ("/v1/health".equals(path) && "GET".equals(method)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("app", APP_NAME);
            body.put("locked", sessionKey == null);
            body.put("port", getPort());
            send(ex, 200, body);
            return;
        }

        // 4) 其余接口一律要求已解锁
        PasswordService svc = this.service;
        SecretKey key = this.sessionKey;
        if (svc == null || key == null) {
            send(ex, 401, err("locked", "密匣当前未解锁，请先打开并登录"));
            return;
        }

        List<String> parts = splitPath(path);
        if (parts.size() < 2 || !"v1".equals(parts.get(0))) {
            send(ex, 404, err("not_found", "未知接口"));
            return;
        }

        try {
            route(ex, method, parts, svc, key);
        } catch (BadRequest e) {
            send(ex, 400, err("bad_request", e.getMessage()));
        }
    }

    private void route(HttpExchange ex, String method, List<String> parts,
                       PasswordService svc, SecretKey key) throws IOException {
        String res = parts.get(1);

        if ("search".equals(res) && parts.size() == 2 && "GET".equals(method)) {
            handleSearch(ex, svc, key);
            return;
        }
        if ("generate".equals(res) && parts.size() == 2 && "POST".equals(method)) {
            handleGenerate(ex);
            return;
        }
        if ("entries".equals(res) && parts.size() >= 3) {
            long id = parseId(parts.get(2));
            if (parts.size() == 3 && "GET".equals(method)) {
                handleEntry(ex, svc, key, id);
                return;
            }
            if (parts.size() == 4) {
                String action = parts.get(3);
                if ("copy".equals(action) && "POST".equals(method)) {
                    handleCopy(ex, svc, key, id);
                    return;
                }
                if ("totp".equals(action) && "GET".equals(method)) {
                    handleTotp(ex, svc, key, id);
                    return;
                }
                if ("secret".equals(action) && "POST".equals(method)) {
                    handleSecret(ex, svc, key, id);
                    return;
                }
            }
        }
        send(ex, 404, err("not_found", "未知接口"));
    }

    // ==================== 接口实现 ====================

    private void handleSearch(HttpExchange ex, PasswordService svc, SecretKey key) throws IOException {
        Map<String, String> q = queryParams(ex.getRequestURI());
        String keyword = q.getOrDefault("q", "");
        String category = q.getOrDefault("category", "");
        int limit = intParam(q.get("limit"), DEFAULT_LIMIT, 1, MAX_LIMIT);

        // includeNote=true：备注内容参与命中，但不返回内容（用户已确认的口径）
        List<Entry> hits = svc.search(keyword, category, key, true);
        int total = hits.size();
        List<Entry> page = total > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", keyword);
        body.put("category", category == null ? "" : category);
        body.put("total", total);
        body.put("returned", page.size());
        body.put("limit", limit);
        body.put("items", ApiEntryView.ofAll(page, key));
        audit("search", 0, null, "total=" + total);
        send(ex, 200, body);
    }

    private void handleEntry(HttpExchange ex, PasswordService svc, SecretKey key, long id) throws IOException {
        Entry e = svc.getById(id);
        if (e == null) {
            send(ex, 404, err("not_found", "条目不存在或已删除"));
            return;
        }
        audit("entry", id, null, "ok");
        send(ex, 200, ApiEntryView.of(e, key));
    }

    private void handleCopy(HttpExchange ex, PasswordService svc, SecretKey key, long id) throws IOException {
        Entry e = svc.getById(id);
        if (e == null) {
            send(ex, 404, err("not_found", "条目不存在或已删除"));
            return;
        }
        String field = readField(ex);
        String value = resolveField(e, key, field);
        if (value == null || value.isEmpty()) {
            audit("copy", id, field, "empty");
            send(ex, 422, err("empty_field", "该字段为空"));
            return;
        }
        boolean secret = SECRET_COPY_FIELDS.contains(field) || ApiEntryView.isSecretExtraKey(field);
        try {
            writeClipboard(value, secret);
        } catch (HeadlessException he) {
            audit("copy", id, field, "clipboard_unavailable");
            send(ex, 503, err("clipboard_unavailable", "当前环境没有可用剪贴板"));
            return;
        }
        audit("copy", id, field, secret ? "copied_secret" : "copied_plain");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("copied", true);
        body.put("field", field);
        body.put("clearsInSeconds", secret ? CLIPBOARD_CLEAR_SECONDS : 0);
        send(ex, 200, body);
    }

    private void handleTotp(HttpExchange ex, PasswordService svc, SecretKey key, long id) throws IOException {
        Entry e = svc.getById(id);
        if (e == null) {
            send(ex, 404, err("not_found", "条目不存在或已删除"));
            return;
        }
        String uri = svc.decryptTotp(e, key);
        if (uri == null || uri.isEmpty()) {
            send(ex, 422, err("no_totp", "该条目未绑定动态验证码"));
            return;
        }
        TotpUtil.Config cfg;
        try {
            cfg = TotpUtil.parse(uri);
        } catch (Exception pe) {
            audit("totp", id, null, "bad_secret");
            send(ex, 422, err("bad_totp", "动态验证码密钥无法解析"));
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", TotpUtil.generate(cfg, now));
        body.put("remainingSeconds", TotpUtil.remainingSeconds(cfg.period, now));
        body.put("period", cfg.period);
        body.put("digits", cfg.digits);
        audit("totp", id, null, "ok");
        send(ex, 200, body);
    }

    /**
     * 取明文值，供插件做"代理调用"时注入请求头。
     *
     * <p>这是唯一会把明文返回给调用方的端点。要求带 {@value #PROXY_PURPOSE_HEADER}: {@value #PROXY_PURPOSE_VALUE}，
     * 并且只允许取密钥类字段 —— 目的是让普通客户端不会顺手拿明文，也便于审计区分。
     *
     * <p>插件侧约定：调用方拿到值后只能用于注入出站请求，<b>绝不可回传给模型</b>。
     */
    private void handleSecret(HttpExchange ex, PasswordService svc, SecretKey key, long id) throws IOException {
        String purpose = ex.getRequestHeaders().getFirst(PROXY_PURPOSE_HEADER);
        if (!PROXY_PURPOSE_VALUE.equals(purpose)) {
            send(ex, 403, err("forbidden_purpose",
                    "明文端点仅供代理调用：需带 " + PROXY_PURPOSE_HEADER + ": " + PROXY_PURPOSE_VALUE));
            return;
        }
        Entry e = svc.getById(id);
        if (e == null) {
            send(ex, 404, err("not_found", "条目不存在或已删除"));
            return;
        }
        String field = readField(ex);
        boolean allowed = "password".equals(field) || ApiEntryView.isSecretExtraKey(field);
        if (!allowed) {
            send(ex, 403, err("forbidden_field", "该字段不允许通过明文端点读取: " + field));
            return;
        }
        String value = resolveField(e, key, field);
        if (value == null || value.isEmpty()) {
            audit("secret", id, field, "empty");
            send(ex, 422, err("empty_field", "该字段为空"));
            return;
        }
        audit("secret", id, field, "released_for_proxy");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("field", field);
        body.put("value", value);
        send(ex, 200, body);
    }

    private void handleGenerate(HttpExchange ex) throws IOException {
        JsonObject in = readJsonObject(ex);
        PasswordGenerator.Options opt = new PasswordGenerator.Options();
        opt.length = clamp(optInt(in, "length", opt.length), 6, 128);
        opt.upper = optBool(in, "upper", opt.upper);
        opt.lower = optBool(in, "lower", opt.lower);
        opt.digits = optBool(in, "digits", opt.digits);
        opt.symbols = optBool(in, "symbols", opt.symbols);
        opt.excludeAmbiguous = optBool(in, "excludeAmbiguous", opt.excludeAmbiguous);
        boolean copy = optBool(in, "copy", false);

        String pwd = PasswordGenerator.generate(opt);
        boolean copied = false;
        if (copy) {
            try {
                writeClipboard(pwd, true);
                copied = true;
            } catch (HeadlessException he) {
                send(ex, 503, err("clipboard_unavailable", "当前环境没有可用剪贴板"));
                return;
            }
        }
        audit("generate", 0, null, "len=" + opt.length + " copied=" + copied);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", pwd);
        body.put("length", pwd.length());
        body.put("copied", copied);
        body.put("clearsInSeconds", copied ? CLIPBOARD_CLEAR_SECONDS : 0);
        send(ex, 200, body);
    }

    // ==================== 字段取值 ====================

    /**
     * 按字段名取明文。字段名可以是标准字段（password/note/platform/account/email/phone），
     * 也可以是分类专属字段键名（api_key/api_endpoint/...）。
     */
    private String resolveField(Entry e, SecretKey key, String field) {
        switch (field) {
            case "password":
                return service.decryptPassword(e, key);
            case "note":
                return e.getNote() == null ? "" : e.getNote();
            case "platform":
                return e.getPlatform() == null ? "" : e.getPlatform();
            case "account":
                return e.getAccount() == null ? "" : e.getAccount();
            case "email":
                return e.getEmail() == null ? "" : e.getEmail();
            case "phone":
                return e.getPhone() == null ? "" : e.getPhone();
            default:
                return ApiEntryView.extraOf(e, key).getOrDefault(field, "");
        }
    }

    // ==================== 工具 ====================

    private boolean authorized(String header) {
        if (header == null || token == null) {
            return false;
        }
        if (!header.startsWith("Bearer ")) {
            return false;
        }
        String provided = header.substring("Bearer ".length()).trim();
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String readField(HttpExchange ex) throws IOException {
        JsonObject in = readJsonObject(ex);
        if (!in.has("field") || in.get("field").isJsonNull()) {
            throw new BadRequest("缺少 field");
        }
        String field = in.get("field").getAsString();
        if (field == null || field.trim().isEmpty()) {
            throw new BadRequest("field 不能为空");
        }
        return field.trim();
    }

    private static JsonObject readJsonObject(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length > MAX_BODY_BYTES) {
            throw new BadRequest("请求体过大");
        }
        if (raw.length == 0) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new BadRequest("请求体不是合法 JSON 对象");
        }
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new BadRequest("条目 id 必须是整数");
        }
    }

    private static List<String> splitPath(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int i = pair.indexOf('=');
            try {
                String k = i < 0 ? pair : pair.substring(0, i);
                String v = i < 0 ? "" : pair.substring(i + 1);
                out.put(java.net.URLDecoder.decode(k, "UTF-8"),
                        java.net.URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
                // 单个参数解码失败就跳过
            }
        }
        return out;
    }

    private static int intParam(String s, int def, int min, int max) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return clamp(Integer.parseInt(s.trim()), min, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int optInt(JsonObject o, String k, int def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean optBool(JsonObject o, String k, boolean def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte v : b) {
            sb.append(String.format("%02x", v));
        }
        return sb.toString();
    }

    /**
     * 写系统剪贴板。Swing 的 {@code Timer} 必须在 EDT 上创建，所以统一切到 EDT 执行；
     * 无图形环境时抛 {@link HeadlessException} 由调用方转成 503。
     */
    private static void writeClipboard(String text, boolean secret) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new HeadlessException();
        }
        Runnable task = () -> {
            if (secret) {
                ClipboardSafe.copySecret(text);
            } else {
                ClipboardSafe.copy(text);
            }
        };
        if (EventQueue.isDispatchThread()) {
            task.run();
        } else {
            try {
                EventQueue.invokeAndWait(task);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("写剪贴板被中断");
            } catch (Exception e) {
                throw new IllegalStateException("写剪贴板失败: " + e.getMessage(), e);
            }
        }
    }

    private void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        // 刻意不下发任何 CORS 头：浏览器既发不进来也读不到
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, Object> err(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message == null ? "" : message);
        return m;
    }

    /** 请求参数不合法 */
    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }
}
