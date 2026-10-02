package com.wikiagent.application.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

/**
 * PDF 页栅格化（OCR/LAYOUT/TABLE 共用）。每次调用独立加载文档，无状态、线程安全。
 */
@Component
public class PdfPageRenderer {

    static final float DEFAULT_SCALE = 2.0f; // 72dpi * 2 = 144dpi

    public byte[] renderPng(byte[] pdfBytes, int pageIndex) {
        return renderPng(pdfBytes, pageIndex, DEFAULT_SCALE, null);
    }

    public byte[] renderPng(byte[] pdfBytes, int pageIndex, String password) {
        return renderPng(pdfBytes, pageIndex, DEFAULT_SCALE, password);
    }

    public byte[] renderPng(byte[] pdfBytes, int pageIndex, float scale, String password) {
        try (PDDocument doc = password == null || password.isBlank()
                ? Loader.loadPDF(pdfBytes) : Loader.loadPDF(pdfBytes, password)) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
                throw new IllegalArgumentException("PDF 页号越界: " + (pageIndex + 1));
            }
            java.awt.image.BufferedImage image = new PDFRenderer(doc).renderImage(pageIndex, scale);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("PDF 页面栅格化 PNG 失败 page=" + (pageIndex + 1));
            }
            return out.toByteArray();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PDF 栅格化失败 page=" + (pageIndex + 1) + ": " + e.getMessage(), e);
        }
    }
}
