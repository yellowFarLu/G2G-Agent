package com.wikiagent.service.ingest;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * 按扩展名解析文档为纯文本。支持：txt/md、pdf、docx、xlsx。
 * 旧版二进制 .doc/.xls 明确报错（不解析）。
 */
@Component
public class DocumentParser {

    public String parse(String filename, byte[] bytes) {
        String name = filename.toLowerCase();
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return switch (ext) {
            case "txt", "md", "markdown" -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            case "pdf" -> parsePdf(bytes);
            case "docx" -> parseDocx(bytes);
            case "xlsx" -> parseXlsx(bytes);
            case "doc", "xls" -> throw new IllegalArgumentException(
                    "暂不支持旧版二进制格式 ." + ext + "，请先另存为 ." + ext + "x / pdf / txt");
            default -> throw new IllegalArgumentException("不支持的文档类型: ." + ext);
        };
    }

    private String parsePdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(doc.getNumberOfPages());
            return stripper.getText(doc);
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    private String parseDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> parts = new ArrayList<>();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    parts.add(t.strip());
                }
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = row.getTableCells().stream()
                            .map(XWPFTableCell::getText)
                            .map(c -> c == null ? "" : c.strip())
                            .toList();
                    if (!String.join("", cells).isBlank()) {
                        parts.add(String.join(" | ", cells));
                    }
                }
            }
            return String.join("\n", parts);
        } catch (Exception e) {
            throw new IllegalStateException("Word(docx) 解析失败: " + e.getMessage(), e);
        }
    }

    private String parseXlsx(byte[] bytes) {
        org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                XSSFSheet sheet = wb.getSheetAt(i);
                parts.add("## Sheet: " + sheet.getSheetName());
                for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                        if (c > row.getFirstCellNum()) {
                            sb.append(" | ");
                        }
                        sb.append(fmt.formatCellValue(row.getCell(c)));
                    }
                    if (!sb.toString().isBlank()) {
                        parts.add(sb.toString());
                    }
                }
            }
            return String.join("\n", parts);
        } catch (Exception e) {
            throw new IllegalStateException("Excel(xlsx) 解析失败: " + e.getMessage(), e);
        }
    }
}
