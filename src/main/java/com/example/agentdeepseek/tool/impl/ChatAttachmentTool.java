package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.ChatAttachmentConfig;
import com.example.agentdeepseek.service.AttachmentStore;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.impl.document.ParserFactory;
import com.example.agentdeepseek.tool.impl.document.ParsedDocument;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 聊天附件工具 — 读取用户上传或指定的文件内容
 * 
 * <p>两种读取方式：</p>
 * <ul>
 *   <li><b>read_by_path</b>：用户粘贴路径到聊天框（如 "读一下 E:\\docs\\report.pdf"）→ LLM 调用此工具读取磁盘文件</li>
 *   <li><b>read_by_attachment</b>：用户通过前端📎按钮上传 → LLM 调用此工具读取已暂存附件</li>
 * </ul>
 * 
 * <p>支持格式：文本/代码、PDF、Word(.docx/.doc)、Excel(.xlsx/.xls/.csv)</p>
 */
@Slf4j
@Component
@ToolPermission(
        category = OperationCategory.READ,
        isPathSensitive = true,
        description = "聊天附件读取 — 支持文本/PDF/Word/Excel，读取用户上传或指定的文件"
)
public class ChatAttachmentTool implements Tool {

    private final ObjectMapper objectMapper;
    private final ParserFactory parserFactory;
    private final AttachmentStore attachmentStore;
    private final ChatAttachmentConfig config;

    public ChatAttachmentTool(ObjectMapper objectMapper,
                              ParserFactory parserFactory,
                              AttachmentStore attachmentStore,
                              ChatAttachmentConfig config) {
        this.objectMapper = objectMapper;
        this.parserFactory = parserFactory;
        this.attachmentStore = attachmentStore;
        this.config = config;
    }

    @Override
    public String getName() {
        return "chat_attachment";
    }

    @Override
    public String getDescription() {
        return "【适用场景】读取用户指定或上传的文件内容，支持文本/代码/PDF/Word/Excel。"
                + "两种方式：read_by_path=读取磁盘路径文件（用户在聊天中粘贴路径）；"
                + "read_by_attachment=读取用户通过上传按钮上传的附件。"
                + "【参数说明】action 必填（read_by_path 或 read_by_attachment）；"
                + "read_by_path 时需传 file_path；read_by_attachment 时需传 attachment_id。"
                + "【限制】PDF 最多解析 " + config.getParse().getPdfMaxPages() + " 页，"
                + "单文件最大输出 " + config.getParse().getContentMaxLength() + " 字符，"
                + "超出部分截断。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "读取用户指定或上传的文件");

        ObjectNode properties = objectMapper.createObjectNode();

        // action 参数
        ObjectNode actionProp = objectMapper.createObjectNode();
        actionProp.put("type", "string");
        actionProp.put("description", "操作类型：read_by_path=按文件路径读取磁盘文件；read_by_attachment=按附件ID读取已上传文件");
        ArrayNode actionEnum = objectMapper.createArrayNode()
                .add("read_by_path")
                .add("read_by_attachment");
        actionProp.set("enum", actionEnum);
        properties.set("action", actionProp);

        // file_path 参数
        ObjectNode filePathProp = objectMapper.createObjectNode();
        filePathProp.put("type", "string");
        filePathProp.put("description", "【read_by_path时必填】文件的绝对路径，如 E:\\docs\\report.pdf");
        properties.set("file_path", filePathProp);

        // attachment_id 参数
        ObjectNode attachmentIdProp = objectMapper.createObjectNode();
        attachmentIdProp.put("type", "string");
        attachmentIdProp.put("description", "【read_by_attachment时必填】前端上传后返回的附件ID");
        properties.set("attachment_id", attachmentIdProp);

        // page 参数
        ObjectNode pageProp = objectMapper.createObjectNode();
        pageProp.put("type", "integer");
        pageProp.put("description", "【可选】PDF指定页码（从1开始），不传则提取全部");
        properties.set("page", pageProp);

        parameters.set("properties", properties);

        // required
        ArrayNode required = objectMapper.createArrayNode().add("action");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        long start = System.currentTimeMillis();
        String action = arguments.has("action") ? arguments.get("action").asText() : "";

        try {
            return switch (action) {
                case "read_by_path" -> readByPath(arguments);
                case "read_by_attachment" -> readByAttachment(arguments);
                default -> error("未知操作: " + action + "，可选值: read_by_path / read_by_attachment");
            };
        } catch (Exception e) {
            log.error("ChatAttachmentTool 执行失败: action={}", action, e);
            return "【错误】" + e.getMessage();
        } finally {
            log.debug("ChatAttachmentTool 执行完成: action={}, 耗时 {}ms", action,
                    System.currentTimeMillis() - start);
        }
    }

    // ========== 方式一：按路径读取 ==========

    private String readByPath(JsonNode args) throws Exception {
        if (!args.has("file_path") || args.get("file_path").asText().isBlank()) {
            return error("缺少参数 file_path，请提供文件路径");
        }

        String filePath = args.get("file_path").asText().trim();

        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return error("文件不存在: " + filePath);
        }
        if (!Files.isReadable(path)) {
            return error("文件不可读: " + filePath);
        }

        return doParse(path, args);
    }

    // ========== 方式二：按附件ID读取 ==========

    private String readByAttachment(JsonNode args) throws Exception {
        if (!args.has("attachment_id") || args.get("attachment_id").asText().isBlank()) {
            return error("缺少参数 attachment_id，请提供附件ID");
        }

        String attachmentId = args.get("attachment_id").asText().trim();
        Path path = attachmentStore.get(attachmentId);

        if (path == null) {
            AttachmentStore.AttachmentMeta meta = attachmentStore.getMeta(attachmentId);
            if (meta != null) {
                return error("附件文件已被清理: " + meta.getFileName() + " (附件仅保留 "
                        + config.getStore().getExpireMinutes() + " 分钟)");
            }
            return error("附件不存在或已过期: " + attachmentId);
        }

        return doParse(path, args);
    }

    // ========== 公共解析逻辑 ==========

    private String doParse(Path path, JsonNode args) {
        Map<String, Object> options = new HashMap<>();
        if (args.has("page")) {
            options.put("page", args.get("page").asInt());
        }

        try {
            ParsedDocument doc = parserFactory.select(path).parse(path, options);

            if (!doc.isSuccess()) {
                return "【解析失败】" + doc.getError() + "\n文件: " + doc.getTitle();
            }

            StringBuilder result = new StringBuilder();
            result.append("=== ").append(doc.getTitle()).append(" ===\n");

            // 元数据摘要
            if (!doc.getMetadata().isEmpty()) {
                result.append("[类型: ").append(doc.getParserType())
                      .append(", 页数: ").append(doc.getPageCount());
                if (doc.getMetadata().containsKey("author")) {
                    result.append(", 作者: ").append(doc.getMetadata().get("author"));
                }
                result.append("]\n\n");
            }

            // 内容截断
            String content = doc.getTextContent();
            int maxLen = config.getParse().getContentMaxLength();
            if (content.length() > maxLen) {
                content = content.substring(0, maxLen)
                        + "\n\n... (内容已截断，共 " + doc.getTextContent().length()
                        + " 字符，仅显示前 " + maxLen + " 字符)";
            }
            result.append(content);

            return result.toString();

        } catch (IllegalArgumentException e) {
            return error("不支持的文件格式: " + path.getFileName() + " — " + e.getMessage());
        } catch (Exception e) {
            log.error("文件解析失败: {}", path, e);
            return error("文件解析失败: " + e.getMessage());
        }
    }

    private String error(String msg) {
        return "【chat_attachment 错误】" + msg;
    }
}
