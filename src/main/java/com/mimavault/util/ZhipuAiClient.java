package com.mimavault.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mimavault.config.AppConfig;
import com.mimavault.model.Entry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 智谱 AI 辅助识别客户端（GLM-4V-Flash，免费）
 * <p>
 * 把整张截图 / 整段文本交给多模态模型，一次性提取多条账号密码记录，
 * 返回结构化 JSON 数组：[{platform, account, password, phone, email, note}, ...]。
 * 用于补足本地 OCR + 规则解析对密集排版、口语化平台名、纯密钥行的识别短板。
 * <p>
 * API Key 存于 data/config.json 的 zhipuApiKey，未配置时调用方应引导用户填写。
 */
public final class ZhipuAiClient {

    /** 智谱开放平台 v4 接口 */
    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String MODEL = "glm-4v-flash";

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** 提取提示词：要求模型只输出 JSON 数组，字段与数据库一一对应 */
    private static final String SYSTEM_PROMPT = "你是账号密码笔记整理助手。用户会提供一张截图或一段文字，内容是账号密码记录。"
            + "请逐条提取所有记录，只输出一个 JSON 数组，不要任何解释、不要 Markdown 围栏。"
            + "每个元素必须是 JSON 对象，字段：platform(平台名称，用原文如'mc仪表盘')、"
            + "account(账号/用户名/学号/邮箱/手机号)、password(密码)、phone(手机号)、"
            + "email(邮箱)、note(备注/补充信息)。规则："
            + "1.同一平台有多个账号时分别列出多条；"
            + "2.识别不出或没有的字段填空字符串\"\"；"
            + "3.只有密钥/口令没有账号的，也作为一条记录，platform 用原文标签或'密钥'，account 留空，password 填该密钥；"
            + "4.密码中的特殊字符（如@、/、?）保持原样；"
            + "5.平台是网址时保留网址，如 github.com 写 github。";

    private ZhipuAiClient() {
    }

    /** 识别单张图片，返回草稿记录列表 */
    public static List<TextBatchParser.RawRecord> recognizeImage(Path image) throws Exception {
        if (image == null || !Files.exists(image)) {
            throw new IOException("图片文件不存在：" + image);
        }
        byte[] bytes = Files.readAllBytes(image);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String dataUrl = "data:image/" + extOf(image) + ";base64," + base64;

        JsonObject imageContent = new JsonObject();
        imageContent.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        imageContent.add("image_url", imageUrl);

        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", "请提取图中所有账号密码记录，输出 JSON 数组。");

        JsonArray content = new JsonArray();
        content.add(textContent);
        content.add(imageContent);

        return call(content, "AI识别图片");
    }

    /** 识别整段文本，返回草稿记录列表 */
    public static List<TextBatchParser.RawRecord> recognizeText(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("文本内容为空");
        }
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", "以下是一段账号密码记录，请提取为 JSON 数组：\n" + text);
        JsonArray content = new JsonArray();
        content.add(textContent);
        return call(content, "AI识别文本");
    }

    private static List<TextBatchParser.RawRecord> call(JsonArray content, String sourceType) throws Exception {
        String apiKey = AppConfig.load().zhipuApiKey;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("未配置智谱 API Key");
        }

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        JsonArray messages = new JsonArray();
        messages.add(message);
        body.add("messages", messages);
        body.addProperty("temperature", 0.1);
        body.addProperty("max_tokens", 1024); // glm-4v-flash 上限 1024，超出返回 400/1210

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("智谱 API 返回 " + resp.statusCode() + "：" + trim(resp.body(), 300));
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new IOException("智谱 API 响应中没有 choices");
        }
        String answer = choices.get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();
        return parseAnswer(answer, sourceType);
    }

    /** 解析模型返回的 JSON 数组文本（容忍 ```json 围栏 / 前后说明文字） */
    static List<TextBatchParser.RawRecord> parseAnswer(String answer, String sourceType) {
        List<TextBatchParser.RawRecord> records = new ArrayList<>();
        if (answer == null || answer.trim().isEmpty()) {
            return records;
        }
        String json = answer.trim();
        // 去掉 ```json ... ``` 围栏
        if (json.startsWith("```")) {
            int first = json.indexOf('\n');
            int last = json.lastIndexOf("```");
            if (first >= 0 && last > first) {
                json = json.substring(first + 1, last).trim();
            }
        }
        // 截取第一个 [ 到最后一个 ]
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }

        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                Entry e = new Entry();
                e.setCategory(Entry.CATEGORY_WEBSITE);
                e.setPlatform(str(o, "platform"));
                e.setAccount(str(o, "account"));
                e.setPasswordEnc(str(o, "password"));
                e.setPhone(str(o, "phone"));
                e.setEmail(str(o, "email"));
                e.setNote(str(o, "note"));
                if (isBlank(e.getPlatform()) && isBlank(e.getAccount()) && isBlank(e.getPasswordEnc())) {
                    continue; // 全空条目丢弃
                }
                records.add(new TextBatchParser.RawRecord(e, answer, sourceType));
            }
        } catch (Exception ex) {
            // JSON 解析失败：返回空列表，由调用方提示
        }
        return records;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return "";
        }
        String s = el.getAsString();
        return s == null ? "" : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String extOf(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".png")) {
            return "png";
        }
        if (n.endsWith(".bmp")) {
            return "bmp";
        }
        return "jpeg";
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
