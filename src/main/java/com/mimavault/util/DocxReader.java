package com.mimavault.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 轻量 docx 简易解析器（零依赖，避免引入 poi 大体积依赖）
 * <p>
 * docx 本质是 zip：word/document.xml 按文档顺序保存段落（文本 w:t 与 图片引用 a:blip）；
 * word/_rels/document.xml.rels 提供 rId -> media 文件映射。
 * 按段落顺序提取文字与图片（图片标记为 IMAGE:xxx），保证"图文交叉按原顺序解析"。
 * 仅支持现代 docx（OOXML），不支持 .doc 老格式。
 */
public final class DocxReader {

    /** 文档片段：要么是文本，要么是图片占位 */
    public static class Segment {
        public final boolean image;
        public final String text;        // 图片时为占位标记，如 [图片1]
        public final String imageName;   // media 文件名，如图片 image1.png
        public final byte[] imageBytes;  // 图片二进制（供 OCR）

        Segment(boolean image, String text, String imageName, byte[] imageBytes) {
            this.image = image;
            this.text = text;
            this.imageName = imageName;
            this.imageBytes = imageBytes;
        }
    }

    private DocxReader() {
    }

    /**
     * 按文档顺序读取 docx 内容
     *
     * @return 有序片段列表：文字片段 image=false；图片片段 image=true
     * @throws IOException 文件无法解析（非 docx / 损坏）
     */
    public static List<Segment> read(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("文件不存在");
        }
        byte[] zipBytes;
        try {
            zipBytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new IOException("读取文件失败：" + e.getMessage(), e);
        }

        // 读取 zip 内条目
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) {
                    continue;
                }
                entries.put(ze.getName(), readAll(zis));
            }
        } catch (Exception e) {
            throw new IOException("文件不是有效的 docx 文档（解压失败）", e);
        }

        byte[] docXml = entries.get("word/document.xml");
        if (docXml == null) {
            throw new IOException("docx 缺少 word/document.xml，可能不是标准文档");
        }
        byte[] relsXml = entries.get("word/_rels/document.xml.rels");

        // 解析 rId -> media 路径
        Map<String, String> relMap = parseRels(relsXml);

        // 解析正文段落
        List<Segment> segments = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(docXml));
            NodeList paras = doc.getElementsByTagNameNS("*", "p");
            int imgCounter = 0;
            for (int i = 0; i < paras.getLength(); i++) {
                Element para = (Element) paras.item(i);
                StringBuilder paraText = new StringBuilder();
                List<Segment> paraImages = new ArrayList<>();
                collectRunContent(para, paraText, paraImages, relMap, entries, imgCounter);
                imgCounter += paraImages.size();
                if (paraText.length() > 0) {
                    String t = paraText.toString().trim();
                    if (!t.isEmpty()) {
                        segments.add(new Segment(false, t, null, null));
                    }
                }
                segments.addAll(paraImages);
            }
        } catch (Exception e) {
            throw new IOException("docx 正文解析失败：" + e.getMessage(), e);
        }
        return segments;
    }

    /** 递归收集段落内文本与图片（保持文档顺序） */
    private static void collectRunContent(Node node, StringBuilder text, List<Segment> images,
                                          Map<String, String> relMap, Map<String, byte[]> entries, int imgCounter) {
        if (node == null) {
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String local = child.getLocalName();
            if (local == null) {
                local = child.getNodeName();
            }
            if ("t".equals(local)) {
                text.append(child.getTextContent());
                continue;
            }
            if ("blip".equals(local)) {
                // 图片引用：r:embed 属性
                String embed = getAttrNS(child, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
                if (embed == null || embed.isEmpty()) {
                    embed = child.getAttributes() != null ? child.getAttributes().getNamedItem("embed") != null
                            ? child.getAttributes().getNamedItem("embed").getNodeValue() : null : null;
                }
                if (embed != null) {
                    String mediaPath = relMap.get(embed);
                    if (mediaPath != null) {
                        if (!mediaPath.startsWith("word/")) {
                            mediaPath = "word/" + mediaPath;
                        }
                        byte[] imgBytes = entries.get(mediaPath);
                        if (imgBytes != null) {
                            String name = mediaPath.substring(mediaPath.lastIndexOf('/') + 1);
                            int idx = images.size() + imgCounter + 1;
                            images.add(new Segment(true, "[图片" + idx + "]", name, imgBytes));
                        }
                    }
                }
                continue;
            }
            collectRunContent(child, text, images, relMap, entries, imgCounter);
        }
    }

    private static String getAttrNS(Node node, String ns, String name) {
        if (node instanceof Element) {
            Element el = (Element) node;
            if (el.hasAttributeNS(ns, name)) {
                return el.getAttributeNS(ns, name);
            }
        }
        return "";
    }

    /** 解析 document.xml.rels：Id -> Target */
    private static Map<String, String> parseRels(byte[] relsXml) {
        Map<String, String> map = new HashMap<>();
        if (relsXml == null) {
            return map;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(relsXml));
            NodeList rels = doc.getElementsByTagNameNS("*", "Relationship");
            for (int i = 0; i < rels.getLength(); i++) {
                Element el = (Element) rels.item(i);
                String id = el.getAttribute("Id");
                String target = el.getAttribute("Target");
                if (id != null && target != null && target.contains("media/")) {
                    map.put(id, target);
                }
            }
        } catch (Exception ignored) {
            // rels 解析失败不影响正文
        }
        return map;
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
