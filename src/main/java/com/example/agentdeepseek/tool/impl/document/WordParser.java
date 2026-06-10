package com.example.agentdeepseek.tool.impl.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Word 解析器 — 使用 Apache POI 提取文本
 * 支持 .docx（XWPF），.doc 格式请先转换为 .docx
 */
@Slf4j
@Component
public class WordParser implements Parser {

    private static final Set<String> EXTENSIONS = Set.of("docx", "doc");

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(Path filePath, Map<String, Object> options) throws Exception {
        long start = System.currentTimeMillis();
        String fileName = filePath.getFileName().toString();
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

        if (ext.equals("doc")) {
            return ParsedDocument.builder()
                    .title(fileName)
                    .textContent("[不支持 .doc 格式] 请先将 .doc 文件转换为 .docx 格式后再上传。")
                    .pageCount(0)
                    .parserType("word")
                    .success(false)
                    .error(".doc 格式不支持，请转换为 .docx")
                    .parseTimeMs(System.currentTimeMillis() - start)
                    .build();
        }

        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument doc = new XWPFDocument(fis)) {

            // 提取段落
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            for (XWPFParagraph p : paragraphs) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }

            // 提取表格
            List<XWPFTable> tables = doc.getTables();
            if (!tables.isEmpty()) {
                sb.append("\n--- 表格内容 ---\n");
                for (int i = 0; i < tables.size(); i++) {
                    sb.append("【表格").append(i + 1).append("】\n");
                    XWPFTable table = tables.get(i);
                    for (int r = 0; r < table.getRows().size(); r++) {
                        List<XWPFTableCell> cells = table.getRow(r).getTableCells();
                        for (int c = 0; c < cells.size(); c++) {
                            sb.append(cells.get(c).getText());
                            if (c < cells.size() - 1) sb.append(" | ");
                        }
                        sb.append("\n");
                    }
                    sb.append("\n");
                }
            }
        }

        log.debug("Word(docx)解析完成: {} ({} 字符)", filePath.getFileName(), sb.length());
        return ParsedDocument.builder()
                .title(fileName)
                .textContent(sb.toString())
                .pageCount(1)
                .parserType("word")
                .success(true)
                .parseTimeMs(System.currentTimeMillis() - start)
                .build();
    }
}
