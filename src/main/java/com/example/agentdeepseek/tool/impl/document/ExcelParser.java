package com.example.agentdeepseek.tool.impl.document;

import com.example.agentdeepseek.config.ChatAttachmentConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Excel 解析器 — 使用 Apache POI 提取文本
 * 支持 .xlsx、.xls、.csv，表格转 Markdown 表格格式
 */
@Slf4j
@Component
public class ExcelParser implements Parser {

    private static final Set<String> EXTENSIONS = Set.of("xlsx", "xls", "csv");

    private final ChatAttachmentConfig config;

    public ExcelParser(ChatAttachmentConfig config) {
        this.config = config;
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(Path filePath, Map<String, Object> options) throws Exception {
        long start = System.currentTimeMillis();
        String fileName = filePath.getFileName().toString();
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

        String text;
        if (ext.equals("csv")) {
            text = parseCsv(filePath);
        } else {
            text = parseExcel(filePath, ext);
        }

        return ParsedDocument.builder()
                .title(fileName)
                .textContent(text)
                .pageCount(1)
                .parserType("excel")
                .success(true)
                .parseTimeMs(System.currentTimeMillis() - start)
                .build();
    }

    private String parseExcel(Path filePath, String ext) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             Workbook workbook = ext.equals("xlsx") ? new XSSFWorkbook(fis) : new HSSFWorkbook(fis)) {

            int maxRows = config.getParse().getExcelMaxRows();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                sb.append("## ").append(sheet.getSheetName()).append("\n\n");

                int rowCount = 0;
                for (Row row : sheet) {
                    if (rowCount >= maxRows) {
                        sb.append("... (已截断，仅显示前 ").append(maxRows).append(" 行)\n");
                        break;
                    }
                    StringBuilder rowStr = new StringBuilder("| ");
                    int lastCell = row.getLastCellNum();
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        String cellValue = getCellValue(cell);
                        rowStr.append(cellValue.replace("|", "\\|").replace("\n", " "));
                        if (c < lastCell - 1) rowStr.append(" | ");
                    }
                    rowStr.append(" |");
                    sb.append(rowStr).append("\n");
                    rowCount++;
                }
                sb.append("\n");
            }
        }
        log.debug("Excel解析完成: {} ({} 字符)", filePath.getFileName(), sb.length());
        return sb.toString();
    }

    private String parseCsv(Path filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(filePath.getFileName().toString()).append("\n\n");
        int maxRows = config.getParse().getExcelMaxRows();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(filePath), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (count >= maxRows) {
                    sb.append("... (已截断，仅显示前 ").append(maxRows).append(" 行)\n");
                    break;
                }
                sb.append("| ").append(line.replace(",", " | ")).append(" |\n");
                count++;
            }
        }
        log.debug("CSV解析完成: {} ({} 行)", filePath.getFileName(), maxRows);
        return sb.toString();
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                yield val == (long) val ? String.valueOf((long) val) : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue(); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }
}
