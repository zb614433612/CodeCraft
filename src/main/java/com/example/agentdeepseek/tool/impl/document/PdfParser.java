package com.example.agentdeepseek.tool.impl.document;

import com.example.agentdeepseek.config.ChatAttachmentConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PDF 解析器 — 使用 Apache PDFBox 提取文本
 */
@Slf4j
@Component
public class PdfParser implements Parser {

    private static final Set<String> EXTENSIONS = Set.of("pdf");

    private final ChatAttachmentConfig config;

    public PdfParser(ChatAttachmentConfig config) {
        this.config = config;
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(Path filePath, Map<String, Object> options) throws Exception {
        long start = System.currentTimeMillis();

        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            int totalPages = document.getNumberOfPages();
            int maxPages = config.getParse().getPdfMaxPages();
            int pagesToRead = Math.min(totalPages, maxPages);

            // 提取元数据
            PDDocumentInformation info = document.getDocumentInformation();
            Map<String, String> metadata = new HashMap<>();
            metadata.put("pages", String.valueOf(totalPages));
            if (info.getTitle() != null) metadata.put("title", info.getTitle());
            if (info.getAuthor() != null) metadata.put("author", info.getAuthor());
            if (info.getCreationDate() != null) metadata.put("created", info.getCreationDate().toString());

            // 提取文本
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pagesToRead);
            String text = stripper.getText(document);

            String title = filePath.getFileName().toString();
            if (info.getTitle() != null && !info.getTitle().isBlank()) {
                title = info.getTitle();
            }

            ParsedDocument doc = ParsedDocument.builder()
                    .title(title)
                    .textContent(text)
                    .pageCount(totalPages)
                    .parserType("pdf")
                    .metadata(metadata)
                    .success(true)
                    .parseTimeMs(System.currentTimeMillis() - start)
                    .build();

            if (totalPages > maxPages) {
                doc.getMetadata().put("truncated", "true (显示前 " + maxPages + " / " + totalPages + " 页)");
            }

            log.debug("PDF解析完成: {} ({}页, {} 字符)", filePath.getFileName(), totalPages, text.length());
            return doc;
        }
    }
}
